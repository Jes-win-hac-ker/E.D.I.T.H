package com.nexwork.edith.theme

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.nexwork.edith.model.HudFontScope
import com.nexwork.edith.model.HudIntensityPreset
import com.nexwork.edith.model.LauncherAccentPreset
import com.nexwork.edith.model.LauncherFontStyle
import com.nexwork.edith.model.LauncherSettingsConfig

val LocalCrimsonMonetActive = staticCompositionLocalOf { false }

@Immutable
data class HudGlobePalette(
  val accentHex: Int,
  val waveR: Float,
  val waveG: Float,
  val waveB: Float,
  val graticuleHex: Int,
)

@Immutable
data class LauncherStyleTokens(
  val accent: Color = IceGlowAccent,
  val graphicAccent: Color = IceGlowAccent,
  val accentDim: Color = IceGlowAccent.copy(alpha = 0.38f),
  val voidTop: Color = Color(0xFF05070B),
  val voidBottom: Color = Color(0xFF0C121B),
  val orbitalMuted: Color = Color(0xFF335D78),
  val semanticWarn: Color = WarmSunAccent,
  val semanticDangerText: Color = CrimsonBright,
  val semanticDangerGraphic: Color = Crimson,
  val idleBorderAlpha: Float = 0.54f,
  val focusedBorderAlpha: Float = 0.94f,
  val railAlpha: Float = 0.74f,
  val plateTintAlpha: Float = 0.07f,
  val fontFamily: FontFamily = FontFamily.Monospace,
  val telemetryFontFamily: FontFamily = FontFamily.Monospace,
  val canvasTypeface: Typeface? = Typeface.MONOSPACE,
  val canvasBoldTypeface: Typeface? = Typeface.MONOSPACE,
  val telemetryTypeface: Typeface? = Typeface.MONOSPACE,
  val fontScale: Float = 1.0f,
  val accentPreset: LauncherAccentPreset = LauncherAccentPreset.ICE_CYAN,
  val hudIntensity: HudIntensityPreset = HudIntensityPreset.TACTICAL,
  val fontStyle: LauncherFontStyle = LauncherFontStyle.IBM_PLEX_MONO,
  val fontScope: HudFontScope = HudFontScope.ALL_HUD,
)

val LocalLauncherStyle = staticCompositionLocalOf { LauncherStyleTokens() }

fun LauncherAccentPreset.toColor(): Color = when (this) {
  LauncherAccentPreset.ICE_CYAN -> IceGlowAccent
  LauncherAccentPreset.STARK_CRIMSON -> CrimsonBright
  LauncherAccentPreset.SOLAR_AMBER -> WarmSunAccent
  LauncherAccentPreset.VERIDIAN_TAC -> VeridianAccent
  LauncherAccentPreset.STEALTH_TITANIUM -> TitaniumAccent
}

fun LauncherAccentPreset.toGraphicColor(): Color = when (this) {
  LauncherAccentPreset.STARK_CRIMSON -> Crimson
  else -> toColor()
}

fun LauncherAccentPreset.toGlobePalette(): HudGlobePalette = when (this) {
  LauncherAccentPreset.ICE_CYAN -> HudGlobePalette(
    accentHex = 0x38BDF8,
    waveR = 0.22f,
    waveG = 0.98f,
    waveB = 1.58f,
    graticuleHex = 0x2C5470,
  )
  LauncherAccentPreset.STARK_CRIMSON -> HudGlobePalette(
    accentHex = 0xE01432,
    waveR = 1.65f,
    waveG = 0.04f,
    waveB = 0.14f,
    graticuleHex = 0x64748B,
  )
  LauncherAccentPreset.SOLAR_AMBER -> HudGlobePalette(
    accentHex = 0xFBBF24,
    waveR = 1.48f,
    waveG = 0.96f,
    waveB = 0.08f,
    graticuleHex = 0x6A5428,
  )
  LauncherAccentPreset.VERIDIAN_TAC -> HudGlobePalette(
    accentHex = 0x2DD4BF,
    waveR = 0.14f,
    waveG = 1.45f,
    waveB = 1.24f,
    graticuleHex = 0x255E58,
  )
  LauncherAccentPreset.STEALTH_TITANIUM -> HudGlobePalette(
    accentHex = 0xE2E8F0,
    waveR = 1.18f,
    waveG = 1.24f,
    waveB = 1.32f,
    graticuleHex = 0x586272,
  )
}

fun buildLauncherStyleTokens(
  settings: LauncherSettingsConfig,
  canvasTypeface: Typeface? = Typeface.MONOSPACE,
  canvasBoldTypeface: Typeface? = Typeface.MONOSPACE,
  telemetryTypeface: Typeface? = Typeface.MONOSPACE,
): LauncherStyleTokens {
  val accent = settings.accentPreset.toColor()
  val graphic = settings.accentPreset.toGraphicColor()
  val (voidTop, voidBottom, orbitalMuted) = when (settings.accentPreset) {
    LauncherAccentPreset.ICE_CYAN -> Triple(Color(0xFF05070B), Color(0xFF0C121B), Color(0xFF335D78))
    LauncherAccentPreset.STARK_CRIMSON -> Triple(Color(0xFF070506), Color(0xFF14080B), Color(0xFF702430))
    LauncherAccentPreset.SOLAR_AMBER -> Triple(Color(0xFF070604), Color(0xFF141008), Color(0xFF705620))
    LauncherAccentPreset.VERIDIAN_TAC -> Triple(Color(0xFF040707), Color(0xFF091414), Color(0xFF236159))
    LauncherAccentPreset.STEALTH_TITANIUM -> Triple(Color(0xFF050506), Color(0xFF111316), Color(0xFF5D6777))
  }
  val (idleBorderAlpha, focusedBorderAlpha, railAlpha, plateTintAlpha) = when (settings.hudIntensity) {
    HudIntensityPreset.STEALTH -> listOf(0.34f, 0.78f, 0.48f, 0.02f)
    HudIntensityPreset.TACTICAL -> listOf(0.54f, 0.94f, 0.74f, 0.07f)
    HudIntensityPreset.OVERDRIVE -> listOf(0.76f, 1.00f, 0.94f, 0.13f)
  }
  val semanticWarn = if (settings.accentPreset == LauncherAccentPreset.SOLAR_AMBER) {
    IceGlowAccent
  } else {
    WarmSunAccent
  }
  return LauncherStyleTokens(
    accent = accent,
    graphicAccent = graphic,
    accentDim = accent.copy(alpha = 0.40f),
    voidTop = voidTop,
    voidBottom = voidBottom,
    orbitalMuted = orbitalMuted,
    semanticWarn = semanticWarn,
    semanticDangerText = CrimsonBright,
    semanticDangerGraphic = Crimson,
    idleBorderAlpha = idleBorderAlpha,
    focusedBorderAlpha = focusedBorderAlpha,
    railAlpha = railAlpha,
    plateTintAlpha = plateTintAlpha,
    fontFamily = settings.fontStyle.toFontFamily(),
    telemetryFontFamily = settings.fontStyle.toTelemetryFontFamily(settings.fontScope),
    canvasTypeface = canvasTypeface,
    canvasBoldTypeface = canvasBoldTypeface,
    telemetryTypeface = telemetryTypeface,
    fontScale = settings.fontScale.coerceIn(0.85f, 1.25f),
    accentPreset = settings.accentPreset,
    hudIntensity = settings.hudIntensity,
    fontStyle = settings.fontStyle,
    fontScope = settings.fontScope,
  )
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
  val primaryTypeface = rememberLauncherTypeface(launcherSettings.fontStyle, bold = false)
  val boldTypeface = rememberLauncherTypeface(launcherSettings.fontStyle, bold = true)
  val plexTypeface = rememberLauncherTypeface(LauncherFontStyle.IBM_PLEX_MONO, bold = false)
  val telemetryTypeface = if (launcherSettings.fontScope == HudFontScope.ALL_HUD) {
    primaryTypeface
  } else {
    plexTypeface
  }
  val styleTokens = buildLauncherStyleTokens(
    settings = launcherSettings,
    canvasTypeface = primaryTypeface,
    canvasBoldTypeface = boldTypeface,
    telemetryTypeface = telemetryTypeface,
  )
  val baseScheme = if (crimsonMonetActive) EdithCrimsonMonetColorScheme else EdithGlassColorScheme
  val colorScheme = baseScheme.copy(
    primary = styleTokens.accent,
    secondary = styleTokens.graphicAccent,
    tertiary = styleTokens.semanticWarn,
  )
  CompositionLocalProvider(
    LocalCrimsonMonetActive provides crimsonMonetActive,
    LocalLauncherStyle provides styleTokens,
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = buildEdithTypography(
        fontStyle = launcherSettings.fontStyle,
        fontScope = launcherSettings.fontScope,
        scale = launcherSettings.fontScale,
      ),
      content = content,
    )
  }
}
