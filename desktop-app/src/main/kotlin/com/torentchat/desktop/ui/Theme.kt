package com.torentchat.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF00E5C7), onPrimary = Color(0xFF003730),
    secondary = Color(0xFFB0CCC8), onSecondary = Color(0xFF1B3531),
    background = Color(0xFF0F1413), onBackground = Color(0xFFE0E3E1),
    surface = Color(0xFF151B1A), onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF1F2725), onSurfaceVariant = Color(0xFFBFCBC8),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

@Composable
fun TorentChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
