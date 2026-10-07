package com.example.systemhealth

import android.Manifest
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
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one nearby-scan engine: the tools screen and the background request loop both call this,
 * so a scan means the same thing (and takes the same 35 seconds) whichever path started it.
 *
 * All Android Wi-Fi and Bluetooth scan permissions must already be granted before calling [scan].
 * This does NOT request permissions. It throws when a shared prerequisite is missing (location
 * services off, Wi-Fi scanning off, Bluetooth off or already discovering) and otherwise returns a
 * JSONObject with "wifi", "bluetooth" and "notes". A radio that Android throttles is recorded in
 * "notes" instead of discarding the other radio's results; a fresh Wi-Fi list is never replaced by
 * a stored one.
 *
 * Bluetooth Classic discovery finds only discoverable devices. Android 12+ needs
 * BLUETOOTH_SCAN / BLUETOOTH_CONNECT; earlier versions need BLUETOOTH / BLUETOOTH_ADMIN.
 * Wi-Fi scanning requires ACCESS_FINE_LOCATION on Android 13+.
 *
 * Must be called from a coroutine. The scan times out after [TIMEOUT_MS] milliseconds.
 */
internal object HeadlessScan {
    private const val TIMEOUT_MS = 35_000L

    @Suppress("MissingPermission", "DEPRECATION")
    suspend fun scan(context: Context): JSONObject {
        val app = context.applicationContext

        // --- Pre-flight permission checks ---
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        val denied = required.filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (denied.isNotEmpty()) {
            throw SecurityException("Nearby scan needs these permissions: ${denied.joinToString { it.substringAfterLast('.') }}")
        }

        // --- System pre-conditions ---
        val locationManager = checkNotNull(app.getSystemService(LocationManager::class.java))
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        check(locationEnabled) {
            "Enable Location services before running a nearby scan."
        }

        val wifiManager = checkNotNull(app.getSystemService(WifiManager::class.java))
        check(wifiManager.isWifiEnabled || wifiManager.isScanAlwaysAvailable) {
            "Enable Wi-Fi (or Allow Wi-Fi scanning in Location settings) before running a nearby scan."
        }

        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        checkNotNull(adapter) { "Bluetooth is unavailable on this device." }
        check(adapter.isEnabled) { "Enable Bluetooth before running a nearby scan." }
        check(!adapter.isDiscovering) { "Bluetooth discovery is already in progress. Try again shortly." }

        return suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            val wifi = linkedMapOf<String, JSONObject>()
            val bluetooth = linkedMapOf<String, JSONObject>()
            val notes = JSONObject()
            var wifiDone = false
            var bluetoothDone = false
            var discoveryStarted = false
            var settled = false
            var receiverRegistered = false
            var receiver: BroadcastReceiver? = null

            fun settle(block: () -> Unit) {
                if (settled) return
                settled = true
                block()
            }

            fun cleanup() {
                main.removeCallbacksAndMessages(null)
                if (receiverRegistered) {
                    receiver?.let { runCatching { app.unregisterReceiver(it) } }
                    receiverRegistered = false
                }
                if (discoveryStarted) {
                    runCatching { adapter.cancelDiscovery() }
                    discoveryStarted = false
                }
            }

            fun complete() {
                settle {
                    cleanup()
                    continuation.resume(JSONObject()
                        .put("wifi", JSONArray().apply { wifi.values.forEach { put(it) } })
                        .put("bluetooth", JSONArray().apply { bluetooth.values.forEach { put(it) } })
                        .put("notes", notes))
                }
            }

            /** Android throttles one radio without touching the other, so a one-sided scan is still
             * worth uploading. The reason travels with it instead of throwing both results away, and no
             * cached Wi-Fi list is ever substituted for a fresh scan. */
            fun degrade(source: String, reason: String) {
                notes.put(source, reason)
                if (source == "wifi") wifiDone = true else bluetoothDone = true
                if (wifiDone && bluetoothDone) complete()
            }

            fun fail(error: Exception) {
                settle {
                    cleanup()
                    continuation.resumeWithException(error)
                }
            }

            receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (settled) return
                    try {
                        when (intent.action) {
                            WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                                if (wifiDone) return
                                if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                                    degrade("wifi", "Android throttled Wi-Fi scanning, so no fresh results were available.")
                                    return
                                }
                                for (network in wifiManager.scanResults) {
                                    val bssid = network.BSSID ?: continue
                                    wifi[bssid] = JSONObject()
                                        .put("ssid", network.SSID.orEmpty())
                                        .put("bssid", bssid)
                                }
                                wifiDone = true
                                if (bluetoothDone) complete()
                            }
                            BluetoothDevice.ACTION_FOUND -> {
                                if (!discoveryStarted || bluetoothDone) return
                                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                }
                                val address = device?.address ?: return
                                bluetooth[address] = JSONObject()
                                    .put("name", device.name.orEmpty())
                                    .put("address", address)
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                if (!discoveryStarted) return
                                discoveryStarted = false
                                bluetoothDone = true
                                if (wifiDone) complete()
                            }
                        }
                    } catch (e: SecurityException) { fail(e) } catch (e: Exception) { fail(e) }
                }
            }

            // A radio that never reports leaves a partial scan; a scan with nothing at all is a failure.
            val timeoutRunnable = Runnable {
                if (!wifiDone && !bluetoothDone) {
                    fail(IllegalStateException("Nearby scan timed out after ${TIMEOUT_MS / 1000}s. Check Wi-Fi and Bluetooth."))
                } else {
                    if (!wifiDone) notes.put("wifi", "No Wi-Fi scan result within ${TIMEOUT_MS / 1000}s.")
                    if (!bluetoothDone) notes.put("bluetooth", "Bluetooth discovery did not finish within ${TIMEOUT_MS / 1000}s.")
                    complete()
                }
            }

            continuation.invokeOnCancellation {
                main.post { cleanup() }
            }

            main.post {
                try {
                    val filter = IntentFilter().apply {
                        addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                        addAction(BluetoothDevice.ACTION_FOUND)
                        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                    }
                    val r = checkNotNull(receiver) { "Receiver not initialized" }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Bluetooth and Wi-Fi broadcasts come from privileged system processes.
                        app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        app.registerReceiver(r, filter)
                    }
                    receiverRegistered = true
                    main.postDelayed(timeoutRunnable, TIMEOUT_MS)

                    if (!wifiManager.startScan()) {
                        degrade("wifi", "Android rejected the Wi-Fi scan; no stored results were used.")
                    }
                    discoveryStarted = adapter.startDiscovery()
                    if (!discoveryStarted) {
                        degrade("bluetooth", "Bluetooth discovery could not start; check it is on and not busy.")
                    }
                } catch (e: SecurityException) { fail(e) } catch (e: Exception) { fail(e) }
            }
        }
    }
}
