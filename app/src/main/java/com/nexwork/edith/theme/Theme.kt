package com.nexwork.edith.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalCrimsonMonetActive = staticCompositionLocalOf { false }

private val EdithGlassColorScheme =
  darkColorScheme(
    primary = IceGlowAccent,
    onPrimary = ObsidianBgTop,
    secondary = TextSilver,
    onSecondary = ObsidianBgTop,
    tertiary = WarmSunAccent,
    background = ObsidianBgTop,
    onBackground = TextPureWhite,
    surface = GlassSurface,
    onSurface = TextPureWhite,
    surfaceVariant = GlassSurfaceElevated,
    onSurfaceVariant = TextSilver,
  )

private val EdithCrimsonMonetColorScheme =
  darkColorScheme(
    primary = CrimsonBright,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF680015),
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = Crimson,
    onSecondary = Color.White,
    tertiary = CrimsonBright,
    background = Color.Black,
    onBackground = Color(0xFFE2E2E2),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFFC6C6C6),
    outline = Crimson,
  )

@Composable
fun EDITHTheme(
  crimsonMonetActive: Boolean = false,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(LocalCrimsonMonetActive provides crimsonMonetActive) {
    MaterialTheme(
      colorScheme = if (crimsonMonetActive) EdithCrimsonMonetColorScheme else EdithGlassColorScheme,
      typography = Typography,
      content = content,
    )
  }
}
