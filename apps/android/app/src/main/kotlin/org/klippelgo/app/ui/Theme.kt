package org.klippelgo.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Greyscale with one blue accent, as in the wireframes: blue marks locked fares and privacy labels.
private val Blue = Color(0xFF1F5FD1)
private val BlueDark = Color(0xFF8AB0FF)

private val Light = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Color(0xFF5F6368),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F3F4),
    background = Color.White,
)

private val Dark = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF0B1F45),
    secondary = Color(0xFFBDC1C6),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF2A2B2E),
    background = Color(0xFF121212),
)

@Composable
fun KlippelGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
