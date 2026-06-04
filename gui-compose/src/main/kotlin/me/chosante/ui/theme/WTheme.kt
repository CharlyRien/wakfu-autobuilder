package me.chosante.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val WakfuDarkColorScheme = darkColorScheme(
    primary = WColor.accent,
    onPrimary = WColor.text,
    primaryContainer = WColor.raised,
    onPrimaryContainer = WColor.text,
    secondary = WColor.accent2,
    onSecondary = WColor.text,
    secondaryContainer = WColor.raised,
    onSecondaryContainer = WColor.text,
    tertiary = WColor.warning,
    onTertiary = WColor.text,
    background = WColor.bg,
    onBackground = WColor.text,
    surface = WColor.surface,
    onSurface = WColor.text,
    surfaceVariant = WColor.raised,
    onSurfaceVariant = WColor.muted,
    outline = WColor.border,
    outlineVariant = WColor.hairline,
    error = WColor.danger,
    onError = WColor.text
)

@Composable
fun WTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WakfuDarkColorScheme,
        typography = WTypography,
        content = content
    )
}
