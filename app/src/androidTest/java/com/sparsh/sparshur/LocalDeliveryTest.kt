package com.sparsh.sparshur

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.sparsh.sparshur.lab.crypto.KeystoreCipher
import com.sparsh.sparshur.lab.network.LabClient
import com.sparsh.sparshur.lab.network.LabWire
import com.sparsh.sparshur.lab.network.LocalEndpoint
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in integration test: start the observer and supply receiverHost. */
class LocalDeliveryTest {
    @Test fun androidSendsBothFormsAndReceiverDeduplicates() {
        val host = InstrumentationRegistry.getArguments().getString("receiverHost")
        assumeNotNull(host)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 37) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                "android.permission.ACCESS_LOCAL_NETWORK"
            )
        }
        val endpoint = LocalEndpoint(host!!, 8787)
        val client = LabClient()
        assertTrue(client.check(endpoint).contains("reachable"))
        val sample = "Hello from the Android Keystore lab! This is invented test text."
        val envelope = KeystoreCipher(instrumentation.targetContext).encrypt(sample)
        val wire = LabWire.encrypted(envelope)
        assertTrue(client.send(endpoint, wire).contains("received"))
        assertTrue(client.send(endpoint, wire).contains("duplicate"))
        assertTrue(client.send(endpoint, LabWire.plaintext(sample)).contains("received"))
    }
}
