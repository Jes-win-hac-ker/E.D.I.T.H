package com.nexwork.edith.ui.hud

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.HudLine

private val MinorGrid = HudLine.copy(alpha = 0.045f)
private val MajorGrid = HudLine.copy(alpha = 0.10f)
private val Scanline = Color.Black.copy(alpha = 0.22f)
private val SweepCore = Color.White.copy(alpha = 0.05f)
private val BracketTick = HudLine.copy(alpha = 0.75f)

private data class Corner(val x: Float, val y: Float, val sx: Int, val sy: Int)

/**
 * Stark holographic backdrop: monochrome micro-grid, crimson rail line, CRT scanlines,
 * slow descending sweep band, pulsing crimson bloom, and corner targeting brackets.
 */
@Composable
fun HudBackground(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "hudBg")

  // Sweep band travels top -> bottom every 7s (full traversal, then restart at the top)
  val sweepY by transition.animateFloat(
    initialValue = -0.18f,
    targetValue = 1.18f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 7000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "sweepY",
  )

  // Bloom breathes between 55% and 100% opacity every ~3.4s round trip
  val bloomAlpha by transition.animateFloat(
    initialValue = 0.55f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1700, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "bloomAlpha",
  )

  Canvas(modifier = modifier.fillMaxSize()) {
    val w = size.width
    val h = size.height

    // --- Micro grid (24dp minor, every 4th line major) ---
    val minor = 24.dp.toPx()
    var gx = minor
    var gi = 1
    while (gx < w) {
      val major = gi % 4 == 0
      drawLine(
        color = if (major) MajorGrid else MinorGrid,
        start = Offset(gx, 0f),
        end = Offset(gx, h),
        strokeWidth = if (major) 1.4f else 0.7f,
      )
      gx += minor; gi++
    }
    var gy = minor
    var gj = 1
    while (gy < h) {
      val major = gj % 4 == 0
      drawLine(
        color = if (major) MajorGrid else MinorGrid,
        start = Offset(0f, gy),
        end = Offset(w, gy),
        strokeWidth = if (major) 1.4f else 0.7f,
      )
      gy += minor; gj++
    }

    // --- Crimson rail line on the 4th major column from the right ---
    val railX = w - 4 * minor
    drawLine(
      color = Crimson.copy(alpha = 0.30f),
      start = Offset(railX, 0f),
      end = Offset(railX, h),
      strokeWidth = 5.dp.toPx(),
    )
    drawLine(
      color = Crimson,
      start = Offset(railX, 0f),
      end = Offset(railX, h),
      strokeWidth = 1.4f,
    )

    // --- CRT scanlines ---
    val scanStep = 3.dp.toPx()
    var sy = 0f
    while (sy < h) {
      drawLine(
        color = Scanline,
        start = Offset(0f, sy),
        end = Offset(w, sy),
        strokeWidth = 1f,
      )
      sy += scanStep
    }

    // --- Pulsing crimson bloom anchored to the rail, lower third ---
    val bloomR = 160.dp.toPx()
    val bloomC = Offset(railX, h * 0.72f)
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(Crimson.copy(alpha = 0.16f * bloomAlpha), Color.Transparent),
        center = bloomC,
        radius = bloomR,
      ),
      radius = bloomR,
      center = bloomC,
    )

    // --- Sweep band ---
    val bandH = 130.dp.toPx()
    val cy = sweepY * h
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, SweepCore, Color.Transparent),
        startY = cy - bandH / 2f,
        endY = cy + bandH / 2f,
      ),
      alpha = bloomAlpha,
    )

    // --- Corner targeting brackets (crimson L-marks with silver tick feet) ---
    val arm = 26.dp.toPx()
    val pad = 10.dp.toPx()
    val stroke = 2.2.dp.toPx()
    val corners = listOf(
      Corner(pad, pad, 1, 1),
      Corner(w - pad, pad, -1, 1),
      Corner(pad, h - pad, 1, -1),
      Corner(w - pad, h - pad, -1, -1),
    )
    for (c in corners) {
      val pt = Offset(c.x, c.y)
      drawLine(Crimson, pt, Offset(pt.x + c.sx * arm, pt.y), stroke)
      drawLine(Crimson, pt, Offset(pt.x, pt.y + c.sy * arm), stroke)
      drawLine(
        BracketTick,
        Offset(pt.x + c.sx * arm, pt.y),
        Offset(pt.x + c.sx * (arm + 5.dp.toPx()), pt.y),
        1.2f,
      )
      drawLine(
        BracketTick,
        Offset(pt.x, pt.y + c.sy * arm),
        Offset(pt.x, pt.y + c.sy * (arm + 5.dp.toPx())),
        1.2f,
      )
    }
  }
}
