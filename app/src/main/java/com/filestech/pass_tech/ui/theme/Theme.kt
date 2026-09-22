package com.filestech.pass_tech.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Dark palette of the Flutter app (2.7.1, lib/main.dart `_darkTheme`, GitHub dark), carried over unchanged.
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
    // 2.7.1: pages (scaffold, app bar) on #0D1117, surfaces (cards, splash) on #161B22.
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurface,
    surfaceContainerHighest = DarkSurfaceHigh,
    outline = DarkBorder,
    outlineVariant = DarkBorder,
    error = DarkError,
)

// The light theme of 2.7.1 is `ColorScheme.fromSeed(0xFF1F6FEB)`. Compose has no seed generator: these
// are the exact colours Flutter derives from that seed, printed by Flutter itself (2026-09-22). The
// tonal algorithm tones the brand blue down to 0xFF465D91: that IS what 2.7.1 shows in light mode.
private val LightColors = lightColorScheme(
    primary = Color(0xFF465D91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF2D4578),
    secondary = Color(0xFF575E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE2F9),
    onSecondaryContainer = Color(0xFF404759),
    tertiary = Color(0xFF725573),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFDD7FB),
    onTertiaryContainer = Color(0xFF593E5A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1A1B20),
    onSurfaceVariant = Color(0xFF44464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFFAFC6FF),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF465D91),
)

/** The strength gauge colours of 2.7.1 (`password_strength_service.dart`), identical in both themes. */
object StrengthColors {
    val Weak = Color(0xFFE53935)
    val Medium = Color(0xFFFF7043)
    val Strong = Color(0xFFFDD835)
    val VeryStrong = Color(0xFF43A047)
}

/** Whether the dark palette is showing: the two themes of 2.7.1 draw cards differently. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** 2.7.1's red for destructive actions, in both themes (`widgets/destructive.dart`). */
val DestructiveRed = Color(0xFFC62828)

/** 2.7.1's favourite star (Flutter `Colors.amber` shades 400 and 600). */
val FavoriteAmber = Color(0xFFFFCA28)
val FavoriteAmberDark = Color(0xFFFFB300)

/** Follows the system setting, as 2.7.1 does by default (`ThemeMode.system`). */
@Composable
fun PassTechTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
