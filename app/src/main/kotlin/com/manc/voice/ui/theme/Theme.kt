package com.manc.voice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7C9CFF)
private val AccentDim = Color(0xFF3A4C86)
private val OkGreen = Color(0xFF53D7A0)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B1020),
    secondary = OkGreen,
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE6ECFF),
    surface = Color(0xFF141A2E),
    onSurface = Color(0xFFE6ECFF),
    surfaceVariant = Color(0xFF1C2440),
    onSurfaceVariant = Color(0xFFB7C2E6),
    error = Color(0xFFFF6B6B)
)

private val Light = lightColorScheme(
    primary = AccentDim,
    background = Color(0xFFF6F8FF),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun MancTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography(),
        content = content
    )
}
