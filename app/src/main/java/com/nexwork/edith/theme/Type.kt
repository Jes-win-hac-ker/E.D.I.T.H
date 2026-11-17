package com.nexwork.edith.theme

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.nexwork.edith.R
import com.nexwork.edith.model.HudFontScope
import com.nexwork.edith.model.LauncherFontStyle

private val IbmPlexMonoFamily = FontFamily(
  Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
  Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
  Font(R.font.ibm_plex_mono_medium, FontWeight.SemiBold),
  Font(R.font.ibm_plex_mono_medium, FontWeight.Bold),
)

private val OrbitronFamily = FontFamily(
  Font(R.font.orbitron_regular, FontWeight.Normal),
  Font(R.font.orbitron_regular, FontWeight.Medium),
  Font(R.font.orbitron_bold, FontWeight.SemiBold),
  Font(R.font.orbitron_bold, FontWeight.Bold),
)

private val HackMonoFamily = FontFamily(
  Font(R.font.hack_regular, FontWeight.Normal),
  Font(R.font.hack_regular, FontWeight.Medium),
  Font(R.font.hack_bold, FontWeight.SemiBold),
  Font(R.font.hack_bold, FontWeight.Bold),
)

private val PixelonFamily = FontFamily(
  Font(R.font.pixelon_regular, FontWeight.Normal),
  Font(R.font.pixelon_regular, FontWeight.Medium),
  Font(R.font.pixelon_regular, FontWeight.SemiBold),
  Font(R.font.pixelon_regular, FontWeight.Bold),
)

private val KogniGearFamily = FontFamily(
  Font(R.font.kognigear_regular, FontWeight.Normal),
  Font(R.font.kognigear_regular, FontWeight.Medium),
  Font(R.font.kognigear_regular, FontWeight.SemiBold),
  Font(R.font.kognigear_regular, FontWeight.Bold),
)

private val EspacionFamily = FontFamily(
  Font(R.font.espacion_regular, FontWeight.Normal),
  Font(R.font.espacion_regular, FontWeight.Medium),
  Font(R.font.espacion_regular, FontWeight.SemiBold),
  Font(R.font.espacion_regular, FontWeight.Bold),
)

fun LauncherFontStyle.toFontFamily(): FontFamily = when (this) {
  LauncherFontStyle.IBM_PLEX_MONO -> IbmPlexMonoFamily
  LauncherFontStyle.ORBITRON -> OrbitronFamily
  LauncherFontStyle.HACK_MONO -> HackMonoFamily
  LauncherFontStyle.PIXELON -> PixelonFamily
  LauncherFontStyle.KOGNIGEAR -> KogniGearFamily
  LauncherFontStyle.ESPACION -> EspacionFamily
}

fun LauncherFontStyle.toTelemetryFontFamily(scope: HudFontScope): FontFamily = when (scope) {
  HudFontScope.ALL_HUD -> toFontFamily()
  HudFontScope.HEADERS_CLOCK_ONLY -> IbmPlexMonoFamily
}

@FontRes
fun LauncherFontStyle.primaryFontResId(): Int = when (this) {
  LauncherFontStyle.IBM_PLEX_MONO -> R.font.ibm_plex_mono_regular
  LauncherFontStyle.ORBITRON -> R.font.orbitron_regular
  LauncherFontStyle.HACK_MONO -> R.font.hack_regular
  LauncherFontStyle.PIXELON -> R.font.pixelon_regular
  LauncherFontStyle.KOGNIGEAR -> R.font.kognigear_regular
  LauncherFontStyle.ESPACION -> R.font.espacion_regular
}

@FontRes
fun LauncherFontStyle.boldFontResId(): Int = when (this) {
  LauncherFontStyle.IBM_PLEX_MONO -> R.font.ibm_plex_mono_medium
  LauncherFontStyle.ORBITRON -> R.font.orbitron_bold
  LauncherFontStyle.HACK_MONO -> R.font.hack_bold
  LauncherFontStyle.PIXELON -> R.font.pixelon_regular
  LauncherFontStyle.KOGNIGEAR -> R.font.kognigear_regular
  LauncherFontStyle.ESPACION -> R.font.espacion_regular
}

fun resolveLauncherTypeface(
  context: Context?,
  style: LauncherFontStyle,
  bold: Boolean = false,
): Typeface? {
  if (context == null) return Typeface.MONOSPACE
  val resId = if (bold) style.boldFontResId() else style.primaryFontResId()
  return runCatching {
    ResourcesCompat.getFont(context, resId)
  }.getOrNull() ?: Typeface.MONOSPACE
}

@Composable
fun rememberLauncherTypeface(
  style: LauncherFontStyle,
  bold: Boolean = false,
): Typeface? {
  val context = LocalContext.current
  return remember(context, style, bold) {
    resolveLauncherTypeface(context, style, bold)
  }
}

fun buildEdithTypography(
  fontStyle: LauncherFontStyle = LauncherFontStyle.IBM_PLEX_MONO,
  fontScope: HudFontScope = HudFontScope.ALL_HUD,
  scale: Float = 1.0f,
): Typography {
  val headerFamily = fontStyle.toFontFamily()
  val bodyFamily = fontStyle.toTelemetryFontFamily(fontScope)
  val s = scale.coerceIn(0.85f, 1.25f)
  return Typography(
    bodyLarge = TextStyle(
      fontFamily = bodyFamily,
      fontWeight = FontWeight.Normal,
      fontSize = (16f * s).sp,
      lineHeight = (24f * s).sp,
      letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
      fontFamily = bodyFamily,
      fontWeight = FontWeight.Normal,
      fontSize = (14f * s).sp,
      lineHeight = (20f * s).sp,
      letterSpacing = 0.25.sp,
    ),
    labelMedium = TextStyle(
      fontFamily = bodyFamily,
      fontWeight = FontWeight.Medium,
      fontSize = (12f * s).sp,
      lineHeight = (16f * s).sp,
      letterSpacing = 0.5.sp,
    ),
    titleMedium = TextStyle(
      fontFamily = headerFamily,
      fontWeight = FontWeight.SemiBold,
      fontSize = (16f * s).sp,
      lineHeight = (22f * s).sp,
      letterSpacing = 0.2.sp,
    ),
  )
}

// Set of Material typography styles to start with
val Typography = buildEdithTypography()
