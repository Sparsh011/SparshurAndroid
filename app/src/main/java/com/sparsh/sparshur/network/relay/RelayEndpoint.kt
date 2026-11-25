package com.sparsh.sparshur.network.relay

import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Preserve the exact external URL for signing, including an explicit default port. */
class RelayEndpoint private constructor(val url: String, internal val httpUrl: HttpUrl) {
    companion object {
        const val PATH = "/v1/relay"
        const val SUBPROTOCOL = "bridge.relay.v1"

        fun parse(value: String): RelayEndpoint {
            require(value.toByteArray(Charsets.UTF_8).size <= 2048 && '|' !in value)
            val uri = URI.create(value)
            require(uri.scheme == "wss" && uri.rawAuthority != null && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == PATH &&
                uri.toASCIIString() == value && uri.authority == uri.authority.lowercase())
            val http = ("https://" + value.removePrefix("wss://")).toHttpUrl()
            require('@' !in uri.rawAuthority && http.encodedPath == uri.rawPath)
            return RelayEndpoint(value, http)
        }
    }
}

class RelayConfiguration(val endpoint: RelayEndpoint, val relayId: String) {
    init { require(RelayAuth.validRelayId(relayId)) }
}
