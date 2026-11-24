package com.sparsh.sparshur.network.relay

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.util.Base64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelayAuthTest {
    private fun vector(name: String) = JSONObject(javaClass.classLoader!!.getResourceAsStream("$name.json")!!
        .bufferedReader().use { it.readText() })

    @Test fun sharedAuthVectorMatchesTinkAndBindsEveryContext() {
        val v = vector("relay-auth")
        val config = RelayConfiguration(RelayEndpoint.parse(v.getString("relay_url")), v.getString("relay_id"))
        fun bytes(name: String) = Base64.getDecoder().decode(v.getString(name))
        val challenge = RelayChallenge(
            config.relayId, v.getString("session_id"), bytes("client_nonce").toByteString(),
            bytes("challenge").toByteString(), v.getLong("expires_at").toULong()
        )
        val signed = RelayAuth.transcript(config, v.getString("device_id"), challenge)
        val transcript = v.getString("signing_bytes")
        assertEquals(transcript, String(signed, Charsets.UTF_8))
        val signature = bytes("signature")
        val verify = Ed25519Verify(bytes("signing_public_key"))
        verify.verify(signature, signed)
        // Tink may randomize signing. Both signatures must verify over the exact transcript.
        verify.verify(Ed25519Sign(bytes("signing_seed")).sign(signed), signed)
        val parts = transcript.split('|')
        for (index in parts.indices) {
            val changed = parts.toMutableList().apply { this[index] += "x" }.joinToString("|")
            assertThrows(GeneralSecurityException::class.java) { verify.verify(signature, changed.toByteArray()) }
        }
    }

    @Test fun allNineSharedWireVectorsRoundTripAndDigestMatches() {
        val frames = vector("relay-wire").getJSONObject("frames_hex")
        assertEquals(9, frames.length())
        frames.keys().forEach { name ->
            val data = frames.getString(name).decodeHex()
            assertEquals(name, data, RelayFrame.decode(data).encode())
        }
        val submit = RelayFrame.decode(frames.getString("submit").decodeHex()) as RelayFrame.Submit
        val forwarded = RelayFrame.decode(frames.getString("forwarded").decodeHex()) as RelayFrame.Forwarded
        assertEquals(submit.packet.messageId, forwarded.messageId)
        assertEquals(submit.packet.digest(), forwarded.digest)
        assertEquals("08011009".decodeHex(), RelayFrame.decode("10090801".decodeHex()).encode())
    }

    @Test fun wireRejectsUnknownDuplicateZeroAndMalformedFields() {
        val frames = vector("relay-wire").getJSONObject("frames_hex")
        val submit = frames.getString("submit")
        val nested = submit.removePrefix("0801100552b501")
        val bad = listOf(
            "08001009", "080110090801", "080110096801", "0001", "0a01011009", "0d01", "0881001009",
            "080110055200", "08ffffffffffffffffffff01", "080110091a0178", "08011005520201",
            "0801100552b701" + nested + "4001", "0801100552b701" + nested + "3801",
            frames.getString("hello").replaceFirst("3333", "ff33"),
            frames.getString("hello").dropLast(2)
        )
        bad.forEach { assertThrows(Exception::class.java) { RelayFrame.decode(it.decodeHex()) } }
        assertThrows(IllegalArgumentException::class.java) {
            RelayFrame.decode(ByteArray(MAX_RELAY_FRAME + 1).toByteString())
        }
        val packet = (RelayFrame.decode(submit.decodeHex()) as RelayFrame.Submit).packet
        assertThrows(IllegalArgumentException::class.java) {
            packet.copy(protectedBody = ByteArray(32769).toByteString())
        }
    }

    @Test fun configuredUrlPreservesExactBytesAndRejectsAmbiguity() {
        val suffix = RelayEndpoint.PATH
        val rejected = listOf(
            "ws://example.test$suffix", "https://example.test$suffix", "wss://example.test/",
            "wss://user:pass@example.test$suffix", "wss://@example.test$suffix", "wss://example.test$suffix?",
            "wss://example.test$suffix#", "wss://example.test:0$suffix", "wss://example.test:65536$suffix", "wss://example.test/v1/%72elay",
            "wss://EXAMPLE.test$suffix", "wss://example.test/a/../v1/relay", " wss://example.test$suffix",
            "wss://example.test$suffix\n", "wss://example.test\\@evil.test$suffix", "wss://ex|ample.test$suffix"
        )
        rejected.forEach { assertThrows(it, IllegalArgumentException::class.java) { RelayEndpoint.parse(it) } }
        listOf("localhost:8443", "[::1]:8443", "example.test:443").forEach {
            val url = "wss://$it$suffix"
            assertEquals(url, RelayEndpoint.parse(url).url)
        }
        val endpoint = RelayEndpoint.parse("wss://example.test$suffix")
        listOf("", "a b", "a|b", "a".repeat(129)).forEach {
            assertThrows(IllegalArgumentException::class.java) { RelayConfiguration(endpoint, it) }
        }
    }
}
