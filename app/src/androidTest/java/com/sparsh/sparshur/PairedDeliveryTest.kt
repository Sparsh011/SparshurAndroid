package com.sparsh.sparshur

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.subtle.Ed25519Sign
import com.sparsh.sparshur.lab.network.LabClient
import com.sparsh.sparshur.lab.network.LocalEndpoint
import com.sparsh.sparshur.lab.paired.PairedProtocol as P
import com.sparsh.sparshur.lab.paired.PairingStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PairedDeliveryTest {
    @Test fun retriesSavedEnvelopeAgainstNativeRestart() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativeReplayOnly") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "bridge-paired-retry.json")
        assumeTrue(file.exists())
        val peer = PairingStore(context).peer()!!
        assertTrue(LabClient().paired(LocalEndpoint(peer.host, peer.port), JSONObject(file.readText()), peer))
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
            val endpoint = LocalEndpoint(bundle.peer.host, bundle.peer.port)
            val client = LabClient()
            val request = P.pairRequest(bundle, identity)
            P.confirm(bundle, identity, client.pair(endpoint, request))
            // Lost-receipt pairing retry must be idempotent.
            P.confirm(bundle, identity, client.pair(endpoint, request))
            store.savePeer(bundle.peer)
            val peer = PairingStore(context).peer()!!
            assertEquals(P.fingerprint(bundle.peer), P.fingerprint(peer))
            val sample = "Paired hello from Android → Mac! This is invented text. 🔐"
            val original = P.encrypt(peer, identity, store.reserve(peer), sample)
            assertFalse(original.toString().contains(sample))
            assertFalse(client.paired(endpoint, original, peer))
            assertTrue(client.paired(endpoint, original, peer))
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
