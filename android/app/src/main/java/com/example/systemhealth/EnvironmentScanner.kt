package com.example.systemhealth

import android.Manifest
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/*
 * minSdk 23; compileSdk 35+. Manifest requirements:
 * <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
 * <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
 * <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 * <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
 * <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
 * <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
 * <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
 * <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 * Request COARSE_LOCATION and FINE_LOCATION together at runtime; scanning
 * requires precise access. Android 12+ additionally needs SCAN/CONNECT.
 * startScan()/getScanResults() still require FINE_LOCATION on Android 13+.
 * Location services, Wi-Fi scanning, and Bluetooth must be enabled by the user.
 * This performs one foreground Wi-Fi scan and Bluetooth Classic discovery.
 * Classic discovery finds discoverable devices, not every nearby Bluetooth device.
 * scan() delivers the requested JSON asynchronously on the main thread.
 * No cached Wi-Fi results are substituted if a fresh scan fails or is throttled.
 */
class EnvironmentScanner(private val activity: Activity) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val wifiManager = checkNotNull(
        activity.applicationContext.getSystemService(WifiManager::class.java)
    )
    private val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter
    private val locationManager = checkNotNull(activity.getSystemService(LocationManager::class.java))
    private var running = false
    private var registered = false
    private var discoveryStarted = false
    private var wifiDone = false
    private var bluetoothDone = false
    private var closed = false
    private val wifi = linkedMapOf<String, JSONObject>()
    private val bluetooth = linkedMapOf<String, JSONObject>()
    private var resultCallback: ((JSONObject) -> Unit)? = null
    private var errorCallback: ((Exception) -> Unit)? = null
    private val timeout = Runnable { fail(IllegalStateException("Environment scan timed out")) }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(a: Activity) {
            if (a === activity && running) fail(IllegalStateException("Scan activity paused"))
        }
        override fun onActivityDestroyed(a: Activity) { if (a === activity) close() }
        override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
    }

    private val receiver = object : BroadcastReceiver() {
        @Suppress("MissingPermission", "DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (!running) return
            try {
                when (intent.action) {
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                        if (wifiDone) return
                        if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                            fail(IllegalStateException("Wi-Fi scan did not produce fresh results"))
                            return
                        }
                        for (network in wifiManager.scanResults) {
                            val bssid = network.BSSID ?: continue
                            wifi[bssid] = JSONObject()
                                .put("ssid", network.SSID.orEmpty())
                                .put("bssid", bssid)
                        }
                        wifiDone = true
                        maybeComplete()
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        if (!discoveryStarted || bluetoothDone) return
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        val address = device.address
                        bluetooth[address] = JSONObject()
                            .put("name", device.name.orEmpty())
                            .put("address", address)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (!discoveryStarted) return
                        discoveryStarted = false
                        bluetoothDone = true
                        maybeComplete()
                    }
                }
            } catch (error: SecurityException) { fail(error)
            } catch (error: Exception) { fail(error) }
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
    }

    @Suppress("MissingPermission", "DEPRECATION")
    fun scan(onResult: (JSONObject) -> Unit, onError: (Exception) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || running) {
            onError(IllegalStateException(if (closed) "Scanner is closed" else "A scan is already active"))
            return
        }
        resultCallback = onResult
        errorCallback = onError
        try {
            check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
                "A visible scan activity is required"
            }
            requirePermission(Manifest.permission.ACCESS_FINE_LOCATION)
            requirePermission(Manifest.permission.ACCESS_WIFI_STATE)
            requirePermission(Manifest.permission.CHANGE_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requirePermission(Manifest.permission.BLUETOOTH_SCAN)
                requirePermission(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                requirePermission(Manifest.permission.BLUETOOTH)
                requirePermission(Manifest.permission.BLUETOOTH_ADMIN)
            }
            val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
            check(locationEnabled) { "Location services are disabled" }
            check(wifiManager.isWifiEnabled || wifiManager.isScanAlwaysAvailable) { "Wi-Fi scanning is disabled" }
            val bt = checkNotNull(adapter) { "Bluetooth is unavailable" }
            check(bt.isEnabled) { "Bluetooth is disabled" }
            check(!bt.isDiscovering) { "Bluetooth discovery is already in progress" }
            wifi.clear()
            bluetooth.clear()
            wifiDone = false
            bluetoothDone = false
            running = true
            val filter = IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            // Bluetooth broadcasts can originate from a privileged process outside
            // the system UID. These actions are protected system broadcasts.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else activity.registerReceiver(receiver, filter)
            registered = true
            main.postDelayed(timeout, 30_000L)
            check(wifiManager.startScan()) { "Wi-Fi scan rejected or throttled" }
            discoveryStarted = bt.startDiscovery()
            check(discoveryStarted) { "Bluetooth discovery could not start" }
        } catch (error: SecurityException) { fail(error)
        } catch (error: Exception) { fail(error) }
    }

    private fun requirePermission(permission: String) {
        if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Permission denied: $permission")
        }
    }

    private fun maybeComplete() {
        if (!running || !wifiDone || !bluetoothDone) return
        val result = JSONObject()
            .put("wifi", JSONArray().apply { wifi.values.forEach { put(it) } })
            .put("bluetooth", JSONArray().apply { bluetooth.values.forEach { put(it) } })
        val callback = resultCallback
        cleanup()
        callback?.invoke(result)
    }

    private fun fail(error: Exception) {
        val callback = errorCallback
        cleanup()
        callback?.invoke(error)
    }

    @Suppress("MissingPermission")
    private fun cleanup() {
        running = false
        main.removeCallbacks(timeout)
        if (registered) {
            try { activity.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
            registered = false
        }
        if (discoveryStarted) {
            try { adapter?.cancelDiscovery() } catch (_: RuntimeException) { }
            discoveryStarted = false
        }
        wifi.clear()
        bluetooth.clear()
        resultCallback = null
        errorCallback = null
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        if (running) fail(IllegalStateException("Scanner closed")) else cleanup()
        activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
    }
}
