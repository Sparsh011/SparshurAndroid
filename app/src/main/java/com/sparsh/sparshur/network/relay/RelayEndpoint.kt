package com.sparsh.sparshur.network.relay

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Configuration identifies the TLS service, never an end-to-end peer key. */
class RelayEndpoint private constructor(val url: String, internal val httpUrl: HttpUrl) {
    companion object {
        const val PATH = "/bridge/v1/ws"
        const val SUBPROTOCOL = "bridge.relay.v1"

        fun parse(value: String): RelayEndpoint {
            require(value.length in 1..512 && value.all { it.code in 33..126 }) { "Enter a canonical WSS relay URL." }
            require(value.startsWith("wss://")) { "The relay requires wss:// and a valid TLS certificate." }
            val http = ("https://" + value.removePrefix("wss://")).toHttpUrl()
            require(http.username.isEmpty() && http.password.isEmpty() && http.query == null && http.fragment == null) {
                "Relay URLs cannot contain credentials, queries or fragments."
            }
            require(http.encodedPath == PATH) { "The relay path must be $PATH." }
            val canonical = "wss://" + http.toString().removePrefix("https://")
            require(canonical == value) { "Enter a canonical WSS relay URL without an explicit default port." }
            return RelayEndpoint(canonical, http)
        }
    }
}
