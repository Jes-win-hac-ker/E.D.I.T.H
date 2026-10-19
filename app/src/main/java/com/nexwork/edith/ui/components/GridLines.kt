package com.nexwork.edith.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Very fine layout grid lines with real-time 120Hz touch reactivity:
 * - Touch crosshair trace: Grid lines under the finger illuminate dynamically.
 * - Radial touch aura: Ambient glow and illuminated intersection nodes around the touch point.
 * - Shockwave ripple: Tapping pulses a wave outward through the grid cells.
 * - Solid line on the 4th major square line from the right of the phone.
 */
@Composable
fun GridLines(
  modifier: Modifier = Modifier,
  gridSize: Dp = 8.dp,
  majorStep: Int = 4, // Major line every 32dp
  minorLineColor: Color = Color.White.copy(alpha = 0.08f),
  majorLineColor: Color = Color.White.copy(alpha = 0.22f),
  verticalMajorLineFromRightIndex: Int? = 4, // 4th major square line from the right
  customLineWidth: Dp = 2.dp,
  customLineColor: Color = Color.White,
  reactiveColor: Color = Color(0xFF38BDF8),
) {
  val scope = rememberCoroutineScope()
  var touchPos by remember { mutableStateOf<Offset?>(null) }
  val touchAlpha = remember { Animatable(0f) }

  var rippleCenter by remember { mutableStateOf<Offset?>(null) }
  val rippleRadius = remember { Animatable(0f) }
  val rippleAlpha = remember { Animatable(0f) }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            touchPos = down.position
            rippleCenter = down.position

            scope.launch {
              touchAlpha.animateTo(1f, animationSpec = tween(80))
            }

            scope.launch {
              rippleAlpha.snapTo(0.6f)
              rippleRadius.snapTo(10f)
              coroutineScope {
                launch {
                  rippleRadius.animateTo(
                    targetValue = 420f,
                    animationSpec = tween(500, easing = FastOutSlowInEasing),
                  )
                }
                launch {
                  rippleAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(500),
                  )
                }
              }
            }

            // Track drag gestures across the grid
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull() ?: break
              if (!change.pressed) {
                break
              }
              touchPos = change.position
            }

            // Finger released
            scope.launch {
              touchAlpha.animateTo(0f, animationSpec = tween(350))
              touchPos = null
            }
          }
        }
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val stepPx = gridSize.toPx()
      val width = size.width
      val height = size.height

      if (stepPx <= 0f) return@Canvas

      val cols = (width / stepPx).toInt() + 1
      val rows = (height / stepPx).toInt() + 1

      val currentTouch = touchPos
      val currentAlpha = touchAlpha.value
      val currentRipplePos = rippleCenter
      val currentRippleR = rippleRadius.value
      val currentRippleA = rippleAlpha.value

      // 1. Base Vertical Grid Lines
      for (i in 0..cols) {
        val x = i * stepPx
        if (x > width) break
        val isMajor = (i % majorStep == 0)
        drawLine(
          color = if (isMajor) majorLineColor else minorLineColor,
          start = Offset(x, 0f),
          end = Offset(x, height),
          strokeWidth = if (isMajor) 0.8.dp.toPx() else 0.5.dp.toPx(),
        )
      }

      // 2. Base Horizontal Grid Lines
      for (j in 0..rows) {
        val y = j * stepPx
        if (y > height) break
        val isMajor = (j % majorStep == 0)
        drawLine(
          color = if (isMajor) majorLineColor else minorLineColor,
          start = Offset(0f, y),
          end = Offset(width, y),
          strokeWidth = if (isMajor) 0.8.dp.toPx() else 0.5.dp.toPx(),
        )
      }

      // 3. Reactive Touch Crosshair Trace & Ambient Glow
      if (currentTouch != null && currentAlpha > 0.01f) {
        val tx = currentTouch.x
        val ty = currentTouch.y

        // Nearest vertical and horizontal grid lines
        val snappedCol = (tx / stepPx).roundToInt()
        val snappedRow = (ty / stepPx).roundToInt()
        val snapX = snappedCol * stepPx
        val snapY = snappedRow * stepPx

        // Soft radial glow aura around the touch
        drawCircle(
          brush =
            Brush.radialGradient(
              colors =
                listOf(
                  reactiveColor.copy(alpha = 0.22f * currentAlpha),
                  reactiveColor.copy(alpha = 0.06f * currentAlpha),
                  Color.Transparent,
                ),
              center = currentTouch,
              radius = 110.dp.toPx(),
            ),
          radius = 110.dp.toPx(),
          center = currentTouch,
        )

        // Reactive Vertical Trace Line
        if (snapX in 0f..width) {
          drawLine(
            brush =
              Brush.verticalGradient(
                colors =
                  listOf(
                    Color.Transparent,
                    reactiveColor.copy(alpha = 0.40f * currentAlpha),
                    Color.White.copy(alpha = 0.90f * currentAlpha),
                    reactiveColor.copy(alpha = 0.40f * currentAlpha),
                    Color.Transparent,
                  ),
                startY = (ty - 160.dp.toPx()).coerceAtLeast(0f),
                endY = (ty + 160.dp.toPx()).coerceAtMost(height),
              ),
            start = Offset(snapX, 0f),
            end = Offset(snapX, height),
            strokeWidth = 1.3.dp.toPx(),
          )
        }

        // Reactive Horizontal Trace Line
        if (snapY in 0f..height) {
          drawLine(
            brush =
              Brush.horizontalGradient(
                colors =
                  listOf(
                    Color.Transparent,
                    reactiveColor.copy(alpha = 0.40f * currentAlpha),
                    Color.White.copy(alpha = 0.90f * currentAlpha),
                    reactiveColor.copy(alpha = 0.40f * currentAlpha),
                    Color.Transparent,
                  ),
                startX = (tx - 160.dp.toPx()).coerceAtLeast(0f),
                endX = (tx + 160.dp.toPx()).coerceAtMost(width),
              ),
            start = Offset(0f, snapY),
            end = Offset(width, snapY),
            strokeWidth = 1.3.dp.toPx(),
          )
        }

        // Illuminated intersection nodes around the touch
        val searchRadiusCols = (80.dp.toPx() / stepPx).toInt()
        val maxDist = 80.dp.toPx()
        for (ci in (snappedCol - searchRadiusCols)..(snappedCol + searchRadiusCols)) {
          val ix = ci * stepPx
          if (ix < 0f || ix > width) continue
          for (rj in (snappedRow - searchRadiusCols)..(snappedRow + searchRadiusCols)) {
            val iy = rj * stepPx
            if (iy < 0f || iy > height) continue
            val dist = hypot(ix - tx, iy - ty)
            if (dist < maxDist) {
              val pointAlpha = (1f - dist / maxDist) * currentAlpha
              drawCircle(
                color = Color.White.copy(alpha = pointAlpha * 0.75f),
                radius = 1.2.dp.toPx(),
                center = Offset(ix, iy),
              )
            }
          }
        }
      }

      // 4. Reactive Shockwave Ripple on Tap
      if (currentRipplePos != null && currentRippleA > 0.01f && currentRippleR > 0f) {
        drawCircle(
          color = reactiveColor.copy(alpha = currentRippleA * 0.5f),
          radius = currentRippleR,
          center = currentRipplePos,
          style = Stroke(width = 1.6.dp.toPx()),
        )
      }

      // 5. Solid Line on the 4th Major Square Line from the Right of the Phone
      if (verticalMajorLineFromRightIndex != null && verticalMajorLineFromRightIndex > 0) {
        val lastLineIndex = (width / stepPx).toInt()
        val highestMajor = (lastLineIndex / majorStep) * majorStep
        val targetIndex = highestMajor - (verticalMajorLineFromRightIndex - 1) * majorStep
        if (targetIndex >= 0) {
          val targetX = targetIndex * stepPx

          // Subtle energetic glow boost if touch is nearby
          val touchNearDist =
            if (currentTouch != null) abs(currentTouch.x - targetX) else 999f
          val nearAlphaBoost =
            if (touchNearDist < 60.dp.toPx()) (1f - touchNearDist / 60.dp.toPx()) * currentAlpha
            else 0f

          if (nearAlphaBoost > 0.05f) {
            drawLine(
              color = reactiveColor.copy(alpha = nearAlphaBoost * 0.4f),
              start = Offset(targetX, 0f),
              end = Offset(targetX, height),
              strokeWidth = customLineWidth.toPx() + 4.dp.toPx(),
            )
          }

          drawLine(
            color = customLineColor,
            start = Offset(targetX, 0f),
            end = Offset(targetX, height),
            strokeWidth = customLineWidth.toPx(),
          )
        }
      }
    }
  }
}
