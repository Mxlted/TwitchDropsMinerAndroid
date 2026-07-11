package com.nathan.twitchdropsminer.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = AppAccent,
    onPrimary = Color(0xFF211132),
    secondary = AppAccentAlt,
    onSecondary = Color(0xFF061A26),
    tertiary = AppWarning,
    onTertiary = Color(0xFF261B00),
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceVariant = AppSurfaceHigh,
    onSurfaceVariant = AppMuted,
    error = AppError,
    onError = Color(0xFF310006),
)

@Composable
fun TwitchDropsMinerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
