package com.yourapp.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Usage (Application.onCreate):
 *
 *   val sync = SyncManager.init(this, SyncManager.Config(serverUrl = "https://api.example.com"))
 *   networkMonitor = NetworkMonitor(this, sync).also { it.start() }
 */
class NetworkMonitor(
    context: Context,
    private val syncManager: SyncManager,
    private val requireValidatedNetwork: Boolean = true,
    private val availableDebounceMs: Long = 1_000L
) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registered = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val lock = Any()

    // Guarded by [lock].
    private var currentNetwork: Network? = null
    private var lastUsableNetwork: Network? = null
    private var pendingOnlineJob: Job? = null

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            synchronized(lock) { currentNetwork = network }
            // On API 26+ onCapabilitiesChanged always follows; this covers API 24-25 and
            // the requireValidatedNetwork = false case.
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps != null && hasUsableInternet(caps)) onNetworkUp(network)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            synchronized(lock) {
                if (currentNetwork == null) currentNetwork = network
                if (network != currentNetwork) return
            }
            if (hasUsableInternet(caps)) onNetworkUp(network) else onNetworkDown()
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (network != currentNetwork) return
                currentNetwork = null
            }
            onNetworkDown()
        }
    }

    fun start() {
        check(!released.get()) { "NetworkMonitor has been released" }
        if (!registered.compareAndSet(false, true)) return

        // Decide the initial state ourselves: if we are already online, the first callback
        // would otherwise be ignored as "no change" and data queued earlier would never sync.
        if (isCurrentlyOnline()) {
            _isOnline.value = true
            syncManager.resumeUploads()
            syncManager.syncData()
        } else {
            _isOnline.value = false
            syncManager.pauseUploads()
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // e.g. SecurityException (missing ACCESS_NETWORK_STATE) or too many callbacks.
            registered.set(false)
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    fun stop() {
        if (!registered.compareAndSet(true, false)) return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was not registered", e)
        }
        synchronized(lock) {
            pendingOnlineJob?.cancel()
            pendingOnlineJob = null
            currentNetwork = null
            lastUsableNetwork = null
        }
    }

    /** Stops monitoring permanently. [start] cannot be called afterwards. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        stop()
        scope.cancel()
    }

    private fun onNetworkUp(network: Network) {
        synchronized(lock) {
            val switchedNetwork = lastUsableNetwork != null && lastUsableNetwork != network
            lastUsableNetwork = network
            // Already online on the same network (or a sync is already scheduled): nothing to do.
            if (_isOnline.value && !switchedNetwork) return
            _isOnline.value = true

            pendingOnlineJob?.cancel()
            // Debounce flapping networks before reconnecting the socket. On a Wi-Fi <-> mobile
            // switch the old socket is dead, so a fresh sync is triggered on the new network.
            pendingOnlineJob = scope.launch {
                delay(availableDebounceMs)
                Log.d(TAG, "Network available, triggering sync")
                syncManager.resumeUploads()
                syncManager.syncData()
            }
        }
    }

    private fun onNetworkDown() {
        synchronized(lock) {
            pendingOnlineJob?.cancel()
            pendingOnlineJob = null
            lastUsableNetwork = null
            if (!_isOnline.value && syncManager.isPaused) return
            _isOnline.value = false
        }
        Log.d(TAG, "Network lost, pausing uploads")
        syncManager.pauseUploads()
    }

    private fun isCurrentlyOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return hasUsableInternet(caps)
    }

    private fun hasUsableInternet(caps: NetworkCapabilities): Boolean {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (requireValidatedNetwork && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        ) return false
        return true
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
