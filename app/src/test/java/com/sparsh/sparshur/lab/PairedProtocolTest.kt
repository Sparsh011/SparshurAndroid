package com.sparsh.sparshur.lab

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.subtle.ChaCha20Poly1305
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import com.sparsh.sparshur.lab.paired.PairedProtocol as P
import java.security.GeneralSecurityException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairedProtocolTest {
    private fun vector() = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("paired-test-vectors.json")!!
            .bufferedReader().use { it.readText() }
    )
    private fun identity(v: JSONObject): P.Identity {
        val request = v.getJSONObject("pair_request")
        return P.Identity(
            request.getString("sender_id"),
            P.bytes(v.getString("android_seed"), 32..32),
            P.bytes(request.getString("sender_key"), 32..32)
        )
    }
    private fun bundle(v: JSONObject) = P.bundle(v.getJSONObject("bundle").toString(), now = 1000000000)

    @Test fun pairingProofAndMacConfirmationMatchSwift() {
        val v = vector()
        val bundle = bundle(v)
        val id = identity(v)
        val request = P.pairRequest(bundle, id)
        assertEquals(v.getJSONObject("pair_request").getString("proof"), request.getString("proof"))
        val signed = ("bridge-v2/pair|" + bundle.transcript(id)).toByteArray()
        Ed25519Verify(id.publicKey).verify(P.bytes(request.getString("signature"), 64..64), signed)
        Ed25519Verify(
            id.publicKey
        ).verify(P.bytes(v.getJSONObject("pair_request").getString("signature"), 64..64), signed)
        P.confirm(bundle, id, v.getJSONObject("pair_ack"))
        val corrupted = JSONObject(v.getJSONObject("pair_ack").toString()).put("proof", P.b64(ByteArray(32)))
        assertThrows(IllegalArgumentException::class.java) { P.confirm(bundle, id, corrupted) }
        val wrongKey = bundle.peer.copy(signingKey = ByteArray(32) { 7 })
        assertThrows(IllegalArgumentException::class.java) {
            P.confirm(bundle.copy(peer = wrongKey), id, v.getJSONObject("pair_ack"))
        }
        val badSignature = JSONObject(v.getJSONObject("pair_ack").toString()).put("signature", P.b64(ByteArray(64)))
        assertThrows(GeneralSecurityException::class.java) { P.confirm(bundle, id, badSignature) }
    }

    @Test fun swiftHpkeAndPayloadDecryptWithTink() {
        val v = vector()
        val b = bundle(v)
        val message = v.getJSONObject("message")
        HybridConfig.register()
        val params = HpkeParameters.builder().setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256).setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
            .setVariant(HpkeParameters.Variant.NO_PREFIX).build()
        val pub = HpkePublicKey.create(params, Bytes.copyFrom(b.peer.hpkeKey), null)
        val key = HpkePrivateKey.create(
            pub,
            SecretBytes.copyFrom(P.bytes(v.getString("mac_hpke_seed"), 32..32), InsecureSecretKeyAccess.get())
        )
        val handle = KeysetHandle.newBuilder().addEntry(
            KeysetHandle.importKey(key).withRandomId().makePrimary()
        ).build()
        val decrypt = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
        val contentKey = decrypt.decrypt(
            P.bytes(message.getString("wrapped_key"), 80..80),
            ("bridge-v2/key-wrap|" + P.aad(message)).toByteArray()
        )
        assertArrayEquals(P.bytes(v.getString("content_key"), 32..32), contentKey)
        val plaintext = ChaCha20Poly1305(contentKey).decrypt(
            P.bytes(message.getString("nonce"), 12..12) +
                P.bytes(message.getString("ciphertext"), 17..4112),
            P.aad(message).toByteArray()
        )
        assertEquals(v.getString("plaintext"), String(plaintext, Charsets.UTF_8))
        assertEquals(v.getString("aad"), P.aad(message))
        assertEquals(v.getString("signed_message"), P.signedMessage(message))
        Ed25519Verify(
            identity(v).publicKey
        ).verify(P.bytes(message.getString("signature"), 64..64), P.signedMessage(message).toByteArray())
        assertThrows(GeneralSecurityException::class.java) {
            decrypt.decrypt(
                P.bytes(
                    message.getString("wrapped_key"),
                    80..80
                ),
                "wrong-domain".toByteArray()
            )
        }
    }

    @Test fun verifiesMacReceiptAndRejectsForgedOrSubstitutedReceipts() {
        val v = vector()
        val peer = bundle(v).peer
        val message = v.getJSONObject("message")
        assertFalse(P.verifyReceipt(peer, message, v.getJSONObject("receipt")))
        val changed = JSONObject(v.getJSONObject("receipt").toString()).put("duplicate", true)
        assertThrows(GeneralSecurityException::class.java) { P.verifyReceipt(peer, message, changed) }
        changed.put("counter", 999)
        assertThrows(IllegalArgumentException::class.java) { P.verifyReceipt(peer, message, changed) }
    }

    @Test fun encryptionUsesFreshContentKeysAndSignsAllFields() {
        val v = vector()
        val peer = bundle(v).peer
        val id = identity(v)
        val one = P.encrypt(peer, id, 1, "sample 🔐")
        val two = P.encrypt(peer, id, 2, "sample 🔐")
        assertNotEquals(one.getString("ciphertext"), two.getString("ciphertext"))
        assertNotEquals(one.getString("wrapped_key"), two.getString("wrapped_key"))
        assertFalse(one.toString().contains("sample"))
        Ed25519Verify(
            id.publicKey
        ).verify(P.bytes(one.getString("signature"), 64..64), P.signedMessage(one).toByteArray())
        one.put("counter", 3)
        assertThrows(GeneralSecurityException::class.java) {
            Ed25519Verify(
                id.publicKey
            ).verify(P.bytes(one.getString("signature"), 64..64), P.signedMessage(one).toByteArray())
        }
    }

    @Test fun bundleRejectsExpiryPublicDestinationsAndMalformedKeys() {
        val v = vector()
        val q = v.getJSONObject("bundle")
        assertThrows(IllegalArgumentException::class.java) { P.bundle(q.toString(), now = 1000000400) }
        q.put("host", "8.8.8.8")
        assertThrows(IllegalArgumentException::class.java) { P.bundle(q.toString(), now = 1000000000) }
        q.put("host", "127.0.0.1").put("hpke_key", "bad")
        assertThrows(IllegalArgumentException::class.java) { P.bundle(q.toString(), now = 1000000000) }
        val pristine = vector().getJSONObject("bundle").toString()
        val random = java.util.Random(7)
        repeat(300) {
            val corrupt = JSONObject(pristine)
            val fields = listOf("pairing_id", "host", "port", "expires_at", "secret", "signing_key", "hpke_key")
            corrupt.put(fields[random.nextInt(fields.size)], if (random.nextBoolean()) true else JSONObject.NULL)
            assertThrows(Exception::class.java) { P.bundle(corrupt.toString(), now = 1000000000) }
        }
    }
}
