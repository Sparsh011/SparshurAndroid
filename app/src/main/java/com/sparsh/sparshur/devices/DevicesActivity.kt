package com.sparsh.sparshur.devices

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.Alignment
import com.sparsh.sparshur.ui.*
import com.sparsh.sparshur.R
import com.sparsh.sparshur.MainActivity
import com.sparsh.sparshur.lab.LocalNetworkActivity
import com.sparsh.sparshur.ui.theme.SparshurTheme

/** The explicit destination tap opens a focused activity; focus alone never creates a second send. */
open class DevicesActivity : LocalNetworkActivity() {
    protected open val shareTarget = false
    private lateinit var model: TransferViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[TransferViewModel::class.java]
        if (savedInstanceState == null) prepare(intent)
        onNetworkPermissionDenied = { model.fail("Local network access needed", "Allow local network access in Android Settings, then tap the device again.") }
        setContent {
            SparshurTheme {
                val state = model.state
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp, vertical = 20.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(R.drawable.ic_branch, tint = MaterialTheme.colorScheme.primary)
                            Text("sparshur", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = ::finish) { AppIcon(R.drawable.ic_close, "Close transfer") }
                        }
                        Spacer(Modifier.weight(1f))
                        Panel(Modifier.fillMaxWidth()) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Eyebrow(state.device?.name ?: "Clipboard transfer")
                            Text(state.title, style = MaterialTheme.typography.headlineMedium)
                            SecondaryText(state.detail)
                            state.shareDevices?.let { devices ->
                                ShareFileActions(devices, model::copySharedFile) { id ->
                                    withLocalNetworkPermission { model.sendSharedFile(id) }
                                }
                            }
                            if (state.canRetry) ActionButton("Retry same envelope", { withLocalNetworkPermission(model::retry) }, Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.weight(1f))
                        if (!state.busy) ActionButton("Back to devices", {
                            startActivity(Intent(this@DevicesActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            finish()
                        }, Modifier.fillMaxWidth(), primary = false)
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        prepare(intent)
        if (hasWindowFocus()) readIfRequested()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::model.isInitialized) readIfRequested()
    }
    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) continueTransfer()
    }
    private fun continueTransfer() {
        if (!shareTarget && model.hasPendingClip()) withLocalNetworkPermission(model::sendPendingClip)
    }
    private fun prepare(intent: Intent) {
        if (!shareTarget) {
            model.prepare(intent.getStringExtra(EXTRA_DEVICE_ID))
            return
        }
        model.prepareShare(intent)
    }
    private fun readIfRequested() {
        if (!model.consumeClipboardRequest()) return
        try {
            val content = SharedContent.clipboard(getSystemService(ClipboardManager::class.java).primaryClip)
            model.acceptClipboard(content)
            continueTransfer()
        } catch (rejected: ClipRejected) {
            model.fail(rejected.title, rejected.detail)
        } catch (_: SecurityException) {
            model.fail("Clipboard unavailable", "Return to the home screen and tap the device again.")
        }
    }
    companion object {
        const val EXTRA_DEVICE_ID = "com.sparsh.sparshur.DEVICE_ID"
        fun intent(context: Context, deviceId: String): Intent = Intent(context, DevicesActivity::class.java).apply {
            data = Uri.Builder().scheme("sparshur").authority("devices").appendPath(deviceId).build()
            putExtra(EXTRA_DEVICE_ID, deviceId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }
}

class ShareFileActivity : DevicesActivity() {
    override val shareTarget = true
}

@Composable
internal fun ShareFileActions(devices: List<ConnectedDevice>, onCopy: () -> Unit, onSend: (String) -> Unit) {
    var choosingDevice by rememberSaveable { mutableStateOf(false) }
    ActionButton("Copy on this device", onCopy, Modifier.fillMaxWidth())
    ActionButton("Send to…", { choosingDevice = true }, Modifier.fillMaxWidth(), devices.isNotEmpty(), primary = false)
    if (devices.isEmpty()) SecondaryText("Pair a device in Sparshur to send files to it.")
    if (choosingDevice && devices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { choosingDevice = false },
            title = { Text("Send to device") },
            text = {
                LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices, key = { it.id }) { device ->
                        ActionButton(device.name, {
                            choosingDevice = false
                            onSend(device.id)
                        }, Modifier.fillMaxWidth(), primary = false)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingDevice = false }) { Text("Cancel") } }
        )
    }
}
