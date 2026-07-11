package com.torentchat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─── Color palettes ───────────────────────────────────────────────────────────
// A privacy-focused dark-first aesthetic: deep charcoal + teal accent.

private val DarkColors = darkColorScheme(
    primary = Color(0xFF00E5C7),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF62FFE9),
    secondary = Color(0xFFB0CCC8),
    onSecondary = Color(0xFF1B3531),
    background = Color(0xFF0F1413),
    onBackground = Color(0xFFE0E3E1),
    surface = Color(0xFF151B1A),
    onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF1F2725),
    onSurfaceVariant = Color(0xFFBFCBC8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF74F8E2),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFDFA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4946),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

/**
 * Root theme. Dark-first — the app defaults to dark mode even on systems set to
 * light, since the target audience values privacy/low-profile usage. Users can
 * override in settings.
 */
@Composable
fun TorentChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Force dark by default for privacy aesthetic; respect system if user enables.
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TorentTypography,
        content = content,
    )
}
