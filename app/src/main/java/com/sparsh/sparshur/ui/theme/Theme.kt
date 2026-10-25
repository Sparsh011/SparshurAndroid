package com.sparsh.sparshur.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same palette as the native Mac Views/Theme.swift; the widget uses these tokens too.
private val MacColors = darkColorScheme(
    primary = Color(0xFFF72681), onPrimary = Color.White,
    primaryContainer = Color(0xFF3B182B), onPrimaryContainer = Color(0xFFFFB1D4),
    secondary = Color(0xFFA6A6B0), onSecondary = Color(0xFF131316),
    secondaryContainer = Color(0xFF222226), onSecondaryContainer = Color(0xFFF0F0F5),
    background = Color(0xFF131316), onBackground = Color(0xFFF0F0F5),
    surface = Color(0xFF1B1B1F), onSurface = Color(0xFFF0F0F5),
    surfaceVariant = Color(0xFF222226), onSurfaceVariant = Color(0xFFA6A6B0),
    outline = Color(0xFF55555E), outlineVariant = Color(0xFF2D2D33),
    error = Color(0xFFFFB4AB)
)

@Composable
fun SparshurTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MacColors, typography = Typography, content = content)
}
