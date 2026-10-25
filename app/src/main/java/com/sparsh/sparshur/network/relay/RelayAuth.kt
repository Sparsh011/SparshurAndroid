package com.sparsh.sparshur.network.relay

import com.google.crypto.tink.subtle.Base64
import java.util.UUID

/** An enrolled device proves possession; the relay never receives a private key. */
interface RelaySigner {
    val deviceId: String
    fun sign(message: ByteArray): ByteArray
}

internal object RelayAuth {
    const val MAX_BYTES = 2048
    private val pair = Regex("\\s*\"([a-z_]+)\"\\s*:\\s*(\"[A-Za-z0-9_./:+=-]*\"|[0-9]+)\\s*")
    private val uuid4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    fun uuid(value: String): String {
        require(uuid4.matches(value) && UUID.fromString(value).toString() == value)
        return value
    }

    fun encoded(bytes: ByteArray): String = Base64.encode(bytes)

    fun decoded(value: String, length: Int): ByteArray = Base64.decode(value).also {
        require(it.size == length && encoded(it) == value)
    }

    /** Deliberately flat control grammar. Reject duplicates and Android JSONObject leniency. */
    fun parse(text: String): Map<String, String> {
        require(text.length <= MAX_BYTES && text.all { it.code in setOf(9, 10, 13) || it.code in 32..126 })
        val body = text.trim()
        require(body.startsWith('{') && body.endsWith('}'))
        val inner = body.substring(1, body.length - 1)
        val result = linkedMapOf<String, String>()
        var offset = 0
        while (offset < inner.length) {
            val match = pair.find(inner, offset)
            require(match != null && match.range.first == offset)
            val name = match.groupValues[1]
            require(result.put(name, match.groupValues[2]) == null && result.size <= 6)
            offset = match.range.last + 1
            if (offset == inner.length) break
            require(inner[offset] == ',')
            offset++
            require(offset < inner.length && inner.substring(offset).isNotBlank())
        }
        require(result["version"] == "1")
        return result
    }

    fun string(frame: Map<String, String>, key: String): String {
        val value = checkNotNull(frame[key])
        require(value.startsWith('"') && value.endsWith('"'))
        return value.substring(1, value.length - 1)
    }

    fun fields(frame: Map<String, String>, expected: String) {
        require(frame.keys == expected.split(' ').toSet())
    }

    fun hello(deviceId: String, nonce: String): String {
        uuid(deviceId)
        decoded(nonce, 32)
        return "{\"version\":1,\"type\":\"hello\",\"device_id\":\"$deviceId\",\"client_nonce\":\"$nonce\"}"
    }

    fun transcript(endpoint: RelayEndpoint, deviceId: String, nonce: String, sessionId: String, challenge: String): ByteArray {
        uuid(deviceId)
        uuid(sessionId)
        decoded(nonce, 32)
        decoded(challenge, 32)
        return "bridge-relay-v1/auth|${endpoint.url}|$deviceId|$nonce|$sessionId|$challenge".toByteArray(Charsets.UTF_8)
    }

    fun response(deviceId: String, sessionId: String, signature: ByteArray): String {
        uuid(deviceId)
        uuid(sessionId)
        require(signature.size == 64)
        return "{\"version\":1,\"type\":\"auth\",\"device_id\":\"$deviceId\",\"session_id\":\"$sessionId\",\"signature\":\"${encoded(signature)}\"}"
    }
}
