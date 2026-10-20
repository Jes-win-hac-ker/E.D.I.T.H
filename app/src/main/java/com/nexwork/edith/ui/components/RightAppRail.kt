package com.nexwork.edith.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.R
import com.nexwork.edith.theme.GlassBorderHighlight
import com.nexwork.edith.theme.GlassBorderSubtle
import com.nexwork.edith.theme.GlassSurface
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.TextPureWhite

data class RightRailAppItem(
  val label: String,
  val packageName: String,
  @param:DrawableRes val iconRes: Int,
  val launchFallback: (Context) -> Unit,
)

/**
 * Custom right-side app column positioned in the 4-square right margin:
 * Arranged from bottom to top:
 * - Google (at the bottom)
 * - GPay (above Google)
 * - Phone (above GPay)
 * - Camera (above Phone)
 * - WhatsApp (above Camera)
 */
@Composable
fun RightAppRail(
  allApps: List<AppEntry> = emptyList(),
  onLaunchApp: (AppEntry) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  // From top to bottom (so Google is at the bottom):
  // Qwen Code -> Gemini -> WhatsApp -> Camera -> Phone -> GPay -> Google
  val apps = listOf(
    RightRailAppItem(
      label = "Qwen Code",
      packageName = "ai.qwenlm.chat.android",
      iconRes = R.drawable.ic_custom_qwen,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "ai.qwenlm.chat.android") {
          launchOrFallback(ctx, "com.tongyi.intl") {
            runCatching {
              ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.qwenlm.ai/"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              )
            }
          }
        }
      },
    ),
    RightRailAppItem(
      label = "Gemini",
      packageName = "com.google.android.apps.bard",
      iconRes = R.drawable.ic_custom_gemini,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.google.android.apps.bard") {
          launchOrFallback(ctx, "com.google.android.apps.googleassistant") {
            runCatching {
              ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              )
            }
          }
        }
      },
    ),
    RightRailAppItem(
      label = "WhatsApp",
      packageName = "com.whatsapp",
      iconRes = R.drawable.ic_custom_whatsapp,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.whatsapp") {
          runCatching {
            ctx.startActivity(
              Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        }
      },
    ),
    RightRailAppItem(
      label = "Camera",
      packageName = "com.motorola.camera3",
      iconRes = R.drawable.ic_custom_camera,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.motorola.camera3") {
          runCatching {
            ctx.startActivity(
              Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        }
      },
    ),
    RightRailAppItem(
      label = "Phone",
      packageName = "com.google.android.dialer",
      iconRes = R.drawable.ic_custom_phone,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.google.android.dialer") {
          runCatching {
            ctx.startActivity(
              Intent(Intent.ACTION_DIAL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        }
      },
    ),
    RightRailAppItem(
      label = "GPay",
      packageName = "com.google.android.apps.nbu.paisa.user",
      iconRes = R.drawable.ic_custom_gpay,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.google.android.apps.nbu.paisa.user") {
          launchOrFallback(ctx, "com.google.android.apps.walletnfcrel")
        }
      },
    ),
    RightRailAppItem(
      label = "Google",
      packageName = "com.google.android.googlequicksearchbox",
      iconRes = R.drawable.ic_custom_google,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.google.android.googlequicksearchbox") {
          runCatching {
            ctx.startActivity(
              Intent(Intent.ACTION_WEB_SEARCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        }
      },
    ),
  )

  Box(
    modifier =
      modifier
        .fillMaxHeight()
        .navigationBarsPadding(),
    contentAlignment = Alignment.Center,
  ) {
    // 1. Centered Vertical App Column aligned with dial
    Column(
      modifier =
        Modifier
          .align(Alignment.Center)
          .padding(horizontal = 8.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      apps.forEachIndexed { index, app ->
        CustomAppItem(
          app = app,
          onClick = { app.launchFallback(context) },
        )
        if (index < apps.lastIndex) {
          Spacer(modifier = Modifier.height(10.dp))
        }
      }
    }

    // 2. Continuous Gesture Multi-Function Deck with visible negative space from app list
    Box(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 24.dp),
    ) {
      MultiFunctionGestureDeck(
        allApps = allApps,
        onLaunchApp = onLaunchApp,
      )
    }
  }
}

/**
 * Sleek Monochrome Liquid Glass Multi-Function Button (MFB):
 * Positioned cleanly at the bottom of the right app rail:
 * 1. 50dp squircle matching the app icons footprint.
 * 2. Multi-stop specular border with specular top meniscus highlight.
 * 3. Inner ambient radial bloom.
 * 4. Precision HUD reticle glyph (center ring with 4 cardinal orbital ticks and core dot).
 */
@Composable
fun MultiFunctionButton(
  onClick: () -> Unit = {},
  modifier: Modifier = Modifier,
  size: Dp = 50.dp,
) {
  val cornerRadius = size * 0.308f

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
            Color(0x2838BDF8),
          ),
          start = Offset(0f, 0f),
          end = Offset.Infinite,
        )
      )
      .border(
        width = 1.dp,
        brush = Brush.linearGradient(
          colors = listOf(
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
      )
      .clickable { onClick() },
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

    // 2. Inner liquid fluid ambient radial bloom
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

    // 3. Precision HUD Multi-Function Reticle Glyph
    Canvas(modifier = Modifier.size(size * 0.44f)) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val strokeW = 1.25.dp.toPx()

      // Center ring
      val ringRadius = 4.5.dp.toPx()
      drawCircle(
        color = Color.White.copy(alpha = 0.92f),
        radius = ringRadius,
        center = Offset(cx, cy),
        style = Stroke(width = strokeW),
      )

      // Center glowing core dot
      drawCircle(
        color = IceGlowAccent,
        radius = 1.6.dp.toPx(),
        center = Offset(cx, cy),
      )

      // 4 Cardinal precision ticks
      val tickInner = ringRadius + 2.0.dp.toPx()
      val tickOuter = tickInner + 3.2.dp.toPx()

      // Top
      drawLine(
        color = Color.White.copy(alpha = 0.85f),
        start = Offset(cx, cy - tickInner),
        end = Offset(cx, cy - tickOuter),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
      )
      // Bottom
      drawLine(
        color = Color.White.copy(alpha = 0.85f),
        start = Offset(cx, cy + tickInner),
        end = Offset(cx, cy + tickOuter),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
      )
      // Left
      drawLine(
        color = Color.White.copy(alpha = 0.85f),
        start = Offset(cx - tickInner, cy),
        end = Offset(cx - tickOuter, cy),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
      )
      // Right
      drawLine(
        color = Color.White.copy(alpha = 0.85f),
        start = Offset(cx + tickInner, cy),
        end = Offset(cx + tickOuter, cy),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
      )
    }
  }
}

@Composable
private fun CustomAppItem(
  app: RightRailAppItem,
  onClick: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .clickable { onClick() }
        .padding(vertical = 4.dp),
  ) {
    LiquidGlassAppIcon(
      packageName = app.packageName,
      label = app.label,
      customIconResOverride = app.iconRes,
      size = 52.dp,
    )

    Spacer(modifier = Modifier.height(5.dp))

    Text(
      text = app.label,
      color = TextPureWhite.copy(alpha = 0.92f),
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      fontFamily = FontFamily.SansSerif,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
    )
  }
}

private fun launchOrFallback(
  context: Context,
  packageName: String,
  onFallback: () -> Unit = {},
) {
  val pm = context.packageManager
  val launchIntent = pm.getLaunchIntentForPackage(packageName)
  if (launchIntent != null) {
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launchIntent)
  } else {
    onFallback()
  }
}
