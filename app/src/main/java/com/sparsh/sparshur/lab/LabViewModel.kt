package com.sparsh.sparshur.lab

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.sparsh.sparshur.lab.crypto.KeystoreCipher
import com.sparsh.sparshur.lab.crypto.LabEnvelope
import com.sparsh.sparshur.lab.network.LabClient
import com.sparsh.sparshur.lab.network.LabWire
import com.sparsh.sparshur.lab.network.LocalEndpoint
import com.sparsh.sparshur.lab.paired.PairedProtocol
import com.sparsh.sparshur.lab.paired.PairingStore
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException
import org.json.JSONObject

data class LabState(
    val pairedMode: Boolean = true,
    val peer: PairedProtocol.Peer? = null,
    val pendingPair: PairedProtocol.Bundle? = null,
    val showPairingSetup: Boolean = false,
    val bundleInput: String = "",
    val pairedWire: String = "",
    val step: Int = 0,
    val text: String = "Hello laptop! This is a made-up learning message.",
    val address: String = "",
    val port: String = "8787",
    val keyDescription: String = "Inspecting Android Keystore…",
    val envelope: LabEnvelope? = null,
    val wire: String = "",
    val result: String = "",
    val busy: String? = null,
    val error: String? = null
)

/** Retains the in-memory lesson across rotation; never writes messages to disk. */
class LabViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(LabState())
        private set

    private val crypto = KeystoreCipher(application)
    private val client = LabClient()
    private val pairing = PairingStore(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var closed = false

    init {
        inspectKey()
    }

    fun step(value: Int) {
        state = state.copy(step = value, error = null, result = "")
    }
    fun mode(paired: Boolean) {
        state = state.copy(pairedMode = paired, error = null, result = "")
    }
    fun bundleInput(value: String) {
        if (value.length <= 2500) state = state.copy(bundleInput = value)
    }
    fun scannerUnavailable() {
        state =
            state.copy(
                showPairingSetup = true,
                error = "QR scanner unavailable. Google Play services may need to download its scanner module. " +
                    "Use the trusted manual bundle fallback."
            )
    }
    fun showPairingSetup() {
        state = state.copy(showPairingSetup = true)
    }
    fun importBundle(value: String = state.bundleInput) {
        task("Reading the trusted QR bundle…", { PairedProtocol.bundle(value) }) {
            state.pendingPair?.secret?.fill(0)
            state =
                state.copy(
                    pendingPair = it,
                    bundleInput = "",
                    result = "QR loaded. Compare the public-key fingerprint with the Mac, then pair."
                )
        }
    }
    fun pairMac() {
        val bundle = state.pendingPair ?: return
        task("Proving pairing-secret and signing-key possession…", {
            check(bundle.expires > System.currentTimeMillis() / 1000) { "Pairing expired. Generate a new QR on Mac." }
            val identity = pairing.identity()
            try {
                val response = client.pair(
                    LocalEndpoint(bundle.peer.host, bundle.peer.port),
                    PairedProtocol.pairRequest(bundle, identity)
                )
                PairedProtocol.confirm(bundle, identity, response)
                pairing.savePeer(bundle.peer)
                bundle.peer
            } finally {
                identity.seed.fill(0)
            }
        }) {
            bundle.secret.fill(0)
            state = state.copy(
                peer = it,
                pendingPair = null,
                showPairingSetup = false,
                pairedWire = "",
                address = it.host,
                port = it.port.toString(),
                result = "Paired. Mac keys are pinned, and Mac has your signing public key. " +
                    "No private keys crossed the network."
            )
        }
    }
    fun encryptForMac() {
        val peer = state.peer ?: return
        val sample = state.text
        task("Encrypting payload, wrapping its key, and signing…", {
            val identity = pairing.identity()
            try {
                PairedProtocol.encrypt(peer, identity, pairing.reserve(peer), sample).toString(2)
            } finally {
                identity.seed.fill(0)
            }
        }) {
            state =
                state.copy(
                    pairedWire = it,
                    result = "Encrypted for the paired Mac. Fresh content key hidden; " +
                        "only ciphertext, wrapped key and signature travel."
                )
        }
    }
    fun sendToMac(tamper: Boolean = false) {
        val peer = state.peer ?: return
        if (state.pairedWire.isEmpty()) return
        val wire = state.pairedWire
        networkTask(
            if (tamper) "Sending an intentionally altered COPY…" else "Waiting for the Mac's signed receipt…"
        ) { endpoint ->
            val message = JSONObject(wire)
            if (tamper) {
                val changed = PairedProtocol.bytes(message.getString("ciphertext"), 17..4112)
                val before = changed[0].toInt() and 255
                changed[0] = (changed[0].toInt() xor 1).toByte()
                message.put("ciphertext", PairedProtocol.b64(changed))
                try {
                    client.paired(endpoint, message, peer)
                    error("Unexpected: altered message accepted.")
                } catch (e: IllegalStateException) {
                    check(e.message?.startsWith("Mac rejected the request") == true) {
                        "No verified rejection received. Check connectivity and retry the original envelope."
                    }
                    "Mac rejected the modified copy. Ciphertext byte 0: 0x%02X → 0x%02X. Original envelope unchanged."
                        .format(before, changed[0].toInt() and 255)
                }
            } else {
                if (client.paired(endpoint, message, peer)) {
                    "Mac verified this exact duplicate and did not display it twice. Signed receipt verified here."
                } else {
                    "Mac verified your signature, unwrapped the content key, verified the tag and decrypted. Mac receipt signature verified here."
                }
            }
        }
    }
    fun text(value: String) {
        state = state.copy(text = value, error = null)
    }
    fun address(value: String) {
        state = state.copy(address = value.trim(), error = null)
    }
    fun port(value: String) {
        state = state.copy(port = value, error = null)
    }
    fun saveConnection() {
        val peer = state.peer ?: return
        val address = state.address
        val port = state.port.toIntOrNull() ?: 0
        task("Saving the connection…", {
            val endpoint = LocalEndpoint(address, port)
            check(pairing.peer()?.pairingId == peer.pairingId) { "Pairing changed. Reopen connection settings." }
            peer.copy(host = endpoint.address, port = endpoint.port).also(pairing::savePeer)
        }) { state = state.copy(peer = it, result = "Connection saved.") }
    }
    fun refreshSavedPeer() {
        executor.execute {
            try {
                val saved = pairing.peer()
                main.post {
                    if (!closed && state.busy == null && state.pendingPair == null && state.peer?.pairingId != saved?.pairingId) {
                        state = state.copy(peer = saved, pairedWire = "", address = saved?.host ?: "", port = saved?.port?.toString() ?: "8787")
                    }
                }
            } catch (_: Exception) {
                main.post { if (!closed) state = state.copy(error = "Could not reload the saved pairing.") }
            }
        }
    }
    fun permissionDenied() {
        state =
            state.copy(
                error = "Allow Nearby devices / local network access to reach the Mac. " +
                    "You can retry or enable it in Android Settings."
            )
    }

    fun inspectKey() = task("Inspecting the key…", { crypto.describeKey() to pairing.peer() }) {
        state =
            state.copy(
                keyDescription = it.first,
                peer = it.second,
                address = state.address.ifEmpty { it.second?.host ?: "" },
                port = it.second?.port?.toString() ?: state.port,
                result = if (state.pairedMode) {
                    "Pair with your Mac to let it decrypt your messages. Saved pairing survives app restarts."
                } else {
                    "The same saved local AES key is reused after app restarts. " +
                        "Its secret bytes are never displayed or exported."
                }
            )
    }

    fun encrypt() {
        val text = state.text
        task("Encrypting on this phone…", { crypto.encrypt(text) }) {
            state =
                state.copy(
                    envelope = it,
                    wire = LabWire.encrypted(it),
                    step = 2,
                    result = "Encrypted locally. Inspect the envelope or try changing a byte below."
                )
        }
    }

    fun decrypt() {
        val envelope = state.envelope ?: return
        task("Verifying tag and decrypting…", { crypto.decrypt(envelope) }) {
            state = state.copy(result = "Verified and decrypted on this phone:\n$it")
        }
    }

    fun tamper() {
        val envelope = state.envelope ?: return
        task("Changing one byte in a COPY…", {
            val changed = envelope.ciphertext.copyOf()
            val before = changed[0].toInt() and 255
            changed[0] = (changed[0].toInt() xor 1).toByte()
            try {
                crypto.decrypt(envelope.copy(ciphertext = changed))
                error("Unexpected result: the modified message was accepted.")
            } catch (_: AEADBadTagException) {
                "Tampering rejected. One changed byte caused authentication to fail. " +
                    "Byte 0: 0x%02X → 0x%02X. No altered text was returned. Original envelope unchanged."
                        .format(before, changed[0].toInt() and 255)
            }
        }) { state = state.copy(result = it) }
    }

    fun connect() = networkTask("Checking the local receiver…") { endpoint -> client.check(endpoint) }

    fun sendEncrypted() {
        val body = state.wire.takeIf { state.envelope != null } ?: return
        networkTask("Sending encrypted bytes…") { endpoint -> client.send(endpoint, body) }
    }

    fun sendPlaintext() {
        val text = state.text
        networkTask("Sending readable sample text…") { endpoint ->
            client.send(endpoint, LabWire.plaintext(text))
        }
    }

    private fun networkTask(label: String, operation: (LocalEndpoint) -> String) {
        val address = state.address
        val port = state.port
        task(label, {
            val endpoint = LocalEndpoint(address, port.toIntOrNull() ?: 0)
            operation(endpoint)
        }) { state = state.copy(result = it) }
    }

    private fun <T> task(label: String, work: () -> T, success: (T) -> Unit) {
        if (state.busy != null) return
        state = state.copy(busy = label, error = null, result = "")
        executor.execute {
            try {
                val result = work()
                main.post {
                    if (!closed) {
                        state = state.copy(busy = null)
                        success(result)
                    }
                }
            } catch (exception: Exception) {
                val explanation = when (exception) {
                    is SocketTimeoutException ->
                        "The Mac did not reply in time. Check its IPv4 address, hotspot connection and firewall."
                    is ConnectException ->
                        "Could not connect. Start the receiver, use the Mac's IPv4 address and port 8787, " +
                            "and allow the local firewall prompt."
                    is AEADBadTagException ->
                        "Authentication failed. The key, nonce, ciphertext or associated data does not match."
                    is IllegalArgumentException, is IllegalStateException ->
                        exception.message
                            ?: "The lab input was not valid."
                    is java.io.IOException ->
                        "Network request failed. Check the shared network, receiver and local network permissions."
                    else ->
                        "The operation failed. The Keystore key may be unavailable; " +
                            "no key was exported or silently replaced."
                }
                main.post { if (!closed) state = state.copy(busy = null, error = explanation) }
            }
        }
    }

    override fun onCleared() {
        closed = true
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onCleared()
    }
}
