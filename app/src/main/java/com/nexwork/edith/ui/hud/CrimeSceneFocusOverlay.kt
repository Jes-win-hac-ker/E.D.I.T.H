package com.nexwork.edith.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Depth vignette: darkens the outer field so the deck sits in a pool of focus. No rings, no
 * pulse — the only thing it must do is fall away from the plates.
 */
@Composable
fun CrimeSceneFocusOverlay(
  isOpen: Boolean,
  modifier: Modifier = Modifier,
) {
  val openAnim = remember { Animatable(0f) }

  LaunchedEffect(isOpen) {
    if (isOpen) {
      openAnim.animateTo(1f, tween(600, easing = LinearEasing))
    } else {
      openAnim.animateTo(0f, tween(400, easing = LinearEasing))
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .drawWithCache {
        val w = size.width
        val h = size.height
        val vignetteBrush = Brush.radialGradient(
          colors = listOf(
            Color.Transparent,
            Color(0x77000000),
            Color(0xF004060B),
          ),
          center = Offset(w * 0.5f, h * 0.48f),
          radius = (w * 0.85f).coerceAtLeast(1f),
        )
        onDrawBehind {
          val progress = openAnim.value
          if (progress > 0.001f) {
            drawRect(
              brush = vignetteBrush,
              alpha = progress,
            )
          }
        }
      },
  )
}
