package com.sparsh.sparshur.lab

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparsh.sparshur.BuildConfig
import com.sparsh.sparshur.lab.network.LabWire
import com.sparsh.sparshur.lab.paired.PairedMacLesson

private val Ink = Color(0xFF222226)

@Composable
fun LabScreen(model: LabViewModel, networkAction: (() -> Unit) -> Unit, scan: () -> Unit = {}) {
    val state = model.state
    val context = LocalContext.current
    val scroll = rememberScrollState()
    LaunchedEffect(state.step) { scroll.scrollTo(0) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OutlinedButton(onClick = { (context as? Activity)?.finish() }) {
                Text("Back to Learn")
            }
            Text(
                if (state.pairedMode) "BRIDGE  /  LEARNING LAB 02" else "BRIDGE  /  LEARNING LAB 01",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                if (state.pairedMode) "Two devices.\nOne trusted link." else "Follow your\nfirst secret.",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (state.pairedMode) {
                    "Pair your Mac, encrypt for its public key, and verify that it decrypted your message."
                } else {
                    "A hands-on trip from readable text to encrypted bytes. One phone, one key, one small experiment."
                },
                style = MaterialTheme.typography.bodyLarge
            )
            Card(colors = CardDefaults.cardColors(containerColor = Ink)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "PHONE  →  ENCRYPTED BYTES  →  MAC",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (state.pairedMode) "Private keys stay on their devices." else "The key stays here.",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (state.pairedMode) {
                            "Android signs and encrypts. Your paired Mac verifies and decrypts, then signs a receipt. QR pairing is the trust anchor."
                        } else {
                            "Your Mac observes this original local-key experiment. Only this phone can decrypt that AES envelope."
                        },
                        color = Color(0xFFDAE6E2),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.pairedMode,
                    onClick = { model.mode(false) },
                    enabled = state.busy == null,
                    label = { Text("Local key lab") }
                )
                FilterChip(
                    selected = state.pairedMode,
                    onClick = { model.mode(true) },
                    enabled = state.busy == null,
                    label = { Text("Pair & decrypt") }
                )
            }
            if (!state.pairedMode) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Key", "Encrypt", "Inspect", "Send").forEachIndexed { index, title ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = state.step == index,
                            onClick = { model.step(index) },
                            enabled = state.busy == null,
                            label = { Text(title, fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.pairedMode) {
                        PairedMacLesson(model, networkAction, scan)
                    } else {
                        when (state.step) {
                            0 -> KeyLesson(state, model)
                            1 -> EncryptLesson(state, model)
                            2 -> InspectLesson(state, model)
                            3 -> SendLesson(state, model, networkAction)
                        }
                    }
                }
            }
            Text(
                "LOCAL LEARNING EXPERIMENT · HUMAN PROTOCOL REVIEW REQUIRED",
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "These lessons do not read SMS or your clipboard. Use invented sample text. Messages stay in app memory; the receiver keeps a limited in-memory view. Nothing is written to message logs.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun KeyLesson(state: LabState, model: LabViewModel) {
    LessonTitle("01 / THE KEY", "A secret Android keeps for you.")
    Text(
        "An encryption key is random bytes, not a password you invent. Android Keystore creates our AES key and lets the app use it without reading its secret bytes."
    )
    Note("Your device reports", state.keyDescription)
    Definition("AES-256", "The encryption algorithm, using a 256-bit secret key.")
    Definition(
        "Keystore",
        "Android's protected key container. Hardware support varies by device; the status above reports what this device actually provides."
    )
    Definition(
        "Same key, next launch",
        "We look up the saved key by a name (an alias). We do not create a new key on every button press. Uninstalling removes it."
    )
    OutlinedButton(
        onClick = model::inspectKey,
        enabled = state.busy == null,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Inspect the saved key again") }
    OperationStatus(state)
    Button(onClick = { model.step(1) }, modifier = Modifier.fillMaxWidth(), enabled = state.busy == null) {
        Text("Next: encrypt a message →")
    }
}

@Composable
private fun EncryptLesson(state: LabState, model: LabViewModel) {
    LessonTitle("02 / ENCRYPT", "Give your message a lock.")
    Text(
        "Start with pretend text. Your phone turns it into UTF-8 bytes, then uses AES-GCM to encrypt and protect it against changes."
    )
    OutlinedTextField(
        value = state.text,
        onValueChange = model::text,
        enabled = state.busy == null,
        label = { Text("Sample message · never a real secret") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 6,
        supportingText = { Text("${state.text.toByteArray(Charsets.UTF_8).size} / 4096 UTF-8 bytes") }
    )
    Definition(
        "GCM",
        "Adds an authentication tag. With the key, Android checks the tag before returning any decrypted text."
    )
    Definition(
        "Nonce / IV",
        "12 public bytes generated by the encryption provider. Never reuse a nonce with the same key; our lab uses random nonces and limits key use."
    )
    Button(
        onClick = model::encrypt,
        enabled = state.busy == null &&
            state.text.toByteArray(Charsets.UTF_8).size in 1..4096,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Encrypt on this phone →")
    }
    OperationStatus(state)
}

@Composable
private fun InspectLesson(state: LabState, model: LabViewModel) {
    LessonTitle("03 / INSPECT", "Open the envelope, not the secret.")
    val envelope = state.envelope
    if (envelope == null) {
        Text(
            "Encrypt a sample first. Then this page will show the actual bytes and let you test what happens when they change."
        )
        Button(onClick = { model.step(1) }, enabled = state.busy == null) { Text("Go to encryption") }
        return
    }
    Definition(
        "1. Readable text → UTF-8 bytes",
        "The encrypted snapshot is separate from later edits in the text field."
    )
    Definition(
        "2. AES-GCM → ciphertext + tag",
        "${envelope.ciphertext.size - 16} encrypted bytes + a 16-byte tag. Counter: ${envelope.header.counter}."
    )
    BytePanel("3. Nonce · public, 12 bytes", LabWire.base64(envelope.nonce))
    BytePanel("Authentication tag · final 16 bytes", LabWire.base64(envelope.ciphertext.takeLast(16).toByteArray()))
    BytePanel("Associated data · readable but protected", String(envelope.header.associatedData(), Charsets.UTF_8))
    Text(
        "Associated data binds the version, devices, message type, counter and message ID to this ciphertext. It is not hidden. A counter alone does not prevent replays; a receiver must track what it accepted.",
        style = MaterialTheme.typography.bodySmall
    )
    Button(onClick = model::decrypt, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) {
        Text("Verify & decrypt on phone")
    }
    OutlinedButton(onClick = model::tamper, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) {
        Text("Flip one byte · test tampering")
    }
    OperationStatus(state)
    BytePanel("Exact encrypted HTTP body · no text or key", state.wire)
    Text(
        "Base64 makes binary bytes printable. Base64 itself is not encryption.",
        style = MaterialTheme.typography.bodySmall
    )
    Button(onClick = { model.step(3) }, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) {
        Text("Next: let the Mac observe →")
    }
}

@Composable
private fun SendLesson(state: LabState, model: LabViewModel, networkAction: (() -> Unit) -> Unit) {
    LessonTitle("04 / SEND", "Watch what reaches your Mac.")
    Text(
        "Turn on your phone's hotspot and join it from the Mac. Both devices share a network, but have different local IP addresses. Enter the Mac's address, not the phone's address."
    )
    BytePanel("On the Mac · from the project folder", "python3 tools/laptop_receiver.py\n\nOpen http://127.0.0.1:8787")
    Text(
        "The receiver prints possible Mac addresses. If it has no address yet, connect the hotspot and restart it. Allow Python's local-network / firewall prompt if macOS shows one.",
        style = MaterialTheme.typography.bodySmall
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.address,
            onValueChange = model::address,
            label = { Text("Mac IPv4 address") },
            placeholder = { Text("192.168.43.123") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = state.busy == null,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = state.port,
            onValueChange = model::port,
            label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = state.busy == null,
            modifier = Modifier.width(92.dp)
        )
    }
    val enabled = BuildConfig.DEBUG && state.busy == null
    if (!BuildConfig.DEBUG) {
        Note(
            "Debug build needed",
            "This release build does not allow the HTTP learning experiment."
        )
    }
    OutlinedButton(
        onClick = { networkAction(model::connect) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Check connection") }
    Note(
        "Encrypted path",
        "Sends the envelope from step 03, not later text edits. The Mac can see metadata and ciphertext, but cannot decrypt with your phone's private Keystore key."
    )
    Button(
        onClick = { networkAction(model::sendEncrypted) },
        enabled = enabled && state.envelope != null,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Send encrypted envelope") }
    Note(
        "Readable comparison · HTTP",
        "This separate action sends the CURRENT sample text from step 02 without encryption. Your Mac and network observers can read it. Use made-up text only.",
        error = true
    )
    OutlinedButton(
        onClick = { networkAction(model::sendPlaintext) },
        enabled = enabled &&
            state.text.toByteArray(Charsets.UTF_8).size in 1..4096,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Send readable demo text") }
    OperationStatus(state)
    Definition(
        "No connection?",
        "Check the address and port, allow Nearby devices on Android 17+, and check the Mac firewall. Some hotspots isolate devices. USB fallback: adb reverse tcp:8787 tcp:8787, then use 127.0.0.1 here. Emulator: use 10.0.2.2."
    )
    Definition(
        "What comes next?",
        "Pairing proves which public key belongs to your Mac. Public-key encryption then lets the Mac decrypt while each device keeps its private keys. This lesson does not authenticate the laptop or sender."
    )
}

@Composable
internal fun OperationStatus(state: LabState) {
    state.busy?.let {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    state.error?.let { Note("Something needs attention", it, error = true) }
    if (state.result.isNotEmpty()) Note("What happened", state.result)
}

@Composable
internal fun LessonTitle(kicker: String, title: String) {
    Text(
        kicker,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
internal fun Definition(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Note(title: String, text: String, error: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().background(
            if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            RoundedCornerShape(12.dp)
        ).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            fontWeight = FontWeight.Bold,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }
        )
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
        }
    }
}

@Composable
internal fun BytePanel(title: String, value: String) {
    Column(
        Modifier.fillMaxWidth().background(Ink, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        SelectionContainer {
            Text(value, color = Color(0xFFE5F0EA), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}
