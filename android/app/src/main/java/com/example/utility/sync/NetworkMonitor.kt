// NetworkMonitor.kt
package com.example.utility.sync

// Requires minSdk 26.
// Call start(), stop(), and close() on the main thread.

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

class NetworkMonitor(
    context: Context,
    private val syncManager: SyncManager
) : AutoCloseable {

    private val connectivityManager =
        context.applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

    private val handler = Handler(Looper.getMainLooper())

    private var started = false
    private var closed = false
    private var defaultNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!started) return

            defaultNetwork = network

            // Wait for onCapabilitiesChanged before uploading. A network can
            // be available while still requiring captive-portal authentication.
            syncManager.pauseUploads()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (!started || network != defaultNetwork) return

            updateSyncState(capabilities.hasValidatedInternet())
        }

        override fun onLost(network: Network) {
            if (!started || network != defaultNetwork) return

            defaultNetwork = null
            syncManager.pauseUploads()
        }

        override fun onUnavailable() {
            if (!started) return

            defaultNetwork = null
            syncManager.pauseUploads()
        }
    }

    fun start() {
        requireMainThread()
        check(!closed) { "NetworkMonitor is closed." }
        if (started) return

        // The snapshot and callbacks run on the same thread. Registration
        // callbacks subsequently reconcile any changes during this snapshot.
        defaultNetwork = connectivityManager.activeNetwork
        val initialCapabilities = defaultNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
        }

        started = true

        try {
            connectivityManager.registerDefaultNetworkCallback(
                callback,
                handler
            )
        } catch (error: RuntimeException) {
            started = false
            defaultNetwork = null
            syncManager.pauseUploads()
            throw error
        }

        updateSyncState(
            initialCapabilities?.hasValidatedInternet() == true
        )
    }

    fun stop() {
        requireMainThread()
        if (!started) return

        started = false
        defaultNetwork = null
        syncManager.pauseUploads()
        connectivityManager.unregisterNetworkCallback(callback)
    }

    override fun close() {
        requireMainThread()
        if (closed) return

        stop()
        closed = true
    }

    private fun updateSyncState(available: Boolean) {
        if (available) {
            syncManager.networkAvailable()
        } else {
            syncManager.pauseUploads()
        }
    }

    private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "NetworkMonitor lifecycle methods must run on the main thread."
        }
    }
}
