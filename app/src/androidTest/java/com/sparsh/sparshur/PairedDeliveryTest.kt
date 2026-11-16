package com.sparsh.sparshur

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.subtle.Ed25519Sign
import com.sparsh.sparshur.lab.network.LabClient
import com.sparsh.sparshur.lab.paired.FilePull
import com.sparsh.sparshur.lab.paired.PairedFile
import com.sparsh.sparshur.lab.paired.PairedProtocol as P
import com.sparsh.sparshur.lab.paired.PairingStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PairedDeliveryTest {
    @Test fun exchangesFilesWithThePairedMacAndAcknowledgesDelivery() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use an isolated receiver with bridge-file.txt already queued", args.getString("pairedFiles") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PairingStore(context)
        val peer = checkNotNull(store.peer())
        val identity = store.identity()
        val client = LabClient()
        try {
            val upload = PairedFile("phone-files.bin", "application/octet-stream", ByteArray(64 * 1024) { it.toByte() })
            val message = P.encryptPayload(peer, identity, store.reserve(peer), "file", upload.encode())
            assertFalse(client.pushFile(peer, message))
            assertTrue(client.pushFile(peer, message))
            FilePull().use { pull ->
                val request = pull.request(peer, identity, store.reserve(peer))
                val response = client.pullFile(peer, request)
                val (id, file) = checkNotNull(pull.open(peer, request, response))
                assertEquals("bridge-file.txt", file.name)
                assertArrayEquals("Mac file test".toByteArray(), file.data)
                val wrongId = JSONObject(response.toString()).put("file_id", "00000000-0000-4000-8000-000000000001")
                assertThrows(java.security.GeneralSecurityException::class.java) { pull.open(peer, request, wrongId) }
                val receipt = P.encryptPayload(peer, identity, store.reserve(peer), "file_ack", id.toByteArray())
                client.acknowledgeFile(peer, receipt)
                client.acknowledgeFile(peer, receipt)
            }
            FilePull().use { pull ->
                val request = pull.request(peer, identity, store.reserve(peer))
                assertNull(pull.open(peer, request, client.pullFile(peer, request)))
            }
        } finally { identity.seed.fill(0) }
    }

    @Test fun retriesSavedEnvelopeAgainstNativeRestart() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativeReplayOnly") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "bridge-paired-retry.json")
        assumeTrue(file.exists())
        val peer = PairingStore(context).peer()!!
        assertTrue(LabClient().paired(peer.endpoint, JSONObject(file.readText()), peer))
    }

    @Test fun pairsEncryptsAndVerifiesRealMacDecryption() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Test setup transfers this secret via trusted adb stdin, not via the LAN.
        val file = File(context.cacheDir, "bridge-pairing-test.json")
        assumeTrue("Provide a fresh Mac bundle via trusted test setup", file.exists())
        if (Build.VERSION.SDK_INT >= 37) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                "android.permission.ACCESS_LOCAL_NETWORK"
            )
        }
        val bundle = P.bundle(file.readText())
        file.delete()
        val store = PairingStore(context)
        val identity = store.identity()
        try {
            val endpoint = bundle.peer.endpoint
            val client = LabClient()
            val request = P.pairRequest(bundle, identity)
            P.confirm(bundle, identity, client.pair(endpoint, request))
            // Lost-receipt pairing retry must be idempotent.
            P.confirm(bundle, identity, client.pair(endpoint, request))
            store.savePeer(bundle.peer)
            val peer = PairingStore(context).peer()!!
            assertEquals(P.fingerprint(bundle.peer), P.fingerprint(peer))
            assertEquals(endpoint, peer.endpoint)
            val sample = "Paired hello from Android → Mac! This is invented text. 🔐"
            val original = P.encrypt(peer, identity, store.reserve(peer), sample)
            assertFalse(original.toString().contains(sample))
            assertFalse(client.paired(peer.endpoint, original, peer))
            assertTrue(client.paired(peer.endpoint, original, peer))
            val next = P.encrypt(
                peer,
                identity,
                store.reserve(peer),
                "The Mac verified my sender signature and decrypted this sample."
            )
            val changed = JSONObject(next.toString())
            val cipher = P.bytes(changed.getString("ciphertext"), 17..4112)
            cipher[0] = (cipher[0].toInt() xor 1).toByte()
            changed.put("ciphertext", P.b64(cipher))
            assertThrows(IllegalStateException::class.java) { client.paired(endpoint, changed, peer) }
            // Even a valid signature must not bypass a bad payload authentication tag.
            changed.put("signature", P.b64(Ed25519Sign(identity.seed).sign(P.signedMessage(changed).toByteArray())))
            assertThrows(IllegalStateException::class.java) { client.paired(endpoint, changed, peer) }
            assertFalse(client.paired(endpoint, next, peer))
            // Public ciphertext only, for the host's fresh-process replay test; removed afterward.
            File(context.cacheDir, "bridge-paired-retry.json").writeText(next.toString())
        } finally {
            identity.seed.fill(0)
            bundle.secret.fill(0)
        }
    }
}
