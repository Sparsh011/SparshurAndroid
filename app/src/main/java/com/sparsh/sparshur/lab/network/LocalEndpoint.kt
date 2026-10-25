package com.sparsh.sparshur.lab.network

/** Literal addresses avoid DNS rebinding and accidental sends to an internet host. */
data class LocalEndpoint(val address: String, val port: Int) {
    init {
        val parts = address.split('.')
        require(
            parts.size == 4 && parts.all {
                it.isNotEmpty() && it.all(Char::isDigit) &&
                    (it.toIntOrNull() ?: -1) in 0..255 && it.toInt().toString() == it
            }
        ) { "Enter the Mac's numeric IPv4 address, without http:// or a path." }
        val octets = parts.map(String::toInt)
        require(
            octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                address == "127.0.0.1"
        ) { "This lab only sends to private IPv4 addresses or 127.0.0.1." }
        require(port in 1024..65535) { "Use a port from 1024 to 65535; the default is 8787." }
    }

    val url: String get() = "http://$address:$port"
}
