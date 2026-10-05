package com.example.systemhealth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!CoreService.isMonitoringEnabled(context) || CoreService.isRunning) return

        try {
            CoreService.start(context)
        } catch (exception: RuntimeException) {
            // Respect Android's startup restrictions; do not schedule bypass attempts.
            Log.e("BootReceiver", "Android denied starting CoreService at boot", exception)
        }
    }
}
