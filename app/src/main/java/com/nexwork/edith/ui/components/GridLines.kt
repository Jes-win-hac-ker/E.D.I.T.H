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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Very fine layout grid lines with real-time 120Hz touch reactivity:
 * - Center illumination: High-contrast bright grid in the center with smooth vignette fade towards top and sides.
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
  minorLineColor: Color = Color.White.copy(alpha = 0.16f),
  majorLineColor: Color = Color.White.copy(alpha = 0.40f),
  verticalMajorLineFromRightIndex: Int? = 4, // 4th major square line from the right
  customLineWidth: Dp = 2.dp,
  customLineColor: Color = Color.White,
  reactiveColor: Color = Color(0xFF38BDF8),
  railGapHeight: Dp? = 290.dp,
  solidLineAlpha: Float = 1.0f,
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

      val centerX = width / 2f
      val centerY = height / 2f

      // 0. Ambient Center Radial Bloom
      drawCircle(
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                Color(0x1838BDF8),
                Color(0x0638BDF8),
                Color.Transparent,
              ),
            center = Offset(centerX, centerY),
            radius = width * 0.70f,
          ),
        center = Offset(centerX, centerY),
        radius = width * 0.70f,
      )

      val halfPi = (Math.PI / 2.0).toFloat()

      // 1. Base Vertical Grid Lines (bright in center, fading to top/bottom and sides)
      for (i in 0..cols) {
        val x = i * stepPx
        if (x > width) break
        val isMajor = (i % majorStep == 0)

        // Horizontal attenuation (fades towards left and right sides)
        val dxNorm = (abs(x - centerX) / centerX).coerceIn(0f, 1f)
        val hFactor = cos(dxNorm * halfPi).coerceAtLeast(0f)
        if (hFactor < 0.02f) continue

        val baseColor = if (isMajor) majorLineColor else minorLineColor
        val maxAlpha = baseColor.alpha * hFactor
        val strokeW = if (isMajor) 0.9.dp.toPx() else 0.5.dp.toPx()

        drawLine(
          brush =
            Brush.verticalGradient(
              0.00f to Color.Transparent,
              0.15f to baseColor.copy(alpha = maxAlpha * 0.15f),
              0.35f to baseColor.copy(alpha = maxAlpha * 0.70f),
              0.50f to baseColor.copy(alpha = maxAlpha),
              0.65f to baseColor.copy(alpha = maxAlpha * 0.70f),
              0.85f to baseColor.copy(alpha = maxAlpha * 0.15f),
              1.00f to Color.Transparent,
              startY = 0f,
              endY = height,
            ),
          start = Offset(x, 0f),
          end = Offset(x, height),
          strokeWidth = strokeW,
        )
      }

      // 2. Base Horizontal Grid Lines (bright in center, fading to top/bottom and sides)
      for (j in 0..rows) {
        val y = j * stepPx
        if (y > height) break
        val isMajor = (j % majorStep == 0)

        // Vertical attenuation (fades towards top and bottom)
        val dyNorm = (abs(y - centerY) / centerY).coerceIn(0f, 1f)
        val vFactor = cos(dyNorm * halfPi).coerceAtLeast(0f)
        if (vFactor < 0.02f) continue

        val baseColor = if (isMajor) majorLineColor else minorLineColor
        val maxAlpha = baseColor.alpha * vFactor
        val strokeW = if (isMajor) 0.9.dp.toPx() else 0.5.dp.toPx()

        drawLine(
          brush =
            Brush.horizontalGradient(
              0.00f to Color.Transparent,
              0.15f to baseColor.copy(alpha = maxAlpha * 0.15f),
              0.35f to baseColor.copy(alpha = maxAlpha * 0.70f),
              0.50f to baseColor.copy(alpha = maxAlpha),
              0.65f to baseColor.copy(alpha = maxAlpha * 0.70f),
              0.85f to baseColor.copy(alpha = maxAlpha * 0.15f),
              1.00f to Color.Transparent,
              startX = 0f,
              endX = width,
            ),
          start = Offset(0f, y),
          end = Offset(width, y),
          strokeWidth = strokeW,
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

          val effectiveAlpha = solidLineAlpha.coerceIn(0f, 1f)
          val effectiveLineColor = customLineColor.copy(alpha = customLineColor.alpha * effectiveAlpha)
          val effectiveGlowBoost = nearAlphaBoost * effectiveAlpha

          val cy = height / 2f
          val railH = railGapHeight?.toPx() ?: 0f
          val hasGap = railGapHeight != null && railH > 0f
          val gapTop = if (hasGap) cy - railH / 2f else 0f
          val gapBottom = if (hasGap) cy + railH / 2f + 42.dp.toPx() else 0f

          if (effectiveGlowBoost > 0.05f) {
            val glowWidth = customLineWidth.toPx() + 4.dp.toPx()
            if (hasGap) {
              if (gapTop > 0f) {
                drawLine(
                  brush = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.08f to reactiveColor.copy(alpha = effectiveGlowBoost * 0.4f),
                    1.00f to reactiveColor.copy(alpha = effectiveGlowBoost * 0.4f),
                    startY = 0f,
                    endY = gapTop,
                  ),
                  start = Offset(targetX, 0f),
                  end = Offset(targetX, gapTop),
                  strokeWidth = glowWidth,
                )
              }
              if (gapBottom < height) {
                drawLine(
                  brush = Brush.verticalGradient(
                    0.00f to reactiveColor.copy(alpha = effectiveGlowBoost * 0.4f),
                    0.92f to reactiveColor.copy(alpha = effectiveGlowBoost * 0.4f),
                    1.00f to Color.Transparent,
                    startY = gapBottom,
                    endY = height,
                  ),
                  start = Offset(targetX, gapBottom),
                  end = Offset(targetX, height),
                  strokeWidth = glowWidth,
                )
              }
            } else {
              drawLine(
                brush = Brush.verticalGradient(
                  0.00f to Color.Transparent,
                  0.08f to reactiveColor.copy(alpha = effectiveGlowBoost * 0.4f),
                  0.92f to reactiveColor.copy(alpha = effectiveGlowBoost * 0.4f),
                  1.00f to Color.Transparent,
                ),
                start = Offset(targetX, 0f),
                end = Offset(targetX, height),
                strokeWidth = glowWidth,
              )
            }
          }

          if (effectiveAlpha > 0.01f) {
            if (hasGap) {
              // Segment 1: Above Energy Rail docking into top node
              if (gapTop > 0f) {
                drawLine(
                  brush = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.06f to effectiveLineColor,
                    1.00f to effectiveLineColor,
                    startY = 0f,
                    endY = gapTop,
                  ),
                  start = Offset(targetX, 0f),
                  end = Offset(targetX, gapTop),
                  strokeWidth = customLineWidth.toPx(),
                )
              }
              // Segment 2: Below Telemetry Lozenge running down to screen bottom
              if (gapBottom < height) {
                drawLine(
                  brush = Brush.verticalGradient(
                    0.00f to effectiveLineColor,
                    0.94f to effectiveLineColor,
                    1.00f to Color.Transparent,
                    startY = gapBottom,
                    endY = height,
                  ),
                  start = Offset(targetX, gapBottom),
                  end = Offset(targetX, height),
                  strokeWidth = customLineWidth.toPx(),
                )
              }
            } else {
              drawLine(
                brush = Brush.verticalGradient(
                  0.00f to Color.Transparent,
                  0.06f to effectiveLineColor,
                  0.94f to effectiveLineColor,
                  1.00f to Color.Transparent,
                ),
                start = Offset(targetX, 0f),
                end = Offset(targetX, height),
                strokeWidth = customLineWidth.toPx(),
              )
            }
          }
        }
      }
    }
  }
}
