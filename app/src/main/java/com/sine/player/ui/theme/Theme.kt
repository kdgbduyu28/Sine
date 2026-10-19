package com.sine.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object SineColors {
    val background = Color(0xFF0E0D10)
    val surface = Color(0xFF1A181D)
    val surfaceHigh = Color(0xFF26232B)
    val accent = Color(0xFFF5C451)
    val onAccent = Color(0xFF1A1405)
    val text = Color(0xFFF4F1EA)
    val muted = Color(0xFFA9A3B0)
    val danger = Color(0xFFFF7A6B)
}

private val colors = darkColorScheme(
    primary = SineColors.accent,
    onPrimary = SineColors.onAccent,
    secondary = SineColors.accent,
    onSecondary = SineColors.onAccent,
    background = SineColors.background,
    onBackground = SineColors.text,
    surface = SineColors.surface,
    onSurface = SineColors.text,
    surfaceVariant = SineColors.surfaceHigh,
    onSurfaceVariant = SineColors.muted,
    inverseSurface = SineColors.text,
    inverseOnSurface = SineColors.background,
    error = SineColors.danger,
    border = SineColors.accent,
)

@Composable
fun SineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
