package com.example.systemhealth

internal object ReadinessPolicy {
    fun title(enrolled: Boolean, notifications: Boolean, verified: Boolean, running: Boolean, uploaded: Boolean): String = when {
        !enrolled -> "Connect this phone"
        !notifications -> "Status notifications needed"
        !verified -> "Check the server connection"
        !running -> "Ready — monitoring stopped"
        !uploaded -> "Waiting for the first upload"
        else -> "Connected and monitoring"
    }
}
