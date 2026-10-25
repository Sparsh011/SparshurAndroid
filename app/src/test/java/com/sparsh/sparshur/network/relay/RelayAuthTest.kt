package com.sparsh.sparshur.network.relay

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelayAuthTest {
    private fun vector() = JSONObject(javaClass.classLoader!!.getResourceAsStream("relay-auth-test-vectors.json")!!
        .bufferedReader().use { it.readText() })

    @Test fun publicOpenSslVectorMatchesTinkAndEveryAuthContextIsBound() {
        val v = vector()
        val endpoint = RelayEndpoint.parse(v.getString("endpoint"))
        val signed = RelayAuth.transcript(endpoint, v.getString("device_id"), v.getString("client_nonce"),
            v.getString("session_id"), v.getString("challenge"))
        assertEquals(v.getString("transcript"), String(signed, Charsets.UTF_8))
        val signature = Ed25519Sign(RelayAuth.decoded(v.getString("seed"), 32)).sign(signed)
        assertEquals(v.getString("signature"), RelayAuth.encoded(signature))
        val verify = Ed25519Verify(RelayAuth.decoded(v.getString("public_key"), 32))
        verify.verify(signature, signed)
        val changes = listOf(
            v.getString("transcript").replace("bridge-relay-v1/auth", "bridge-v2/pair"),
            v.getString("transcript").replace("relay.example.test", "other.example.test"),
            v.getString("transcript").replace(v.getString("device_id"), "33333333-3333-4333-8333-333333333333"),
            v.getString("transcript").replace(v.getString("session_id"), "44444444-4444-4444-8444-444444444444"),
            v.getString("transcript").replace(v.getString("client_nonce"), RelayAuth.encoded(ByteArray(32) { 9 })),
            v.getString("transcript").replace(v.getString("challenge"), RelayAuth.encoded(ByteArray(32) { 8 }))
        )
        changes.forEach { assertThrows(GeneralSecurityException::class.java) { verify.verify(signature, it.toByteArray()) } }
        assertEquals(RelayAuth.parse(v.getJSONObject("hello").toString()),
            RelayAuth.parse(RelayAuth.hello(v.getString("device_id"), v.getString("client_nonce"))))
        assertEquals(RelayAuth.parse(v.getJSONObject("auth").toString()),
            RelayAuth.parse(RelayAuth.response(v.getString("device_id"), v.getString("session_id"), signature)))
    }

    @Test fun endpointRejectsDowngradesCredentialUrlsRedirectInputsAndAmbiguousCanonicalization() {
        val suffix = RelayEndpoint.PATH
        val rejected = listOf("ws://example.test$suffix", "https://example.test$suffix", "wss://example.test/",
            "wss://user:pass@example.test$suffix", "wss://@example.test$suffix", "wss://example.test$suffix?token=x",
            "wss://example.test$suffix#fragment", "wss://example.test:443$suffix", "wss://EXAMPLE.test$suffix",
            "wss://example.test/a/../bridge/v1/ws", "wss://example.test/bridge%2fv1/ws", " wss://example.test$suffix",
            "wss://example.test$suffix\n", "wss://example.test\\@evil.test$suffix")
        rejected.forEach { assertThrows(it, IllegalArgumentException::class.java) { RelayEndpoint.parse(it) } }
        assertEquals("wss://localhost:8443$suffix", RelayEndpoint.parse("wss://localhost:8443$suffix").url)
        assertEquals("wss://[::1]:8443$suffix", RelayEndpoint.parse("wss://[::1]:8443$suffix").url)
    }

    @Test fun controlParserRejectsDuplicatesCoercionsNestingTrailingContentAndOversize() {
        val bad = listOf("{\"version\":1,\"version\":1}", "{\"version\":\"1\"}", "{\"version\":true}",
            "{\"version\":1.0}", "{\"version\":01}", "{version:1}", "{'version':1}", "{\"version\":1,}",
            "{\"version\":1}{}", "{\"version\":1,\"type\":{}}", "{\"version\":1,\"type\":null}",
            "{\"version\":1,\"type\":\"chall\\u0065nge\"}", "{\"version\":1,\"type\":\"bad text\"}",
            "{\"version\":1\u000b}", "{\"version\":1,\"type\":\"${"a".repeat(2048)}\"}")
        bad.forEach { assertThrows(it.take(80), IllegalArgumentException::class.java) { RelayAuth.parse(it) } }
        val frame = RelayAuth.parse(" \n { \"type\" : \"challenge\", \"version\" : 1 } \t")
        assertEquals("challenge", RelayAuth.string(frame, "type"))
        assertThrows(IllegalArgumentException::class.java) { RelayAuth.fields(frame, "version type challenge session_id") }
    }

    @Test fun fuzzedInvalidControlsCannotCoerceAChallengeOrVersion() {
        val random = java.util.Random(11)
        repeat(400) {
            val field = listOf("version", "session_id", "challenge", "type")[random.nextInt(4)]
            val text = JSONObject().put("version", 1).put("type", "challenge")
                .put("session_id", "22222222-2222-4222-8222-222222222222")
                .put("challenge", RelayAuth.encoded(ByteArray(32)))
                .put(field, if (random.nextBoolean()) true else JSONObject.NULL).toString()
            assertThrows(IllegalArgumentException::class.java) { RelayAuth.parse(text) }
        }
        listOf("not-base64", RelayAuth.encoded(ByteArray(31)), RelayAuth.encoded(ByteArray(32)).trimEnd('=')).forEach {
            assertThrows(IllegalArgumentException::class.java) { RelayAuth.decoded(it, 32) }
        }
        assertThrows(IllegalArgumentException::class.java) { RelayAuth.uuid("11111111-1111-1111-8111-111111111111") }
    }
}
