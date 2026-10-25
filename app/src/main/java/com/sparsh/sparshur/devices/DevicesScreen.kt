package com.sparsh.sparshur.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparsh.sparshur.R
import com.sparsh.sparshur.ui.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmsFilterScreen(model: DevicesViewModel) {
    val device = model.selected ?: return
    var senderDraft by remember(device.id) { mutableStateOf("") }
    var keywordDraft by remember(device.id) { mutableStateOf("") }
    var showKeywords by remember(device.id) { mutableStateOf(model.keywords.isNotBlank()) }
    var showTest by remember(device.id) { mutableStateOf(false) }
    fun rules(value: String) = value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    LaunchedEffect(model.mode, model.senders, model.keywords, model.sampleSender, model.sampleBody) {
        if (model.sampleSender.isNotBlank() && model.sampleBody.isNotBlank()) model.testPolicy()
    }
    PageHeader(device.name, "SMS, on your terms.", "Choose what this device gets.")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(SmsMode.OFF to "Off", SmsMode.ALLOWLIST to "Selected", SmsMode.ALL to "All SMS").forEach { (mode, label) ->
            val selected = model.mode == mode
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(7.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                .selectable(selected, role = Role.RadioButton, onClick = { model.mode(mode) }).padding(horizontal = 4.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    SecondaryText(when (model.mode) {
        SmsMode.OFF -> "No SMS is allowed by these rules."
        SmsMode.ALLOWLIST -> "Only a matching sender or keyword is allowed."
        SmsMode.ALL -> "All SMS is allowed, including personal messages and OTPs."
    })
    if (model.mode == SmsMode.ALLOWLIST) {
        Panel(Modifier.fillMaxWidth()) {
            Text("Allowed senders", style = MaterialTheme.typography.titleMedium)
            SecondaryText("Exact sender ID or full phone number.")
            if (rules(model.senders).isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rules(model.senders).forEach { sender -> RuleChip(sender, "Remove sender $sender") {
                    model.senders(rules(model.senders).filterNot { it == sender }.joinToString("\n"))
                } }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun add() {
                    val value = senderDraft.trim()
                    if (value.isNotEmpty()) { model.senders((rules(model.senders) + value).distinct().joinToString("\n")); senderDraft = "" }
                }
                AppField(senderDraft, { if (it.length <= 120) senderDraft = it }, "Add a sender", Modifier.weight(1f),
                    placeholder = "Sender ID or phone number", onSubmit = ::add)
                ActionButton("Add", ::add, Modifier.semantics { contentDescription = "Add sender" },
                    enabled = senderDraft.isNotBlank() && rules(model.senders).size < 40, primary = false)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(if (showKeywords) "Hide message keywords" else "Also allow message keywords", { showKeywords = !showKeywords })
            if (showKeywords) Panel(Modifier.fillMaxWidth()) {
                Text("Message keywords", style = MaterialTheme.typography.titleMedium)
                SecondaryText("Optional. A broad phrase may match personal messages too.")
                if (rules(model.keywords).isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rules(model.keywords).forEach { word -> RuleChip(word, "Remove keyword $word") {
                        model.keywords(rules(model.keywords).filterNot { it == word }.joinToString("\n"))
                    } }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun add() {
                        val value = keywordDraft.trim()
                        if (value.isNotEmpty()) { model.keywords((rules(model.keywords) + value).distinct().joinToString("\n")); keywordDraft = "" }
                    }
                    AppField(keywordDraft, { if (it.length <= 120) keywordDraft = it }, "Add a phrase", Modifier.weight(1f), onSubmit = ::add)
                    ActionButton("Add", ::add, Modifier.semantics { contentDescription = "Add keyword" },
                        enabled = keywordDraft.isNotBlank() && rules(model.keywords).size < 40, primary = false)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietButton(if (showTest) "Hide rule test" else "Test these rules", { showTest = !showTest })
        if (showTest) Panel(Modifier.fillMaxWidth()) {
            Text("Check a message", style = MaterialTheme.typography.titleMedium)
            SecondaryText("Local preview only. Nothing is transmitted or saved.")
            AppField(model.sampleSender, model::sampleSender, "Test sender", Modifier.fillMaxWidth())
            AppField(model.sampleBody, model::sampleBody, "Test message", Modifier.fillMaxWidth(), singleLine = false)
            if (model.sampleSender.isNotBlank() && model.sampleBody.isNotBlank()) model.filterResult?.let { result ->
                val allowed = result.startsWith("Allowed")
                if (allowed || result.startsWith("Blocked")) Text(if (allowed) "Allowed by these rules" else "Blocked by these rules",
                    style = MaterialTheme.typography.titleSmall, color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                else Text(result, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("SMS delivery isn’t enabled yet.", style = MaterialTheme.typography.titleSmall)
        SecondaryText("These rules are saved on your phone for when SMS delivery is connected. Sender names don’t verify an organization’s identity.")
    }
}

@Composable
private fun RuleChip(text: String, description: String, remove: () -> Unit) {
    Surface(onClick = remove, shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .07f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .18f)),
        modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = description }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            AppIcon(R.drawable.ic_close, modifier = Modifier.size(13.dp))
        }
    }
}
