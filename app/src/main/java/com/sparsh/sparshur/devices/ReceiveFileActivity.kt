package com.sparsh.sparshur.devices

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sparsh.sparshur.lab.LocalNetworkActivity
import com.sparsh.sparshur.lab.network.LabClient
import com.sparsh.sparshur.lab.paired.FilePull
import com.sparsh.sparshur.lab.paired.PairedFile
import com.sparsh.sparshur.lab.paired.PairedProtocol
import com.sparsh.sparshur.lab.paired.PairingStore
import com.sparsh.sparshur.ui.ActionButton
import com.sparsh.sparshur.ui.PageHeader
import com.sparsh.sparshur.ui.theme.SparshurTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReceiveFileActivity : LocalNetworkActivity() {
    private lateinit var model: ReceiveFileViewModel
    private val saveDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(model::save)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[ReceiveFileViewModel::class.java]
        onNetworkPermissionDenied = model::permissionDenied
        setContent {
            SparshurTheme {
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PageHeader("Paired Mac", "Receive file", "Queue a file in Sparshur on your Mac, then check for it here.")
                        Text(model.status)
                        if (model.busy) CircularProgressIndicator()
                        else when {
                            model.awaitingReceipt -> ActionButton("Retry delivery confirmation", { withLocalNetworkPermission(model::acknowledge) }, Modifier.fillMaxWidth())
                            model.fileName != null -> ActionButton("Save file…", {
                                saveDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType(model.mime).putExtra(Intent.EXTRA_TITLE, model.fileName))
                            }, Modifier.fillMaxWidth())
                            else -> ActionButton("Check for file", { withLocalNetworkPermission(model::receive) }, Modifier.fillMaxWidth())
                        }
                        ActionButton("Back to devices", ::finish, Modifier.fillMaxWidth(), primary = false)
                    }
                }
            }
        }
    }
}

class ReceiveFileViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PairingStore(application)
    private val client = LabClient()
    private data class Incoming(val peer: PairedProtocol.Peer, val id: String, val file: PairedFile)
    private var incoming by mutableStateOf<Incoming?>(null)
    private var savedFile by mutableStateOf<Pair<PairedProtocol.Peer, String>?>(null)
    val fileName: String? get() = incoming?.file?.name
    val mime: String? get() = incoming?.file?.mime
    val awaitingReceipt: Boolean get() = savedFile != null
    var status by mutableStateOf("Ready to check your paired Mac.")
        private set
    var busy by mutableStateOf(false)
        private set

    fun permissionDenied() { status = "Allow local network access to reach your Mac." }

    fun receive() = task("Checking your paired Mac…", "Couldn’t check for a file. Make sure the Mac is reachable and running a file-capable build.") {
        val result = withContext(Dispatchers.IO) {
            val peer = checkNotNull(store.peer())
            FilePull().use { pull ->
                val identity = store.identity()
                val request = try { pull.request(peer, identity, store.reserve(peer)) } finally { identity.seed.fill(0) }
                pull.open(peer, request, client.pullFile(peer, request))?.let { (id, file) -> Incoming(peer, id, file) }
            }
        }
        incoming = result
        status = result?.let { "Verified ${it.file.name}. Choose where to save it." } ?: "No file is waiting on your Mac."
    }

    fun save(destination: Uri) {
        val item = incoming ?: return
        task("Saving ${item.file.name}…", "Couldn’t save the file. Choose another location.") {
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                checkNotNull(resolver.openOutputStream(destination, "wt")).use { it.write(item.file.data) }
            }
            savedFile = item.peer to item.id
            incoming = null
            acknowledgeSaved()
        }
    }

    fun acknowledge() = task("Confirming delivery…", "File saved. Confirm delivery when your Mac is reachable.") { acknowledgeSaved() }

    private suspend fun acknowledgeSaved() {
        val (peer, id) = checkNotNull(savedFile)
        try {
            withContext(Dispatchers.IO) {
                val identity = store.identity()
                val receipt = try {
                    PairedProtocol.encryptPayload(peer, identity, store.reserve(peer), "file_ack", id.toByteArray())
                } finally { identity.seed.fill(0) }
                client.acknowledgeFile(peer, receipt)
            }
            savedFile = null
            status = "File saved and delivery confirmed to your Mac."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "File saved. Confirm delivery when your Mac is reachable."
        }
    }

    private fun task(progress: String, failure: String, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = progress
        viewModelScope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = failure }
            finally { busy = false }
        }
    }
}
