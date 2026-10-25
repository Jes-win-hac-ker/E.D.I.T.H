package com.nexwork.edith.ui.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.HudInk
import com.nexwork.edith.theme.HudVoid
import kotlin.random.Random

private data class Star(val x: Float, val y: Float, val r: Float, val a: Float, val tw: Int)

/**
 * Deep-space backdrop: near-black void, slow-drifting blurry nebula blobs
 * (cold indigo + one dim crimson ember) and a faint twinkling starfield.
 * Everything stays very dark so HUD line work reads on top.
 */
@Composable
fun HudSpaceCanvas(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "space")

  // Very slow nebula drift — 40s one-way, ping-pong
  val drift by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 40_000, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "drift",
  )

  // Ambient breathing of the crimson ember
  val ember by transition.animateFloat(
    initialValue = 0.6f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 6_000, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "ember",
  )

  // Stable starfield, generated once
  val stars = remember {
    val rnd = Random(0xE01432)
    List(140) {
      Star(
        x = rnd.nextFloat(),
        y = rnd.nextFloat(),
        r = 0.7f + rnd.nextFloat() * 1.9f,
        a = 0.30f + rnd.nextFloat() * 0.60f,
        tw = rnd.nextInt(4),
      )
    }
  }

  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height

    // Base void gradient
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(HudVoid, HudInk, HudVoid),
      ),
    )

    // Cold indigo nebula — two overlapping blurry masses, drifting
    val dx = (drift - 0.5f) * 40.dp.toPx()
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(Color(0xFF1B2A55).copy(alpha = 0.85f), Color.Transparent),
        center = Offset(w * 0.28f + dx, h * 0.22f),
        radius = w * 0.85f,
      ),
      radius = w * 0.85f,
      center = Offset(w * 0.28f + dx, h * 0.22f),
    )
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(Color(0xFF142046).copy(alpha = 0.90f), Color.Transparent),
        center = Offset(w * 0.78f - dx, h * 0.62f),
        radius = w * 0.95f,
      ),
      radius = w * 0.95f,
      center = Offset(w * 0.78f - dx, h * 0.62f),
    )

    // Dim crimson ember, lower-left — the one warm mass
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(Crimson.copy(alpha = 0.20f * ember), Color.Transparent),
        center = Offset(w * 0.18f, h * 0.86f - dx * 0.5f),
        radius = w * 0.62f,
      ),
      radius = w * 0.62f,
      center = Offset(w * 0.18f, h * 0.86f - dx * 0.5f),
    )

    // Starfield with per-star twinkle phase from the drift cycle
    for (s in stars) {
      val phase = (drift * (2f + s.tw) + s.x) % 1f
      val twinkle = 0.55f + 0.45f * kotlin.math.sin(phase * 6.2832f)
      drawCircle(
        color = Color.White.copy(alpha = s.a * twinkle),
        radius = s.r.dp.toPx(),
        center = Offset(s.x * w, s.y * h),
      )
    }
  }
}
