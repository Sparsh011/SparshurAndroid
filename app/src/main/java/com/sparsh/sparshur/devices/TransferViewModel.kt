package com.sparsh.sparshur.devices

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.sparsh.sparshur.BuildConfig
import com.sparsh.sparshur.lab.network.LabClient
import com.sparsh.sparshur.lab.network.LocalEndpoint
import com.sparsh.sparshur.lab.paired.PairedProtocol
import com.sparsh.sparshur.lab.paired.PairingStore
import java.util.concurrent.Executors
import org.json.JSONObject

data class TransferState(
    val device: ConnectedDevice? = null,
    val busy: Boolean = false,
    val title: String = "Transfer interrupted",
    val detail: String = "Tap the device again to start a new clipboard send.",
    val canRetry: Boolean = false,
    val succeeded: Boolean = false
)

/** Clipboard text never enters saved state, logs or the database. Retries retain ciphertext only. */
class TransferViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(TransferState())
        private set
    private var waitingForFocus = false
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pairing = PairingStore(application)
    private var envelope: JSONObject? = null
    private var peer: PairedProtocol.Peer? = null
    private var pendingClip: String? = null
    @Volatile private var closed = false

    fun prepare(deviceId: String?) {
        // A second intent cannot turn an in-flight operation into another send.
        if (state.busy) return
        envelope = null
        peer = null
        pendingClip = null
        val device = runCatching { AvailableDevices(getApplication()).devices().firstOrNull { it.id == deviceId } }.getOrNull()
        waitingForFocus = device != null
        state = if (device == null) TransferState(title = "Device unavailable", detail = "Choose a device again. Nothing was sent.")
            else TransferState(device, busy = true, title = "Getting your clipboard", detail = "Sending to ${device.name}.")
    }

    fun consumeClipboardRequest(): Boolean {
        if (!waitingForFocus) return false
        waitingForFocus = false
        return true
    }

    fun fail(title: String, detail: String) {
        waitingForFocus = false
        pendingClip = null
        state = state.copy(busy = false, title = title, detail = detail, canRetry = false)
    }

    fun acceptClipboard(text: String) {
        pendingClip = text
    }
    fun hasPendingClip(): Boolean = pendingClip != null
    fun sendPendingClip() {
        val text = pendingClip ?: return
        pendingClip = null
        send(text)
    }

    fun send(text: String) {
        val device = state.device ?: return
        if (!BuildConfig.DEBUG) {
            fail("Debug build required", "The current paired transport is a local learning protocol. Sending is disabled in release builds.")
            return
        }
        state = state.copy(title = "Sending clipboard", detail = "Encrypting and waiting for the Mac’s signed receipt.")
        executor.execute {
            try {
                val current = pairing.peer() ?: error("No paired Mac.")
                check(device.id == "paired:${current.pairingId}") { "Pairing changed." }
                val identity = pairing.identity()
                val message = try {
                    PairedProtocol.encrypt(current, identity, pairing.reserve(current), text)
                } finally { identity.seed.fill(0) }
                // Only this executor reads/writes the retry envelope, and it is never persisted.
                peer = current
                envelope = message
                deliver(current, message)
            } catch (_: Exception) {
                reportFailure()
            }
        }
    }

    fun retry() {
        if (!state.canRetry || state.busy) return
        state = state.copy(busy = true, title = "Retrying the same envelope", canRetry = false)
        executor.execute {
            try {
                val oldPeer = checkNotNull(peer)
                check(pairing.peer()?.pairingId == oldPeer.pairingId)
                deliver(oldPeer, checkNotNull(envelope))
            } catch (_: Exception) { reportFailure() }
        }
    }

    private fun deliver(target: PairedProtocol.Peer, message: JSONObject) {
        val duplicate = LabClient().paired(LocalEndpoint(target.host, target.port), message, target)
        main.post {
            if (!closed) state = state.copy(busy = false, title = "Received by ${state.device?.name}",
                detail = if (duplicate) "Exact duplicate acknowledged. Mac receipt signature verified."
                    else "Mac receipt signature verified. Your clip arrived.", canRetry = false, succeeded = true)
        }
        envelope = null
        peer = null
    }

    private fun reportFailure() {
        val retryable = envelope != null && peer != null
        main.post {
            if (!closed) state = state.copy(busy = false, title = "Couldn’t confirm delivery",
                detail = if (retryable) "The Mac may have received it. Check its connection and retry the same envelope."
                    else "Check the saved pairing and connection details. Nothing was confirmed.", canRetry = retryable)
        }
    }

    override fun onCleared() {
        closed = true
        pendingClip = null
        // Already-started network work may finish after the screen closes; never reread the clipboard.
        executor.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onCleared()
    }
}
