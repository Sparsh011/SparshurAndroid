package com.sparsh.sparshur.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppIcon(@DrawableRes icon: Int, description: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), description, modifier.size(20.dp), tint = tint)
}

@Composable
fun Eyebrow(text: String) {
    Text(text.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp,
        lineHeight = 15.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun PageHeader(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(eyebrow)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        SecondaryText(subtitle)
    }
}

@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    primary: Boolean = true, @DrawableRes icon: Int? = null) {
    Button(onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (primary) Color.White else MaterialTheme.colorScheme.onSurface),
        border = if (primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)) {
        if (icon != null) { AppIcon(icon, tint = LocalContentColor.current); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null,
    enabled: Boolean = true) {
    TextButton(onClick, modifier.heightIn(min = 44.dp), enabled = enabled,
        shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
        if (icon != null) { AppIcon(icon, tint = LocalContentColor.current); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
fun AppField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    placeholder: String = "", singleLine: Boolean = true, onSubmit: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value, onValueChange, Modifier.fillMaxWidth().semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = singleLine,
            interactionSource = interaction,
            maxLines = if (singleLine) 1 else 5,
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = if (singleLine) 46.dp else 90.dp)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .padding(1.dp)) {
                    Surface(shape = RoundedCornerShape(8.dp), color = Color.Transparent,
                        border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.matchParentSize()) {}
                    Box(Modifier.padding(12.dp).align(if (singleLine) Alignment.CenterStart else Alignment.TopStart)) {
                        if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                        inner()
                    }
                }
            })
    }
}
