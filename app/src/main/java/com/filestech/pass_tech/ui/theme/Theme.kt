package com.filestech.pass_tech.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark palette of the Flutter app (2.7.1, lib/main.dart `_darkTheme`), carried over unchanged.
private val DarkBackground = Color(0xFF0D1117)
private val DarkSurface = Color(0xFF161B22)
private val DarkSurfaceHigh = Color(0xFF21262D)
private val DarkBorder = Color(0xFF30363D)
private val DarkTextPrimary = Color(0xFFE6EDF3)
private val DarkTextSecondary = Color(0xFF8B949E)
private val BrandBlueLight = Color(0xFF58A6FF)
private val BrandBlue = Color(0xFF1F6FEB)
private val DarkError = Color(0xFFFF7B72)

private val DarkColors = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = DarkBackground,
    primaryContainer = BrandBlue,
    onPrimaryContainer = DarkTextPrimary,
    background = DarkBackground,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainerHighest = DarkSurfaceHigh,
    outline = DarkBorder,
    error = DarkError,
)

// The Flutter light theme is `ColorScheme.fromSeed(0xFF1F6FEB)`. Compose has no seed generator, so
// only the brand colour is set here; the full tonal palette comes with the real screens.
private val LightColors = lightColorScheme(
    primary = BrandBlue,
)

@Composable
fun PassTechTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
