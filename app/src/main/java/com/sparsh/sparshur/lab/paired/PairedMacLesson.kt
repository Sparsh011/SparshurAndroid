package com.sparsh.sparshur.lab.paired

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sparsh.sparshur.BuildConfig
import com.sparsh.sparshur.lab.BytePanel
import com.sparsh.sparshur.lab.Definition
import com.sparsh.sparshur.lab.LabViewModel
import com.sparsh.sparshur.lab.LessonTitle
import com.sparsh.sparshur.lab.Note
import com.sparsh.sparshur.lab.OperationStatus

@Composable
fun PairedMacLesson(model: LabViewModel, networkAction: (() -> Unit) -> Unit, scan: () -> Unit) {
    val state = model.state
    val enabled = state.busy == null && BuildConfig.DEBUG
    LessonTitle("LESSON 02 / PAIR & DECRYPT", "A message only your Mac can open.")
    Text(
        "The Mac now owns an X25519 private key. You pin its public key by scanning a QR from its local dashboard. Android keeps a separate signing identity."
    )
    if (!BuildConfig.DEBUG) {
        Note(
            "Debug learning build required",
            "Local HTTP pairing and sending are disabled in release builds."
        )
    }
    state.peer?.let { peer ->
        Note("Paired Mac · public-key fingerprint", PairedProtocol.fingerprint(peer))
        Text(
            "This fingerprint is a public label for both pinned Mac keys. It is not a password or an IP address.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (state.peer == null || state.pendingPair != null || state.showPairingSetup) {
        Definition(
            "1. Pair using a trusted display",
            "On Mac, run python3 tools/laptop_receiver.py and open http://127.0.0.1:8787. Choose the hotspot IPv4 address and create a 5-minute QR. A successful new pairing replaces the previously paired phone."
        )
        Button(onClick = scan, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Scan Mac pairing QR") }
        OutlinedTextField(
            value = state.bundleInput,
            onValueChange = model::bundleInput,
            label = { Text("Trusted pairing bundle · manual fallback") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
        Text(
            "Manual input has the same trust requirement as QR. Transfer it privately from your Mac; it contains a one-time secret. Never fetch it over untrusted HTTP.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = { model.importBundle() },
            enabled = enabled && state.bundleInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Review pairing bundle") }
        state.pendingPair?.let { pending ->
            BytePanel("Compare this fingerprint with Mac", PairedProtocol.fingerprint(pending.peer))
            Text(
                "Destination: ${pending.peer.host}:${pending.peer.port}. The secret stays off the LAN; HMAC proofs bind both devices' keys to this pairing."
            )
            Button(
                onClick = { networkAction(model::pairMac) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Pair with this Mac") }
        }
    } else {
        OutlinedButton(onClick = scan, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Scan a new pairing QR")
        }
        OutlinedButton(onClick = model::showPairingSetup, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Open manual pairing fallback")
        }
    }
    OperationStatus(state)
    if (state.peer != null) {
        Definition(
            "2. Encrypt a new message",
            "A fresh random content key encrypts your text using ChaCha20-Poly1305. HPKE wraps that key for the Mac. Ed25519 signs the envelope so the Mac can verify the paired sender."
        )
        OutlinedTextField(
            value = state.text,
            onValueChange = model::text,
            label = { Text("Sample message for your Mac") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
        Button(
            onClick = model::encryptForMac,
            enabled = enabled && state.text.toByteArray().size in 1..4096,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Encrypt for paired Mac") }
        if (state.pairedWire.isNotEmpty()) {
            Definition(
                "3. Send & verify decryption",
                "This sends the encrypted snapshot below. Changing the text above requires encrypting again. Editing the destination changes routing; it never replaces the pinned keys."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    state.address,
                    model::address,
                    label = { Text("Mac IPv4 address") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    state.port,
                    model::port,
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(90.dp),
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Button(
                onClick = { networkAction { model.sendToMac() } },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Send to Mac & verify receipt") }
            OutlinedButton(
                onClick = { networkAction { model.sendToMac(tamper = true) } },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Flip one byte & send a copy") }
            BytePanel("What travels · ciphertext, wrapped key & signature", state.pairedWire)
        }
    }
    Definition(
        "Where are the private keys?",
        "Android's original AES key stays in Keystore. Its new Ed25519 signing seed is software key material, encrypted at rest with a separate Keystore key. Mac X25519/Ed25519 keys are protected at rest by Keychain and used by CryptoKit in memory."
    )
    Definition(
        "What this lesson still needs",
        "Local HTTP exposes metadata and allows disruption. Pairing trusts your QR source. This is one-way messaging for one phone, with persistent replay rejection but no reorder window, relay, group membership, or forward secrecy. Human protocol review is needed before production use."
    )
}
