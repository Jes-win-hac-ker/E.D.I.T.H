package com.nexwork.edith.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nexwork.edith.model.LauncherFontStyle

fun LauncherFontStyle.toFontFamily(): FontFamily = when (this) {
  LauncherFontStyle.DEFAULT -> FontFamily.Default
  LauncherFontStyle.MONOSPACE -> FontFamily.Monospace
  LauncherFontStyle.CLEAN_SANS -> FontFamily.SansSerif
}

fun buildEdithTypography(
  fontStyle: LauncherFontStyle = LauncherFontStyle.DEFAULT,
  scale: Float = 1.0f,
): Typography {
  val family = fontStyle.toFontFamily()
  val s = scale.coerceIn(0.85f, 1.25f)
  return Typography(
    bodyLarge = TextStyle(
      fontFamily = family,
      fontWeight = FontWeight.Normal,
      fontSize = (16f * s).sp,
      lineHeight = (24f * s).sp,
      letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
      fontFamily = family,
      fontWeight = FontWeight.Normal,
      fontSize = (14f * s).sp,
      lineHeight = (20f * s).sp,
      letterSpacing = 0.25.sp,
    ),
    labelMedium = TextStyle(
      fontFamily = family,
      fontWeight = FontWeight.Medium,
      fontSize = (12f * s).sp,
      lineHeight = (16f * s).sp,
      letterSpacing = 0.5.sp,
    ),
    titleMedium = TextStyle(
      fontFamily = family,
      fontWeight = FontWeight.SemiBold,
      fontSize = (16f * s).sp,
      lineHeight = (22f * s).sp,
      letterSpacing = 0.2.sp,
    ),
  )
}

// Set of Material typography styles to start with
val Typography = buildEdithTypography()
