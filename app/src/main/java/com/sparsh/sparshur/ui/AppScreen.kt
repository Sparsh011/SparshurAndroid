package com.sparsh.sparshur.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparsh.sparshur.BuildConfig
import com.sparsh.sparshur.R
import com.sparsh.sparshur.devices.*
import com.sparsh.sparshur.lab.LabViewModel
import com.sparsh.sparshur.lab.paired.PairedProtocol
import com.sparsh.sparshur.ui.learn.LearnScreen
import com.sparsh.sparshur.widget.DevicesWidgetProvider

@Composable
fun AppScreen(devices: DevicesViewModel, pairing: LabViewModel, network: (() -> Unit) -> Unit,
    send: (String) -> Unit, scan: () -> Unit, experiments: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Devices") }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var smsDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var lesson by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }
    fun navigate(action: () -> Unit) {
        if (detail == "sms" && devices.hasUnsavedPolicy()) pendingNavigation = action else action()
    }
    fun back() = navigate { when { detail != null -> detail = null; lesson != null -> lesson = null; else -> tab = "Devices" } }
    LaunchedEffect(tab, detail, lesson) { scroll.scrollTo(0) }
    LaunchedEffect(pairing.state.peer?.pairingId) { devices.refresh(); DevicesWidgetProvider.updateAll(context) }
    LaunchedEffect(detail, smsDeviceId, devices.devices) {
        if (detail == "sms" && devices.selected?.id != smsDeviceId && devices.devices.any { it.id == smsDeviceId }) devices.open(smsDeviceId)
    }
    LaunchedEffect(devices.notice) {
        val message = devices.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        if (devices.notice == message) devices.clearNotice()
    }
    BackHandler(detail != null || lesson != null || tab != "Devices", ::back)
    if (pendingNavigation != null) AlertDialog(
        onDismissRequest = { pendingNavigation = null }, title = { Text("Discard changes?") },
        text = { Text("Your SMS rules haven’t been saved.") },
        confirmButton = { QuietButton("Discard", { val action = pendingNavigation; pendingNavigation = null; devices.open(null); action?.invoke() }) },
        dismissButton = { QuietButton("Keep editing", { pendingNavigation = null }) }, shape = RoundedCornerShape(14.dp))
    fun addWidget() {
        val manager = context.getSystemService(AppWidgetManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported) {
            val requested = manager.requestPinAppWidget(ComponentName(context, DevicesWidgetProvider::class.java), null, null)
            devices.notice(if (requested) "Confirm the widget in your launcher." else "Long-press your home screen and choose Widgets → Sparshur.")
        } else devices.notice("Long-press your home screen and choose Widgets → Sparshur.")
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (detail != null || (tab == "Learn" && lesson != null)) {
                    IconButton(onClick = ::back) { AppIcon(R.drawable.ic_back, "Back") }
                    Text(when (detail) { "sms" -> "SMS sharing"; "pair" -> "Pair a device"; "widget" -> "Home-screen widget"; else -> "Learn" },
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                } else {
                    AppIcon(R.drawable.ic_branch, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Text("sparshur", Modifier.padding(start = 10.dp).weight(1f), fontSize = 23.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp)
                    IconButton(onClick = { navigate { detail = "widget" } }) { AppIcon(R.drawable.ic_widget, "Home-screen widget") }
                }
            }
        },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                if (detail == "sms" && devices.selected?.id == smsDeviceId) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ActionButton(if (devices.savingPolicy) "Saving…" else "Save rules", devices::savePolicy,
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), enabled = !devices.savingPolicy)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    listOf("Devices" to R.drawable.ic_devices, "Learn" to R.drawable.ic_learn, "Settings" to R.drawable.ic_settings).forEach { (label, icon) ->
                        val active = tab == label && detail == null
                        val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(Modifier.weight(1f).clickable(role = Role.Tab) { navigate { tab = label; detail = null } }
                            .padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AppIcon(icon, label, color)
                            Text(label, fontSize = 10.sp, lineHeight = 14.sp, color = color)
                        }
                    }
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(scroll).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (detail) {
                "sms" -> if (devices.selected?.id == smsDeviceId) SmsFilterScreen(devices)
                    else SecondaryText(if (devices.loading) "Loading device…" else "This device is no longer paired. Choose a device from Devices.")
                "pair" -> PairDeviceScreen(pairing, network, scan)
                "widget" -> {
                    PageHeader("From your home screen", "One tap.\nAcross devices.", "Send your clipboard straight to your Mac.")
                    Panel(Modifier.fillMaxWidth()) {
                        Text("sparshur", style = MaterialTheme.typography.titleMedium)
                        if (devices.devices.isEmpty()) SecondaryText("Pair your Mac to add it here.")
                        devices.devices.sortedByDescending { it.id == devices.primaryId }.forEach { device ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(R.drawable.ic_devices)
                                Text(device.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
                                if (device.id == devices.primaryId) PrimaryBadge()
                            }
                        }
                    }
                    ActionButton("Add home-screen widget", ::addWidget, Modifier.fillMaxWidth(), icon = R.drawable.ic_widget)
                    SecondaryText("Clipboard content never appears in the widget. Tapping a device opens a brief transfer screen.")
                    if (devices.devices.isEmpty()) QuietButton("Pair a Mac", { detail = "pair" }, icon = R.drawable.ic_add)
                }
                else -> when (tab) {
                    "Devices" -> {
                        PageHeader("Your workspace", "Your devices.\nWithin reach.", "Tap a device to send your clipboard.")
                        when {
                            devices.loading -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                            devices.loadError != null -> Panel(Modifier.fillMaxWidth()) {
                                Text("Unable to load devices", style = MaterialTheme.typography.titleMedium)
                                SecondaryText(devices.loadError!!)
                                ActionButton("Try again", devices::refresh, primary = false)
                            }
                            devices.devices.isEmpty() -> EmptyDevices { detail = "pair" }
                            else -> {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("PAIRED DEVICES", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    QuietButton("Pair device", { detail = "pair" }, icon = R.drawable.ic_add)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    devices.devices.forEach { device -> DeviceCard(device, devices, send) {
                                        smsDeviceId = device.id; devices.open(device.id); detail = "sms"
                                    } }
                                }
                                SecondaryText("Only devices you’ve paired appear here. A saved pairing doesn’t indicate current reachability.")
                            }
                        }
                    }
                    "Learn" -> LearnScreen(lesson, { lesson = it }, experiments)
                    else -> {
                        PageHeader("Make it yours", "Settings.", "A few things that make Sparshur yours.")
                        SettingsGroup("SHARING") {
                            SettingsRow("Primary device", R.drawable.ic_star,
                                devices.devices.firstOrNull { it.id == devices.primaryId }?.name ?: "Not set", { tab = "Devices" })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsRow("Home-screen widget", R.drawable.ic_widget, "", { detail = "widget" })
                        }
                        if (devices.primaryId != null) QuietButton("Clear Primary", { devices.makePrimary(null) })
                        SettingsGroup("CONNECTION") {
                            SettingsRow("Pair a Mac", R.drawable.ic_qr, "", { detail = "pair" })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ConnectionSettings(pairing)
                            if (BuildConfig.DEBUG) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                SettingsRow("Mac mini relay", R.drawable.ic_branch, "Connection lesson", {
                                    context.startActivity(android.content.Intent(context, com.sparsh.sparshur.network.relay.RelayActivity::class.java))
                                })
                            }
                        }
                        SettingsGroup("REFERENCE") {
                            SettingsRow("Guide & experiments", R.drawable.ic_learn, "13 lessons", { tab = "Learn" })
                        }
                        Text("Sparshur ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun EmptyDevices(pair: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile(R.drawable.ic_phone)
            Text("→", color = MaterialTheme.colorScheme.primary)
            IconTile(R.drawable.ic_devices)
        }
        Text("Your Mac, one tap away.", style = MaterialTheme.typography.titleLarge)
        SecondaryText("Pair from your Mac’s QR code to start sharing clips. Your devices will appear here.")
        Spacer(Modifier.height(4.dp))
        ActionButton("Pair a Mac", pair, Modifier.fillMaxWidth(), icon = R.drawable.ic_qr)
    }
    Text("YOUR CLIPBOARD STAYS YOURS", style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SecondaryText("Sparshur reads your clipboard only when you choose a destination. Sensitive clips are never shared.")
}

@Composable
private fun IconTile(icon: Int) {
    Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { AppIcon(icon) }
}

@Composable
private fun PrimaryBadge() {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape = RoundedCornerShape(5.dp)) {
        Text("Primary", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DeviceCard(device: ConnectedDevice, model: DevicesViewModel, send: (String) -> Unit, sms: () -> Unit) {
    val primary = device.id == model.primaryId
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { send(device.id) }
                .semantics { contentDescription = "Send clipboard to ${device.name}" }.padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(R.drawable.ic_devices)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text("${device.platform} · Paired", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (primary) PrimaryBadge()
                AppIcon(R.drawable.ic_send, tint = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(start = 14.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                val mode = when (model.policies[device.id]?.mode) { SmsMode.ALL -> "All messages"; SmsMode.ALLOWLIST -> "Selected"; else -> "Off" }
                Row(Modifier.weight(1f).heightIn(min = 46.dp).clickable(role = Role.Button, onClick = sms)
                    .semantics { contentDescription = "SMS sharing for ${device.name}" }, verticalAlignment = Alignment.CenterVertically) {
                    Text("SMS sharing", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(mode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    AppIcon(R.drawable.ic_chevron, modifier = Modifier.padding(start = 3.dp).size(14.dp))
                }
                IconButton(onClick = { model.makePrimary(device.id) }, enabled = !model.primaryBusy) {
                    AppIcon(R.drawable.ic_star, if (primary) "${device.name} is Primary" else "Make ${device.name} Primary",
                        if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(content = content) }
    }
}

@Composable
private fun SettingsRow(label: String, icon: Int, value: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click).padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppIcon(icon)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppIcon(R.drawable.ic_chevron, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun PairDeviceScreen(model: LabViewModel, network: (() -> Unit) -> Unit, scan: () -> Unit) {
    val state = model.state
    var manual by rememberSaveable { mutableStateOf(false) }
    val enabled = state.busy == null && BuildConfig.DEBUG
    PageHeader("A trusted connection", "Bring your Mac\ninto the loop.", "Open Pair phone on your Mac. Scan the QR code, then compare its fingerprint.")
    Panel(Modifier.fillMaxWidth()) {
        listOf("Open Sparshur on your Mac", "Choose Pair phone and generate a QR", "Scan below and compare the fingerprint").forEachIndexed { index, text ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("0${index + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    ActionButton(if (state.busy != null) "Working…" else "Scan Mac QR", scan, Modifier.fillMaxWidth(), enabled, icon = R.drawable.ic_qr)
    QuietButton(if (manual) "Hide manual entry" else "Use a pairing bundle instead", { manual = !manual })
    if (manual || state.showPairingSetup) {
        AppField(state.bundleInput, model::bundleInput, "Trusted pairing bundle", Modifier.fillMaxWidth(), singleLine = false)
        SecondaryText("Transfer this privately from your Mac. It contains a temporary pairing secret.")
        ActionButton("Review bundle", { model.importBundle() }, enabled = enabled && state.bundleInput.isNotBlank(), primary = false)
    }
    state.pendingPair?.let { bundle ->
        Panel(Modifier.fillMaxWidth()) {
            Text("Compare fingerprints", style = MaterialTheme.typography.titleMedium)
            Text(PairedProtocol.fingerprint(bundle.peer).chunked(8).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            SecondaryText("Continue only if this matches the Mac’s display. Pairing replaces the active phone on that Mac.")
            ActionButton("Fingerprints match · Pair", { network(model::pairMac) }, Modifier.fillMaxWidth(), enabled)
        }
    }
    state.peer?.let { Panel(Modifier.fillMaxWidth()) {
        Text("Mac paired", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        Text(PairedProtocol.fingerprint(it).chunked(8).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    } }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    if (!BuildConfig.DEBUG) SecondaryText("This build does not include a production transport. Local pairing is available in debug builds.")
}

@Composable
private fun ConnectionSettings(model: LabViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsRow("Connection details", R.drawable.ic_settings, "", { expanded = !expanded })
    if (expanded) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryText("Local connection · debug only. Editing the address preserves the pinned keys.")
        AppField(model.state.address, model::address, "Mac IPv4 address", Modifier.fillMaxWidth())
        AppField(model.state.port, model::port, "Port", Modifier.fillMaxWidth())
        ActionButton("Save connection", model::saveConnection, Modifier.fillMaxWidth(), model.state.peer != null && model.state.busy == null, primary = false)
        model.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
