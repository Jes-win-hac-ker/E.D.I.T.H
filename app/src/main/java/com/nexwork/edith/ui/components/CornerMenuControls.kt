package com.nexwork.edith.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nexwork.edith.theme.IceGlowAccent
import kotlin.math.roundToInt

/**
 * Step One: Corner Menu Controls
 * 1. Corner MFB anchor button (50dp squircle with precision reticle glyph).
 * 2. Sliding App List icon (50dp squircle with 3 vertical dots + 3 horizontal bars)
 *    that slides out to the left from beneath the MFB icon.
 * 3. Triggered exclusively via Press & Hold (Long Press).
 */
@Composable
fun CornerMenuControls(
  isOpen: Boolean,
  onOpen: () -> Unit,
  onAppListClick: () -> Unit = {},
  modifier: Modifier = Modifier,
  size: Dp = 50.dp,
) {
  val view = LocalView.current

  // Slide animation progress (0f = hidden under MFB, 1f = fully slid to the left)
  val slideProgress by animateFloatAsState(
    targetValue = if (isOpen) 1f else 0f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessMediumLow,
    ),
    label = "cornerSlideProgress",
  )

  // Distance the sliding icon travels to the left (50dp button size + 12dp spacing)
  val slideDistanceDp = 62.dp

  Box(
    modifier = modifier.size(size),
    contentAlignment = Alignment.Center,
  ) {
    // 1. Sliding App List Glass Icon (3 vertical dots + 3 horizontal lines)
    if (slideProgress > 0.005f) {
      Box(
        modifier = Modifier
          .zIndex(1f) // Beneath MFB icon (zIndex 2f) so it literally emerges from beneath
          .offset {
            val offsetX = -slideDistanceDp.toPx() * slideProgress
            IntOffset(offsetX.roundToInt(), 0)
          }
          .size(size)
          .alpha(slideProgress)
          .scale(0.6f + 0.4f * slideProgress)
          .clip(RoundedCornerShape(size * 0.308f))
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
            width = 1.0.dp,
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
            shape = RoundedCornerShape(size * 0.308f),
          )
          .clickable {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onAppListClick()
          },
        contentAlignment = Alignment.Center,
      ) {
        // Specular meniscus highlight along top edge
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(size * 0.31f)
            .align(Alignment.TopCenter)
            .clip(RoundedCornerShape(topStart = size * 0.308f, topEnd = size * 0.308f))
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

        // Precision HUD List Glyph (3 vertical dots on left, 3 horizontal bars on right)
        Canvas(modifier = Modifier.size(size * 0.46f)) {
          val cx = this.size.width / 2f
          val cy = this.size.height / 2f

          val dotRadius = 1.8.dp.toPx()
          val lineStroke = 2.0.dp.toPx()
          val spacing = 5.8.dp.toPx()

          val dotX = cx - 7.0.dp.toPx()
          val lineStartX = cx - 1.2.dp.toPx()
          val lineEndX = cx + 8.0.dp.toPx()

          for (i in -1..1) {
            val rowY = cy + i * spacing

            // Left vertical dot
            drawCircle(
              color = Color.White.copy(alpha = 0.95f),
              radius = dotRadius,
              center = Offset(dotX, rowY),
            )

            // Right horizontal bar
            drawLine(
              color = Color.White.copy(alpha = 0.90f),
              start = Offset(lineStartX, rowY),
              end = Offset(lineEndX, rowY),
              strokeWidth = lineStroke,
              cap = StrokeCap.Round,
            )
          }
        }
      }
    }

    // 2. Corner Multi-Function Button (Anchor, zIndex 2f)
    Box(
      modifier = Modifier
        .zIndex(2f)
        .size(size)
        .clip(RoundedCornerShape(size * 0.308f))
        .background(
          Brush.linearGradient(
            colors = listOf(
              Color(0x38FFFFFF),
              Color(0x14FFFFFF),
              Color(0x0E0F172A),
              if (isOpen) Color(0x4038BDF8) else Color(0x2838BDF8),
            ),
            start = Offset(0f, 0f),
            end = Offset.Infinite,
          )
        )
        .border(
          width = if (isOpen) 1.2.dp else 1.0.dp,
          brush = Brush.linearGradient(
            colors = if (isOpen) listOf(
              Color(0xFF38BDF8),
              Color(0x8038BDF8),
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
          shape = RoundedCornerShape(size * 0.308f),
        )
        .pointerInput(Unit) {
          detectTapGestures(
            onLongPress = {
              view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
              onOpen()
            },
          )
        },
      contentAlignment = Alignment.Center,
    ) {
      // Specular meniscus highlight at top of squircle
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(size * 0.31f)
          .align(Alignment.TopCenter)
          .clip(RoundedCornerShape(topStart = size * 0.308f, topEnd = size * 0.308f))
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

      // Inner liquid fluid ambient radial bloom
      Box(
        modifier = Modifier
          .size(size * 0.69f)
          .clip(RoundedCornerShape((size * 0.308f) * 0.75f))
          .background(
            Brush.radialGradient(
              colors = listOf(
                if (isOpen) Color(0x4538BDF8) else Color(0x3338BDF8),
                Color.Transparent,
              )
            )
          )
      )

      // Precision HUD Multi-Function Reticle Glyph
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
          radius = if (isOpen) 2.2.dp.toPx() else 1.6.dp.toPx(),
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
}
