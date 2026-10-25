package com.sparsh.sparshur

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sparsh.sparshur.lab.crypto.KeystoreCipher
import com.sparsh.sparshur.lab.network.LabWire
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreLabTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualKeystoreRoundTripSurvivesANewWrapperInstance() {
        val first = KeystoreCipher(context)
        val envelope = first.encrypt("Hello laptop! नमस्ते 🔐")
        assertEquals("Hello laptop! नमस्ते 🔐", KeystoreCipher(context).decrypt(envelope))
        val next = KeystoreCipher(context).encrypt("Hello laptop! नमस्ते 🔐")
        assertFalse(envelope.nonce.contentEquals(next.nonce))
        assertFalse(envelope.ciphertext.contentEquals(next.ciphertext))
        assertTrue(next.header.counter > envelope.header.counter)
        assertEquals(envelope.header.senderId, next.header.senderId)
        assertTrue(first.describeKey().contains("nonexportable"))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(store.getKey("bridge.lab.aes256.v1", null).encoded)
    }

    @Test fun actualKeystoreRejectsTamperedCiphertextAndMetadata() {
        val cipher = KeystoreCipher(context)
        val envelope = cipher.encrypt("invented sample")
        val changed = envelope.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(envelope.copy(ciphertext = changed)) }
        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(envelope.copy(header = envelope.header.copy(counter = envelope.header.counter + 1)))
        }
        assertEquals("invented sample", cipher.decrypt(envelope))
    }

    @Test fun encryptedWireNeverContainsTheReadableSampleOrKey() {
        val sample = "unique-readable-sample-秘密"
        val wire = LabWire.encrypted(KeystoreCipher(context).encrypt(sample))
        assertFalse(wire.contains(sample))
        val body = JSONObject(wire)
        assertEquals("encrypted", body.getString("mode"))
        assertEquals(11, body.length())
        listOf("text", "key", "plaintext", "key_alias").forEach { assertFalse(body.has(it)) }
        val readable = JSONObject(LabWire.plaintext(sample))
        assertEquals(sample, readable.getString("text"))
        assertEquals(4, readable.length())
    }

    @Test fun emptyAndOversizeInputAreRejected() {
        val cipher = KeystoreCipher(context)
        assertThrows(IllegalArgumentException::class.java) { cipher.encrypt("") }
        assertThrows(IllegalArgumentException::class.java) { cipher.encrypt("🔐".repeat(1025)) }
    }
}
