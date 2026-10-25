package com.sparsh.sparshur.lab

import com.sparsh.sparshur.lab.crypto.LabHeader
import com.sparsh.sparshur.lab.network.LocalEndpoint
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoContractTest {
    private val sender = "11111111-1111-4111-8111-111111111111"
    private val message = "22222222-2222-4222-8222-222222222222"
    private val header = LabHeader(sender, message, 1)

    @Test fun aes256GcmMatchesNistVector() {
        val encrypted = cipher(Cipher.ENCRYPT_MODE, ByteArray(32), ByteArray(12), byteArrayOf())
            .doFinal(ByteArray(16))
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919", encrypted.hex())
        val resources = javaClass.classLoader!!.getResourceAsStream("test-vectors.json")!!.bufferedReader().use {
            it.readText()
        }
        org.junit.Assert.assertTrue(resources.contains(encrypted.hex()))
    }

    @Test fun headerHasAnExactStableEncoding() {
        assertEquals("bridge-lab|1|$sender|laptop-observer|text|1|$message", String(header.associatedData()))
        assertThrows(IllegalArgumentException::class.java) { LabHeader(sender, message, 0) }
        assertThrows(IllegalArgumentException::class.java) { LabHeader(sender, message, 10001) }
        assertThrows(IllegalArgumentException::class.java) { LabHeader("x|y", message, 1) }
        assertThrows(IllegalArgumentException::class.java) { LabHeader(sender, message, 1, version = 2) }
    }

    @Test fun sharedLabVectorMatchesIndependentPythonEncryption() {
        val vector = javaClass.classLoader!!.getResourceAsStream("test-vectors.json")!!
            .bufferedReader().use { it.readText() }
        val encodedCiphertext = Regex(""""ciphertext": "([^"]+)"""")
            .find(vector)!!.groupValues[1]
        val encrypted = cipher(
            Cipher.ENCRYPT_MODE,
            ByteArray(32) { it.toByte() },
            ByteArray(12) { it.toByte() },
            header.associatedData()
        ).doFinal("Hello laptop!".toByteArray())
        assertArrayEquals(java.util.Base64.getDecoder().decode(encodedCiphertext), encrypted)
    }

    @Test fun changesToEveryAuthenticatedFieldAreRejected() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { it.toByte() }
        val bytes = "Hello laptop!".toByteArray()
        val encrypted = cipher(Cipher.ENCRYPT_MODE, key, nonce, header.associatedData()).doFinal(bytes)
        assertArrayEquals(bytes, cipher(Cipher.DECRYPT_MODE, key, nonce, header.associatedData()).doFinal(encrypted))
        val alternatives = listOf(
            header.copy(senderId = "33333333-3333-4333-8333-333333333333").associatedData(),
            header.copy(messageId = "44444444-4444-4444-8444-444444444444").associatedData(),
            header.copy(counter = 2).associatedData(),
            String(header.associatedData()).replace("laptop-observer", "another-recipient").toByteArray(),
            String(header.associatedData()).replace("|text|", "|file|").toByteArray(),
            String(header.associatedData()).replace("|1|", "|2|").toByteArray()
        )
        alternatives.forEach { aad ->
            assertThrows(AEADBadTagException::class.java) {
                cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(encrypted)
            }
        }
        listOf(0, encrypted.lastIndex).forEach { index ->
            val altered = encrypted.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows(AEADBadTagException::class.java) {
                cipher(Cipher.DECRYPT_MODE, key, nonce, header.associatedData()).doFinal(altered)
            }
        }
        assertThrows(AEADBadTagException::class.java) {
            cipher(Cipher.DECRYPT_MODE, ByteArray(32) { 7 }, nonce, header.associatedData()).doFinal(encrypted)
        }
        assertThrows(AEADBadTagException::class.java) {
            cipher(Cipher.DECRYPT_MODE, key, ByteArray(12) { 9 }, header.associatedData()).doFinal(encrypted)
        }
    }

    @Test fun onlyExplicitLocalEndpointsAreAccepted() {
        listOf("10.0.2.2", "172.20.10.2", "192.168.43.55", "127.0.0.1").forEach {
            assertEquals("http://$it:8787", LocalEndpoint(it, 8787).url)
        }
        listOf(
            "8.8.8.8", "example.com", "http://192.168.1.1", "0.0.0.0", "192.168.1.256",
            "192.168.1.01", "127.1", "192.168.1.1/path", "localhost", "192.168.1.1@8.8.8.8"
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalEndpoint(it, 8787) }
        }
        assertThrows(IllegalArgumentException::class.java) { LocalEndpoint("127.0.0.1", 80) }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
