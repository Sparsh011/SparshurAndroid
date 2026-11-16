package com.sparsh.sparshur.lab.network

/** Literal addresses avoid DNS rebinding and accidental sends to an internet host. */
data class LocalEndpoint(val address: String, val port: Int) {
    init {
        validateEndpoint(address, port, allowTailscale = false)
    }

    val url: String get() = "http://$address:$port"
}

/** Paired traffic also follows the system VPN route to a Tailscale IPv4 address. */
data class PairedEndpoint(val address: String, val port: Int) {
    init {
        validateEndpoint(address, port, allowTailscale = true)
    }

    val url: String get() = "http://$address:$port"
}

private fun validateEndpoint(address: String, port: Int, allowTailscale: Boolean) {
    val parts = address.split('.')
    require(
        parts.size == 4 && parts.all {
            it.isNotEmpty() && it.all(Char::isDigit) &&
                (it.toIntOrNull() ?: -1) in 0..255 && it.toInt().toString() == it
        }
    ) { "Enter the Mac's numeric IPv4 address, without http:// or a path." }
    val first = parts[0].toInt()
    val second = parts[1].toInt()
    require(
        first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            address == "127.0.0.1" ||
            (allowTailscale && first == 100 && second in 64..127)
    ) {
        if (allowTailscale) "Use a private IPv4 address, 127.0.0.1, or a Tailscale IPv4 address (100.64.0.0/10)."
        else "This lab only sends to private IPv4 addresses or 127.0.0.1."
    }
    require(port in 1024..65535) { "Use a port from 1024 to 65535; the default is 8787." }
}
