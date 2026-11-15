package com.nexwork.edith.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.nexwork.edith.model.LauncherAccentPreset
import com.nexwork.edith.model.LauncherFontStyle
import com.nexwork.edith.model.LauncherSettingsConfig

val LocalCrimsonMonetActive = staticCompositionLocalOf { false }

@Immutable
data class LauncherStyleTokens(
  val accent: Color = IceGlowAccent,
  val accentDim: Color = IceGlowAccent.copy(alpha = 0.34f),
  val fontFamily: FontFamily = FontFamily.Default,
  val fontScale: Float = 1.0f,
  val accentPreset: LauncherAccentPreset = LauncherAccentPreset.ICE_CYAN,
  val fontStyle: LauncherFontStyle = LauncherFontStyle.DEFAULT,
)

val LocalLauncherStyle = staticCompositionLocalOf { LauncherStyleTokens() }

fun LauncherAccentPreset.toColor(): Color = when (this) {
  LauncherAccentPreset.ICE_CYAN -> IceGlowAccent
  LauncherAccentPreset.STARK_CRIMSON -> CrimsonBright
  LauncherAccentPreset.SOLAR_AMBER -> WarmSunAccent
}

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
  launcherSettings: LauncherSettingsConfig = LauncherSettingsConfig(),
  content: @Composable () -> Unit,
) {
  val launcherAccent = launcherSettings.accentPreset.toColor()
  val styleTokens = LauncherStyleTokens(
    accent = launcherAccent,
    accentDim = launcherAccent.copy(alpha = 0.34f),
    fontFamily = launcherSettings.fontStyle.toFontFamily(),
    fontScale = launcherSettings.fontScale.coerceIn(0.85f, 1.25f),
    accentPreset = launcherSettings.accentPreset,
    fontStyle = launcherSettings.fontStyle,
  )
  val baseScheme = if (crimsonMonetActive) EdithCrimsonMonetColorScheme else EdithGlassColorScheme
  val colorScheme = baseScheme.copy(
    primary = launcherAccent,
    tertiary = launcherAccent,
  )
  CompositionLocalProvider(
    LocalCrimsonMonetActive provides crimsonMonetActive,
    LocalLauncherStyle provides styleTokens,
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = buildEdithTypography(
        fontStyle = launcherSettings.fontStyle,
        scale = launcherSettings.fontScale,
      ),
      content = content,
    )
  }
}
