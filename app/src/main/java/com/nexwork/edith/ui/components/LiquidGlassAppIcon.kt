package com.nexwork.edith.ui.components

import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.ObsidianBgTop
import com.nexwork.edith.theme.TextPureWhite

/**
 * Universal Liquid Glass Squircle Icon:
 * Unifies all icons across the launcher to use the signature liquid glass squircle aesthetic:
 * 1. Linear gradient backdrop (0x38FFFFFF, 0x14FFFFFF, 0x0E0F172A, 0x2838BDF8).
 * 2. Multi-stop specular border (0x99FFFFFF, 0x4038BDF8, 0x18FFFFFF, 0x3538BDF8, 0x70FFFFFF).
 * 3. Specular meniscus top highlight with vertical gradient.
 * 4. Inner ambient fluid radial bloom.
 * 5. Monochrome vector icon (custom vector or Android 14 monochrome layer) or desaturated monochrome silhouette.
 */
@Composable
fun LiquidGlassAppIcon(
  packageName: String,
  label: String,
  icon: Drawable? = null,
  iconBitmapOverride: androidx.compose.ui.graphics.ImageBitmap? = null,
  @DrawableRes customIconResOverride: Int? = null,
  size: Dp = 52.dp,
  isPrimaryCandidate: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val cornerRadius = remember(size) { size * 0.308f }
  val customIconRes = remember(packageName, customIconResOverride) {
    customIconResOverride ?: CustomAppIcons.getCustomIconRes(packageName)
  }

  val monochromeDrawable = remember(packageName, icon, customIconRes) {
    if (customIconRes == null) CustomAppIcons.getMonochromeDrawable(icon) else null
  }

  val monochromeBitmap = remember(monochromeDrawable) {
    monochromeDrawable?.toImageBitmapSafe()
  }

  val fallbackBitmap = remember(packageName, icon, customIconRes, monochromeDrawable, iconBitmapOverride) {
    if (customIconRes == null && monochromeDrawable == null) {
      icon?.toImageBitmapSafe() ?: iconBitmapOverride
    } else null
  }

  val desaturatedFilter = remember {
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
  }

  Box(
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(cornerRadius))
      .background(
        Brush.linearGradient(
          colors = listOf(
            Color(0x38FFFFFF),
            Color(0x14FFFFFF),
            Color(0x0E0F172A),
            if (isPrimaryCandidate) Color(0x4538BDF8) else Color(0x2838BDF8),
          ),
          start = Offset(0f, 0f),
          end = Offset.Infinite,
        )
      )
      .border(
        width = if (isPrimaryCandidate) 1.4.dp else 1.dp,
        brush = Brush.linearGradient(
          colors = if (isPrimaryCandidate) listOf(
            Color(0xFF38BDF8),
            Color(0x8038BDF8),
            Color(0x4038BDF8),
            Color(0xFF38BDF8),
          ) else listOf(
            Color(0x99FFFFFF),
            Color(0x4038BDF8),
            Color(0x18FFFFFF),
            Color(0x3538BDF8),
            Color(0x70FFFFFF),
          ),
          start = Offset(0f, 0f),
          end = Offset.Infinite,
        ),
        shape = RoundedCornerShape(cornerRadius),
      ),
    contentAlignment = Alignment.Center,
  ) {
    // 1. Specular meniscus highlight at top of squircle
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(size * 0.31f)
        .align(Alignment.TopCenter)
        .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color(0x55FFFFFF),
              Color(0x18FFFFFF),
              Color.Transparent,
            )
          )
        )
    )

    // 2. Primary candidate indicator dot
    if (isPrimaryCandidate) {
      Box(
        modifier = Modifier
          .padding(top = 4.dp, end = 4.dp)
          .align(Alignment.TopEnd)
          .size(5.dp)
          .clip(CircleShape)
          .background(IceGlowAccent)
      )
    }

    // 3. Inner liquid fluid ambient radial bloom
    Box(
      modifier = Modifier
        .size(size * 0.69f)
        .clip(RoundedCornerShape(cornerRadius * 0.75f))
        .background(
          Brush.radialGradient(
            colors = listOf(
              Color(0x3338BDF8),
              Color.Transparent,
            )
          )
        )
    )

    // 4. Center icon content: custom white vector, Android 14 monochrome layer, or desaturated silhouette
    val innerIconSize = size * 0.52f
    when {
      customIconRes != null -> {
        Icon(
          painter = painterResource(id = customIconRes),
          contentDescription = label,
          tint = TextPureWhite,
          modifier = Modifier.size(innerIconSize),
        )
      }
      monochromeBitmap != null -> {
        Image(
          bitmap = monochromeBitmap,
          contentDescription = label,
          colorFilter = ColorFilter.tint(TextPureWhite),
          modifier = Modifier.size(innerIconSize),
        )
      }
      fallbackBitmap != null -> {
        Image(
          bitmap = fallbackBitmap,
          contentDescription = label,
          colorFilter = desaturatedFilter,
          modifier = Modifier.size(innerIconSize + 2.dp),
        )
      }
      else -> {
        Text(
          text = label.take(1).uppercase(),
          color = IceGlowAccent,
          fontSize = (size.value * 0.33f).sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

/** Ice-blue unread-notification dot with an obsidian keyline ring. */
@Composable
fun NotificationBadgeDot(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .size(12.dp)
      .clip(CircleShape)
      .background(IceGlowAccent)
      .border(1.5.dp, ObsidianBgTop, CircleShape),
  )
}
