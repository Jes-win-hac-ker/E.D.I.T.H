package com.nexwork.edith.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Concept 1: The "Vertical Energy Rail" (Architectural Grid Integration).
 *
 * Implements a pure Monochrome Obsidian & White Liquid Glass aesthetic:
 * 1. Integrated directly onto the solid vertical guideline (the 4th major grid line from the right).
 * 2. Precision capillary glass tube isolating the vertical bore with an obsidian dark frosted base.
 * 3. Pure luminous white liquid column filling from the bottom up to match live battery percentage.
 * 4. Convex optical meniscus highlight with specular lens flare at the liquid surface.
 * 5. Top and bottom specular glass connector rings cleanly docking into the grid architecture.
 * 6. Laser-etched calibration ticks with "100" / "00" markers on the screen-inward edge.
 * 7. Photon pulse shimmer wave travelling up the capillary tube during charging.
 * 8. Frosted glass telemetry lozenge displaying the percentage and geometric Stark bolt when charging.
 * 9. Interactive tactile ripple wave on lozenge tap.
 */
@Composable
fun VerticalEnergyRail(
  batteryPct: Int,
  isCharging: Boolean,
  modifier: Modifier = Modifier,
  gridSize: Dp = 8.dp,
  majorStep: Int = 4,
  verticalMajorLineFromRightIndex: Int = 4,
  railHeight: Dp = 290.dp,
) {
  val scope = rememberCoroutineScope()
  val tapWave = remember { Animatable(0f) }

  // Smooth 850ms fluid level progression when battery percentage updates
  val animatedLevel by animateFloatAsState(
    targetValue = (batteryPct / 100f).coerceIn(0.02f, 1f),
    animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
    label = "BatteryLiquidLevel",
  )

  // Infinite animations for charging shimmer wave & ambient breathing
  val infiniteTransition = rememberInfiniteTransition(label = "EnergyRailCharging")
  val pulsePhase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1800, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "ChargingPhotonPulse",
  )
  val glowBreathing by infiniteTransition.animateFloat(
    initialValue = 0.25f,
    targetValue = 0.70f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "ChargingGlowBreathing",
  )

  // Smooth width transition for the lozenge when charging state toggles
  val pillTargetWidthDp = if (isCharging) 52.dp else 42.dp
  val animatedPillWidthDp by animateFloatAsState(
    targetValue = pillTargetWidthDp.value,
    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
    label = "PillWidth",
  )

  // Cached text paints for 120Hz performance
  val textPaint = remember {
    Paint().apply {
      isAntiAlias = true
      color = android.graphics.Color.WHITE
      typeface = Typeface.create("monospace", Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }
  }

  val tickLabelPaint = remember {
    Paint().apply {
      isAntiAlias = true
      color = android.graphics.Color.argb(120, 255, 255, 255)
      typeface = Typeface.create("monospace", Typeface.NORMAL)
      textAlign = Paint.Align.RIGHT
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .pointerInput(Unit) {
        detectTapGestures {
          scope.launch {
            tapWave.snapTo(0f)
            tapWave.animateTo(
              targetValue = 1f,
              animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            )
          }
        }
      }
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val width = size.width
      val height = size.height
      val stepPx = gridSize.toPx()
      if (stepPx <= 0f) return@Canvas

      // Exact matching calculation for the 4th major square line from the right
      val lastLineIndex = (width / stepPx).toInt()
      val highestMajor = (lastLineIndex / majorStep) * majorStep
      val targetIndex = highestMajor - (verticalMajorLineFromRightIndex - 1) * majorStep
      if (targetIndex < 0) return@Canvas
      val targetX = targetIndex * stepPx

      val cy = height / 2f
      val railHeightPx = railHeight.toPx()
      val topY = cy - railHeightPx / 2f
      val bottomY = cy + railHeightPx / 2f
      val totalRange = bottomY - topY

      val tubeR = 3.dp.toPx()
      val innerR = 1.8.dp.toPx()

      // 1. Capillary Tube Bore (Frosted Dark Glass Isolator)
      // Masks out the solid white guideline directly beneath the capillary tube
      drawRoundRect(
        brush = Brush.horizontalGradient(
          colors = listOf(
            Color(0xDD0B0D12),
            Color(0xFA0B0D12),
            Color(0xDD0B0D12),
          ),
          startX = targetX - tubeR,
          endX = targetX + tubeR,
        ),
        topLeft = Offset(targetX - tubeR, topY),
        size = Size(tubeR * 2, totalRange),
        cornerRadius = CornerRadius(tubeR, tubeR),
      )

      // Translucent inner liquid bore backdrop
      drawRoundRect(
        brush = Brush.horizontalGradient(
          colors = listOf(
            Color(0x08FFFFFF),
            Color(0x18FFFFFF),
            Color(0x08FFFFFF),
          ),
          startX = targetX - tubeR,
          endX = targetX + tubeR,
        ),
        topLeft = Offset(targetX - tubeR, topY),
        size = Size(tubeR * 2, totalRange),
        cornerRadius = CornerRadius(tubeR, tubeR),
      )

      // Dual Specular Outer Glass Borders
      drawRoundRect(
        brush = Brush.verticalGradient(
          colors = listOf(
            Color(0x55FFFFFF),
            Color(0x22FFFFFF),
            Color(0x55FFFFFF),
          ),
          startY = topY,
          endY = bottomY,
        ),
        topLeft = Offset(targetX - tubeR, topY),
        size = Size(tubeR * 2, totalRange),
        cornerRadius = CornerRadius(tubeR, tubeR),
        style = Stroke(width = 0.8.dp.toPx()),
      )

      // 2. Terminal Glass Connector Nodes (Top and Bottom Docking Rings)
      val nodeR = 4.2.dp.toPx()
      // Top Node
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(Color(0x80FFFFFF), Color(0x30FFFFFF), Color(0x200B0D12)),
          center = Offset(targetX, topY),
          radius = nodeR,
        ),
        radius = nodeR,
        center = Offset(targetX, topY),
      )
      drawCircle(
        color = Color(0x90FFFFFF),
        radius = nodeR,
        center = Offset(targetX, topY),
        style = Stroke(width = 0.8.dp.toPx()),
      )
      drawCircle(
        color = Color.White,
        radius = 1.2.dp.toPx(),
        center = Offset(targetX, topY),
      )

      // Bottom Node
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(Color(0x80FFFFFF), Color(0x30FFFFFF), Color(0x200B0D12)),
          center = Offset(targetX, bottomY),
          radius = nodeR,
        ),
        radius = nodeR,
        center = Offset(targetX, bottomY),
      )
      drawCircle(
        color = Color(0x90FFFFFF),
        radius = nodeR,
        center = Offset(targetX, bottomY),
        style = Stroke(width = 0.8.dp.toPx()),
      )
      drawCircle(
        color = Color.White,
        radius = 1.2.dp.toPx(),
        center = Offset(targetX, bottomY),
      )

      // 3. Laser-Etched Calibration Ticks (Graduations on Left Edge)
      tickLabelPaint.textSize = 7.dp.toPx()
      val tickLabelFm = tickLabelPaint.fontMetrics
      val labelYOffset = -(tickLabelFm.ascent + tickLabelFm.descent) / 2f

      for (pct in 0..100 step 10) {
        val tickY = bottomY - totalRange * (pct / 100f)
        val isMajor = (pct % 25 == 0)
        val tickLength = if (isMajor) 4.5.dp.toPx() else 2.5.dp.toPx()
        val tickColor = if (isMajor) Color(0x60FFFFFF) else Color(0x24FFFFFF)
        val tickStroke = if (isMajor) 0.9.dp.toPx() else 0.6.dp.toPx()

        val startX = targetX - tubeR - 1.2.dp.toPx()
        val endX = startX - tickLength

        drawLine(
          color = tickColor,
          start = Offset(startX, tickY),
          end = Offset(endX, tickY),
          strokeWidth = tickStroke,
        )

        // Etched markers at 100% (100) and 0% (00)
        if (pct == 100) {
          drawContext.canvas.nativeCanvas.drawText(
            "100",
            endX - 3.dp.toPx(),
            tickY + labelYOffset,
            tickLabelPaint,
          )
        } else if (pct == 0) {
          drawContext.canvas.nativeCanvas.drawText(
            "00",
            endX - 3.dp.toPx(),
            tickY + labelYOffset,
            tickLabelPaint,
          )
        }
      }

      // 4. Luminous Liquid Column (Pure White Monochrome Fluid)
      val fluidHeight = totalRange * animatedLevel
      val fluidTopY = bottomY - fluidHeight

      if (fluidHeight > 0.5f) {
        // Soft ambient fluid bloom
        drawRoundRect(
          brush = Brush.verticalGradient(
            colors = listOf(
              Color(0x35FFFFFF),
              Color(0x0AFFFFFF),
            ),
            startY = fluidTopY,
            endY = bottomY,
          ),
          topLeft = Offset(targetX - tubeR * 1.6f, fluidTopY),
          size = Size(tubeR * 3.2f, fluidHeight),
          cornerRadius = CornerRadius(tubeR, tubeR),
        )

        // Core luminous liquid column
        drawRoundRect(
          brush = Brush.verticalGradient(
            colors = listOf(
              Color(0xFFFFFFFF), // Radiant white meniscus head
              Color(0xEEFFFFFF),
              Color(0x88FFFFFF),
              Color(0x40FFFFFF), // Base
            ),
            startY = fluidTopY,
            endY = bottomY,
          ),
          topLeft = Offset(targetX - innerR, fluidTopY),
          size = Size(innerR * 2, fluidHeight),
          cornerRadius = CornerRadius(innerR, innerR),
        )

        // 5. Optical Meniscus Highlight (Convex Specular Lens)
        val meniscusW = 6.4.dp.toPx()
        val meniscusH = 2.4.dp.toPx()
        val meniscusRect = Rect(
          targetX - meniscusW / 2f,
          fluidTopY - meniscusH / 2f,
          targetX + meniscusW / 2f,
          fluidTopY + meniscusH / 2f,
        )

        // Specular radial flare on meniscus
        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(
              Color(0xCCFFFFFF),
              Color(0x35FFFFFF),
              Color.Transparent,
            ),
            center = Offset(targetX, fluidTopY),
            radius = 5.dp.toPx(),
          ),
          radius = 5.dp.toPx(),
          center = Offset(targetX, fluidTopY),
        )

        // Bright specular lens curve
        drawOval(
          brush = Brush.horizontalGradient(
            colors = listOf(
              Color(0x20FFFFFF),
              Color(0xFFFFFFFF),
              Color(0x20FFFFFF),
            ),
            startX = meniscusRect.left,
            endX = meniscusRect.right,
          ),
          topLeft = Offset(meniscusRect.left, meniscusRect.top),
          size = Size(meniscusRect.width, meniscusRect.height),
        )

        // Radiant center reflection point
        drawCircle(
          color = Color.White,
          radius = 1.1.dp.toPx(),
          center = Offset(targetX, fluidTopY),
        )

        // 6. Charging Photon Shimmer Wave
        if (isCharging) {
          val shimmerY = bottomY - fluidHeight * pulsePhase
          val shimmerHalfH = 16.dp.toPx()
          val sTop = (shimmerY - shimmerHalfH).coerceAtLeast(fluidTopY)
          val sBottom = (shimmerY + shimmerHalfH).coerceAtMost(bottomY)

          if (sBottom > sTop) {
            drawRoundRect(
              brush = Brush.verticalGradient(
                colors = listOf(
                  Color.Transparent,
                  Color(0xF0FFFFFF),
                  Color.Transparent,
                ),
                startY = sTop,
                endY = sBottom,
              ),
              topLeft = Offset(targetX - innerR, sTop),
              size = Size(innerR * 2, sBottom - sTop),
              cornerRadius = CornerRadius(innerR, innerR),
            )
          }

          // Ambient breathing glow around the meniscus during charge
          drawCircle(
            brush = Brush.radialGradient(
              colors = listOf(
                Color.White.copy(alpha = glowBreathing * 0.40f),
                Color.Transparent,
              ),
              center = Offset(targetX, fluidTopY),
              radius = 10.dp.toPx(),
            ),
            radius = 10.dp.toPx(),
            center = Offset(targetX, fluidTopY),
          )
        }

        // 7. Tactile Tap Ripple Pulse
        if (tapWave.value > 0.01f) {
          val waveY = bottomY - fluidHeight * tapWave.value
          val waveA = (1f - tapWave.value).coerceIn(0f, 1f)
          drawCircle(
            brush = Brush.radialGradient(
              colors = listOf(Color.White.copy(alpha = waveA * 0.85f), Color.Transparent),
              center = Offset(targetX, waveY),
              radius = 8.dp.toPx(),
            ),
            radius = 8.dp.toPx(),
            center = Offset(targetX, waveY),
          )
        }
      }

      // 8. Frosted Glass Telemetry Lozenge (Pill Badge below Rail)
      val pillW = animatedPillWidthDp.dp.toPx()
      val pillH = 22.dp.toPx()
      val pillTop = bottomY + 14.dp.toPx()
      val pillRect = Rect(
        targetX - pillW / 2f,
        pillTop,
        targetX + pillW / 2f,
        pillTop + pillH,
      )
      val pillCorner = CornerRadius(11.dp.toPx(), 11.dp.toPx())
      val gapBottom = bottomY + 42.dp.toPx()

      // Precision capillary stems connecting bottom node -> pill -> guideline resumption
      drawLine(
        brush = Brush.verticalGradient(
          colors = listOf(Color(0x90FFFFFF), Color(0x35FFFFFF)),
          startY = bottomY,
          endY = pillTop,
        ),
        start = Offset(targetX, bottomY),
        end = Offset(targetX, pillTop),
        strokeWidth = 1.dp.toPx(),
      )

      if (gapBottom > pillRect.bottom) {
        drawLine(
          brush = Brush.verticalGradient(
            colors = listOf(Color(0x35FFFFFF), Color(0x90FFFFFF)),
            startY = pillRect.bottom,
            endY = gapBottom,
          ),
          start = Offset(targetX, pillRect.bottom),
          end = Offset(targetX, gapBottom),
          strokeWidth = 1.dp.toPx(),
        )
      }

      // Obsidian dark glass backing (completely isolates from anything underneath)
      drawRoundRect(
        color = Color(0xFF0B0D12),
        topLeft = Offset(pillRect.left, pillRect.top),
        size = Size(pillRect.width, pillRect.height),
        cornerRadius = pillCorner,
      )

      // Liquid frosted glass surface
      drawRoundRect(
        brush = Brush.verticalGradient(
          colors = listOf(
            Color(0x35FFFFFF),
            Color(0x18FFFFFF),
            Color(0x100B0D12),
          ),
          startY = pillRect.top,
          endY = pillRect.bottom,
        ),
        topLeft = Offset(pillRect.left, pillRect.top),
        size = Size(pillRect.width, pillRect.height),
        cornerRadius = pillCorner,
      )

      // Specular Top Meniscus Highlight
      drawRoundRect(
        brush = Brush.verticalGradient(
          colors = listOf(
            Color(0x65FFFFFF),
            Color(0x15FFFFFF),
            Color.Transparent,
          ),
          startY = pillRect.top,
          endY = pillRect.top + 8.dp.toPx(),
        ),
        topLeft = Offset(pillRect.left, pillRect.top),
        size = Size(pillRect.width, 8.dp.toPx()),
        cornerRadius = pillCorner,
      )

      // Specular Glass Border
      val borderAlpha = if (isCharging) 0.50f + glowBreathing * 0.35f else 0.45f
      drawRoundRect(
        brush = Brush.verticalGradient(
          colors = listOf(
            Color.White.copy(alpha = borderAlpha),
            Color(0x20FFFFFF),
            Color(0x35FFFFFF),
          ),
          startY = pillRect.top,
          endY = pillRect.bottom,
        ),
        topLeft = Offset(pillRect.left, pillRect.top),
        size = Size(pillRect.width, pillRect.height),
        cornerRadius = pillCorner,
        style = Stroke(width = 0.8.dp.toPx()),
      )

      // Telemetry Text & Geometric Stark Bolt Icon
      textPaint.textSize = 9.8.dp.toPx()
      val textFm = textPaint.fontMetrics
      val textBaselineY = pillRect.top + (pillH - (textFm.ascent + textFm.descent)) / 2f

      val pctText = "$batteryPct%"

      if (isCharging) {
        val textWidth = textPaint.measureText(pctText)
        val boltHeight = 9.5.dp.toPx()
        val boltWidth = 5.5.dp.toPx()
        val gap = 3.dp.toPx()
        val totalContentW = boltWidth + gap + textWidth

        val contentStartX = pillRect.left + (pillW - totalContentW) / 2f
        val boltCenterX = contentStartX + boltWidth / 2f
        val boltCenterY = pillRect.top + pillH / 2f

        // Draw precision Stark lightning bolt vector
        val boltPath = makeStarkBoltPath(boltCenterX, boltCenterY, boltHeight)
        drawPath(path = boltPath, color = Color.White)

        // Ambient bolt radiance
        drawCircle(
          color = Color.White.copy(alpha = 0.25f * glowBreathing),
          radius = 5.dp.toPx(),
          center = Offset(boltCenterX, boltCenterY),
        )

        // Draw percentage text
        val textStartX = contentStartX + boltWidth + gap + textWidth / 2f
        drawContext.canvas.nativeCanvas.drawText(
          pctText,
          textStartX,
          textBaselineY,
          textPaint,
        )
      } else {
        // Centered percentage text
        drawContext.canvas.nativeCanvas.drawText(
          pctText,
          pillRect.left + pillW / 2f,
          textBaselineY,
          textPaint,
        )
      }
    }
  }
}

/**
 * Creates a precision geometric Stark Industries lightning bolt path.
 */
private fun makeStarkBoltPath(cx: Float, cy: Float, height: Float): Path {
  val h2 = height / 2f
  val w2 = height * 0.26f
  return Path().apply {
    moveTo(cx + w2 * 0.25f, cy - h2)
    lineTo(cx - w2, cy + h2 * 0.05f)
    lineTo(cx - w2 * 0.15f, cy + h2 * 0.05f)
    lineTo(cx - w2 * 0.35f, cy + h2)
    lineTo(cx + w2, cy - h2 * 0.15f)
    lineTo(cx + w2 * 0.15f, cy - h2 * 0.15f)
    close()
  }
}
