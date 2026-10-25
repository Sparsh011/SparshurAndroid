package com.sparsh.sparshur.network.relay

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.Executors

/** Explicit foreground diagnostic. No payload or secret-bearing state is saved. */
class RelayViewModel(application: Application) : AndroidViewModel(application) {
    var endpoint by mutableStateOf("")
        private set
    var status by mutableStateOf(RelayStatus(RelayPhase.STOPPED))
        private set
    var notice by mutableStateOf("Loading the existing signing identity…")
        private set
    var deviceId by mutableStateOf("")
        private set
    var publicKey by mutableStateOf("")
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var signer: KeystoreRelaySigner? = null
    private var connection: RelayConnection? = null
    @Volatile private var closed = false
    @Volatile private var interactive = false
    @Volatile private var operation = 0L

    init {
        if (Build.VERSION.SDK_INT < 29) {
            notice = "Relay TLS 1.3 needs Android 10 or newer. The existing local labs remain available."
        } else worker.execute {
            try {
                val identity = KeystoreRelaySigner.load(application)
                signer = identity
                main.post { if (!closed) {
                    deviceId = identity.deviceId
                    publicKey = identity.publicKeyBase64
                    notice = "Enroll this public identity on your relay before connecting."
                } }
            } catch (_: Exception) {
                main.post { if (!closed) notice = "A readable existing paired signing identity is required. Pair in the local lab first; no identity was replaced." }
            }
        }
    }

    fun editEndpoint(value: String) {
        if (value.length <= 512 && status.phase in setOf(RelayPhase.STOPPED, RelayPhase.FAILED)) endpoint = value
    }

    fun foreground() { interactive = true }

    fun localNetworkPermission(granted: Boolean) {
        disconnect()
        notice = if (granted) "Local-network access is allowed. You can connect to your configured relay."
            else "Local-network access was denied. Allow it in Android Settings if your relay uses a private network."
    }

    fun connect() {
        if (!interactive || closed || deviceId.isEmpty()) return
        val destination = try { RelayEndpoint.parse(endpoint) } catch (_: Exception) {
            status = RelayStatus(RelayPhase.STOPPED)
            notice = "Use a canonical wss://host[:port]/bridge/v1/ws URL without credentials or query parameters."
            return
        }
        val current = ++operation
        notice = "Only connection authentication is enabled. No clipboard or SMS will be sent."
        status = RelayStatus(RelayPhase.CONNECTING)
        worker.execute {
            connection?.close()
            connection = null
            if (!interactive || closed || operation != current) return@execute
            val identity = signer ?: return@execute
            val next = RelayConnection(destination, identity) { update ->
                main.post { if (!closed && interactive && operation == current) status = update }
            }
            connection = next
            // A stop racing creation is caught before any connection starts.
            if (interactive && !closed && operation == current) next.connect() else next.close()
        }
    }

    fun disconnect() {
        operation++
        status = RelayStatus(RelayPhase.STOPPED)
        if (!closed) worker.execute { connection?.close(); connection = null }
    }

    fun background() { interactive = false; disconnect() }

    override fun onCleared() {
        interactive = false
        closed = true
        operation++
        worker.execute { connection?.close(); connection = null; signer = null }
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onCleared()
    }
}
