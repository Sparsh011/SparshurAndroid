package com.sparsh.sparshur.devices

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.sparsh.sparshur.BuildConfig
import com.sparsh.sparshur.lab.network.LabClient
import com.sparsh.sparshur.lab.paired.PairedProtocol
import com.sparsh.sparshur.lab.paired.PairingStore
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import org.json.JSONObject

data class TransferState(
    val device: ConnectedDevice? = null,
    val busy: Boolean = false,
    val title: String = "Transfer interrupted",
    val detail: String = "Tap the device again to start a new clipboard send.",
    val shareDevices: List<ConnectedDevice>? = null,
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
    private val devices = AvailableDevices(application)
    private var envelope: JSONObject? = null
    private var peer: PairedProtocol.Peer? = null
    private var pendingClip: SharedContent? = null
    private var pendingShare: SharedContent.File? = null
    @Volatile private var closed = false

    fun prepare(deviceId: String?) {
        // A second intent cannot turn an in-flight operation into another send.
        if (state.busy) return
        envelope = null
        peer = null
        pendingClip = null
        val device = pairedDevices().firstOrNull { it.id == deviceId }
        waitingForFocus = device != null
        state = if (device == null) TransferState(title = "Device unavailable", detail = "Choose a device again. Nothing was sent.")
            else TransferState(device, busy = true, title = "Getting your clipboard", detail = "Sending to ${device.name}.")
    }

    fun consumeClipboardRequest(): Boolean {
        if (!waitingForFocus) return false
        waitingForFocus = false
        return true
    }

    internal fun prepareShare(intent: Intent) {
        if (state.busy) return
        envelope = null
        peer = null
        pendingShare = null
        state = TransferState()
        pendingShare = try {
            SharedContent.share(intent)
        } catch (rejected: ClipRejected) {
            fail(rejected.title, rejected.detail)
            return
        } catch (_: RuntimeException) {
            fail("File unavailable", "Share one file from the app that can open it.")
            return
        }
        state = TransferState(shareDevices = pairedDevices(), title = "Shared file", detail = "Choose where to put this file.")
    }

    private fun pairedDevices(): List<ConnectedDevice> = runCatching(devices::devices).getOrDefault(emptyList())

    fun sendSharedFile(deviceId: String) {
        val file = pendingShare ?: return
        if (state.busy || state.shareDevices == null) return
        val available = pairedDevices()
        val device = available.firstOrNull { it.id == deviceId }
        if (device == null) {
            state = state.copy(shareDevices = available, detail = "That device is no longer paired. Choose a destination again.")
            return
        }
        state = state.copy(device = device)
        send(file)
    }

    fun copySharedFile() {
        val file = pendingShare ?: return
        if (state.busy || state.shareDevices == null) return
        state = state.copy(busy = true, shareDevices = null, title = "Copying file", detail = "Adding it to this device’s clipboard.")
        executor.execute {
            try {
                SharedFileClipboard.copy(getApplication(), file)
                main.post {
                    if (!closed) state = TransferState(shareDevices = pairedDevices(), title = "Copied on this device",
                        detail = "Paste it into an app that accepts the file.", succeeded = true)
                }
            } catch (rejected: ClipRejected) {
                main.post { if (!closed) fail(rejected.title, rejected.detail) }
            } catch (_: Exception) {
                main.post { if (!closed) fail("Couldn’t copy file", "Share it again from the app that can open it.") }
            }
        }
    }

    fun fail(title: String, detail: String) {
        waitingForFocus = false
        pendingClip = null
        state = state.copy(busy = false, title = title, detail = detail, canRetry = false,
            shareDevices = pendingShare?.let { pairedDevices() })
    }

    internal fun acceptClipboard(content: SharedContent) {
        pendingClip = content
    }
    fun hasPendingClip(): Boolean = pendingClip != null
    fun sendPendingClip() {
        val content = pendingClip ?: return
        pendingClip = null
        send(content)
    }

    private fun send(content: SharedContent) {
        val device = state.device ?: return
        if (!BuildConfig.DEBUG) {
            fail("Debug build required", "The current paired transport is a local learning protocol. Sending is disabled in release builds.")
            return
        }
        state = state.copy(busy = true, shareDevices = null,
            title = if (content is SharedContent.File) "Sending file" else "Sending clipboard",
            detail = "Encrypting and waiting for the Mac’s signed receipt.")
        executor.execute {
            try {
                val current = pairing.peer() ?: error("No paired Mac.")
                check(device.id == "paired:${current.pairingId}") { "Pairing changed." }
                val identity = pairing.identity()
                val message = try {
                    when (content) {
                        is SharedContent.Text -> PairedProtocol.encrypt(current, identity, pairing.reserve(current), content.value)
                        is SharedContent.File -> {
                            val file = content.read(getApplication<Application>().contentResolver)
                            PairedProtocol.encryptPayload(current, identity, pairing.reserve(current), "file", file.encode())
                        }
                    }
                } finally { identity.seed.fill(0) }
                // Only this executor reads/writes the retry envelope, and it is never persisted.
                peer = current
                envelope = message
                deliver(current, message)
            } catch (rejected: ClipRejected) {
                main.post { if (!closed) fail(rejected.title, rejected.detail) }
            } catch (_: FileNotFoundException) {
                main.post { if (!closed) fail("File unavailable", "Share the file from the app that can open it.") }
            } catch (_: SecurityException) {
                main.post { if (!closed) fail("File unavailable", "Share the file from the app that can open it.") }
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
        val isFile = message.getString("message_type") == "file"
        val client = LabClient()
        val duplicate = if (isFile) client.pushFile(target, message) else client.paired(target.endpoint, message, target)
        main.post {
            if (!closed) state = state.copy(busy = false, title = "Received by ${state.device?.name}",
                detail = if (duplicate) "Exact duplicate acknowledged. Mac receipt signature verified."
                    else if (isFile) "Mac receipt signature verified. Save the file from the Mac inbox."
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
                    else "Check the saved pairing and connection details. Nothing was confirmed.", canRetry = retryable,
                shareDevices = pendingShare?.takeUnless { retryable }?.let { pairedDevices() })
        }
    }

    override fun onCleared() {
        closed = true
        pendingClip = null
        pendingShare = null
        // Already-started network work may finish after the screen closes; never reread the clipboard.
        executor.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onCleared()
    }
}
