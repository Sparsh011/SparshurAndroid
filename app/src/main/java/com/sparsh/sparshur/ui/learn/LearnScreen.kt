package com.sparsh.sparshur.ui.learn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparsh.sparshur.R
import com.sparsh.sparshur.ui.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LearnScreen(topicIndex: Int?, open: (Int?) -> Unit, experiments: () -> Unit) {
    if (topicIndex == null) {
        var query by rememberSaveable { mutableStateOf("") }
        PageHeader("Build it. Understand it.", "Know your bridge.", "Small lessons, real code, and something to try. Available offline.")
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .045f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .16f))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Explore a lesson")
                Text("Inside a paired envelope", style = MaterialTheme.typography.titleLarge)
                SecondaryText("Follow the text, content key and signature.")
                ActionButton("Explore lesson", { open(3) }, Modifier.fillMaxWidth())
            }
        }
        AppField(query, { query = it }, "Find a topic", Modifier.fillMaxWidth(), placeholder = "Encryption, pairing, storage…")
        val matches = guideLessons.indices.filter { query.isBlank() ||
            "${guideLessons[it].title} ${guideLessons[it].summary} ${guideLessons[it].code}".contains(query, ignoreCase = true) }
        if (matches.isEmpty()) SecondaryText("No matching lessons. Try a different word.")
        Column {
            listOf("CRYPTO & TRUST" to 0..6, "DELIVERY & PLATFORM" to 7..9, "BUILDING THE APPS" to 10..12).forEach { (group, range) ->
                val indices = matches.filter { it in range }
                if (indices.isNotEmpty()) {
                    Text(group, Modifier.padding(top = 15.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    indices.forEach { index ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(index) }.padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((index + 1).toString().padStart(2, '0'), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            Text(guideLessons[index].title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            AppIcon(R.drawable.ic_chevron, modifier = Modifier.size(15.dp))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        ActionButton("Open hands-on labs", experiments, Modifier.fillMaxWidth(), primary = false, icon = R.drawable.ic_learn)
    } else {
        val topic = guideLessons[topicIndex]
        var changed by remember(topicIndex) { mutableStateOf(false) }
        var showCode by remember(topicIndex) { mutableStateOf(false) }
        PageHeader("Lesson ${(topicIndex + 1).toString().padStart(2, '0')} / ${guideLessons.size}", topic.title, topic.summary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            topic.flow.forEach { step -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(7.dp)) {
                Text(step, Modifier.padding(horizontal = 9.dp, vertical = 7.dp), fontSize = 11.sp)
            } }
        }
        Panel(Modifier.fillMaxWidth()) {
            Eyebrow("What happens")
            topic.steps.forEachIndexed { index, step ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("0${index + 1}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    SecondaryText(step)
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("Try it: ${topic.exercise}", style = MaterialTheme.typography.titleMedium)
            Text("Conceptual exercise · no transmission", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (changed) topic.after else topic.before, style = MaterialTheme.typography.bodyMedium)
            ActionButton(if (changed) "Reset example" else topic.action, { changed = !changed }, primary = false)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton("Where this lives in our code", { showCode = !showCode })
            if (showCode) Panel(Modifier.fillMaxWidth()) { SelectionContainer {
                Text(topic.code, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        ActionButton("Open hands-on labs", experiments, Modifier.fillMaxWidth(), primary = false)
        Text("The original labs run real cryptographic exercises. Protocol review is required before production use.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (topicIndex < guideLessons.lastIndex) QuietButton("Next lesson →", { open(topicIndex + 1) })
    }
}
