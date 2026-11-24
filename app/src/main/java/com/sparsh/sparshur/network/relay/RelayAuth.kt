package com.sparsh.sparshur.network.relay

import com.google.crypto.tink.subtle.Base64

/** An enrolled device proves possession of its existing signing key. */
interface RelaySigner {
    val deviceId: String
    fun sign(message: ByteArray): ByteArray
}

internal object RelayAuth {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun uuid(value: String): String = value.also { require(uuid.matches(it)) }

    fun validRelayId(value: String): Boolean =
        value.length in 1..128 && value.all { it.code in 33..126 && it != '|' }

    fun encoded(bytes: ByteArray): String = Base64.encode(bytes)

    fun transcript(configuration: RelayConfiguration, deviceId: String, challenge: RelayChallenge): ByteArray {
        require(challenge.relayId == configuration.relayId)
        return listOf(
            "bridge-relay-v1/auth", configuration.endpoint.url, challenge.relayId, uuid(deviceId),
            challenge.sessionId, encoded(challenge.nonce.toByteArray()), encoded(challenge.challenge.toByteArray()), challenge.expiresAt.toString()
        ).joinToString("|").toByteArray(Charsets.UTF_8)
    }
}
