package com.example.utility.sync

import java.net.URI

internal object ServerAddressPolicy {
    fun validate(value: String, allowDebugHttp: Boolean = false): URI {
        val uri = URI.create(value)
        require(!uri.host.isNullOrBlank()) { "Enter a server URL with a host." }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
            "Enter the server origin without a URL path."
        }
        require(uri.port == -1 || uri.port in 1..65535)
        val localHttp = allowDebugHttp && uri.scheme == "http" && isLocalHost(uri.host)
        require(uri.scheme == "https" || localHttp) {
            "Use HTTPS. Debug builds also allow HTTP to a local/private IP address."
        }
        return uri
    }

    private fun isLocalHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host.removeSurrounding("[", "]") == "::1") return true
        val pieces = host.split('.')
        if (pieces.size != 4 || pieces.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return false
        val numbers = pieces.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return numbers[0] == 127 || numbers[0] == 10 ||
            (numbers[0] == 172 && numbers[1] in 16..31) ||
            (numbers[0] == 192 && numbers[1] == 168)
    }
}
