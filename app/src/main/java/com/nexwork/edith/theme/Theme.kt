package com.nexwork.edith.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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

@Composable
fun EDITHTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = EdithGlassColorScheme,
    typography = Typography,
    content = content,
  )
}
