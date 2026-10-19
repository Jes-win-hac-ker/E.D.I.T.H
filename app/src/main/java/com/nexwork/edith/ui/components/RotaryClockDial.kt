package com.nexwork.edith.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nexwork.edith.model.WeatherCondition
import com.nexwork.edith.theme.GlassBorderHighlight
import com.nexwork.edith.theme.GlassBorderSubtle
import com.nexwork.edith.theme.GlassSurface
import com.nexwork.edith.theme.GlassSurfaceElevated
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.ObsidianBgTop
import com.nexwork.edith.theme.WarmSunAccent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Sleek Dark Glass Rotary Clock & Weather Dial built from scratch.
 *
 * Anchored flush to the left screen edge:
 * - Frosted obsidian outer & inner glass semi-circles with subtle specular borders.
 * - Smooth rotating hour track with fine micro-ticks and distance-based opacity.
 * - Floating glass horizontal time capsule highlighting the current hour + live :MM.SS.
 * - Crafted weather & date readouts nestled inside the inner glass chamber.
 */
@Composable
fun RotaryClockDial(
  weather: WeatherCondition,
  batteryPct: Int,
  isCharging: Boolean,
  use24Hour: Boolean,
  onWeatherClick: () -> Unit,
  onClockClick: () -> Unit,
  onToggleTimeFormat: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var dragHourOffset by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    while (true) {
      currentTimeMillis = System.currentTimeMillis()
      delay(1000L)
    }
  }

  val animatedOffset by
    animateFloatAsState(
      targetValue = dragHourOffset,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessLow,
        ),
      label = "glassDialRotation",
    )

  val calendar =
    remember(currentTimeMillis) {
      Calendar.getInstance().apply { timeInMillis = currentTimeMillis }
    }

  val liveHour24 = calendar.get(Calendar.HOUR_OF_DAY)
  val liveMinute = calendar.get(Calendar.MINUTE)
  val liveSecond = calendar.get(Calendar.SECOND)
  val isAm = calendar.get(Calendar.AM_PM) == Calendar.AM

  val dayName =
    remember(currentTimeMillis / 60_000L) {
      SimpleDateFormat("EEE", Locale.US).format(Date(currentTimeMillis)).uppercase(Locale.US)
    }
  val dateStr =
    remember(currentTimeMillis / 60_000L) {
      SimpleDateFormat("MMM d", Locale.US).format(Date(currentTimeMillis)).uppercase(Locale.US)
    }

  Box(
    modifier =
      modifier
        .width(212.dp)
        .height(332.dp)
        .pointerInput(Unit) {
          detectVerticalDragGestures(
            onDragEnd = {
              val rounded = dragHourOffset.roundToInt().toFloat()
              dragHourOffset = if (abs(rounded) <= 0.3f) 0f else rounded
            },
            onVerticalDrag = { change, dragAmount ->
              change.consume()
              dragHourOffset = (dragHourOffset - dragAmount / 36f).coerceIn(-12f, 12f)
            },
          )
        }
        .pointerInput(Unit) {
          detectTapGestures(
            onTap = { tapOffset ->
              val cy = size.height / 2f
              val barHalfH = 26.dp.toPx()
              when {
                tapOffset.y in (cy - barHalfH)..(cy + barHalfH) && tapOffset.x > 152.dp.toPx() -> {
                  onToggleTimeFormat()
                }
                abs(dragHourOffset) > 0.1f -> {
                  dragHourOffset = 0f
                }
                tapOffset.x < 108.dp.toPx() && tapOffset.y < (cy - barHalfH) -> {
                  onWeatherClick()
                }
                tapOffset.y in (cy - barHalfH)..(cy + barHalfH) -> {
                  onClockClick()
                }
              }
            }
          )
        }
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val cy = size.height / 2f
      val outerR = 156.dp.toPx()
      val innerR = 104.dp.toPx()

      // 1. Soft ambient ice glow behind the outer rim
      drawCircle(
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                IceGlowAccent.copy(alpha = 0.14f),
                Color.Transparent,
              ),
            center = Offset(0f, cy),
            radius = outerR * 1.15f,
          ),
        radius = outerR * 1.15f,
        center = Offset(0f, cy),
      )

      // 2. Outer Frosted Glass Semi-Circle Track
      val outerArcPath =
        Path().apply {
          moveTo(0f, cy - outerR)
          arcTo(
            rect = Rect(left = -outerR, top = cy - outerR, right = outerR, bottom = cy + outerR),
            startAngleDegrees = -90f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
          )
          close()
        }

      drawPath(
        path = outerArcPath,
        brush =
          Brush.horizontalGradient(
            colors =
              listOf(
                GlassSurface.copy(alpha = 0.55f),
                GlassSurfaceElevated.copy(alpha = 0.85f),
              ),
            startX = 0f,
            endX = outerR,
          ),
      )

      // 3. Inner Deep Obsidian Glass Chamber
      val innerArcPath =
        Path().apply {
          moveTo(0f, cy - innerR)
          arcTo(
            rect = Rect(left = -innerR, top = cy - innerR, right = innerR, bottom = cy + innerR),
            startAngleDegrees = -90f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
          )
          close()
        }

      drawPath(
        path = innerArcPath,
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                ObsidianBgTop.copy(alpha = 0.92f),
                GlassSurface.copy(alpha = 0.78f),
              ),
            center = Offset(16.dp.toPx(), cy),
            radius = innerR,
          ),
      )

      // 4. Rotating Hour Track & Micro-Ticks
      clipPath(outerArcPath) {
        drawGlassHourTrack(
          cy = cy,
          innerR = innerR,
          outerR = outerR,
          liveHour24 = liveHour24,
          hourOffset = animatedOffset,
          use24Hour = use24Hour,
        )
      }

      // 5. Delicate Specular Glass Rim Strokes (1.2dp instead of thick wireframe lines)
      drawArc(
        brush =
          Brush.sweepGradient(
            colors =
              listOf(
                GlassBorderSubtle,
                GlassBorderHighlight,
                IceGlowAccent.copy(alpha = 0.45f),
                GlassBorderHighlight,
                GlassBorderSubtle,
              ),
            center = Offset(0f, cy),
          ),
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-outerR, cy - outerR),
        size = Size(outerR * 2f, outerR * 2f),
        style = Stroke(width = 1.3.dp.toPx()),
      )

      drawArc(
        color = GlassBorderSubtle,
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-innerR, cy - innerR),
        size = Size(innerR * 2f, innerR * 2f),
        style = Stroke(width = 1.dp.toPx()),
      )

      // 6. Upper Inner Chamber: Crafted Weather Icon + Temperature
      drawCraftedWeather(
        cy = cy,
        weather = weather,
      )

      // 7. Lower Inner Chamber: Date & Battery Pill
      drawCraftedDateAndBattery(
        cy = cy,
        dayName = dayName,
        dateStr = dateStr,
        batteryPct = batteryPct,
        isCharging = isCharging,
      )

      // 8. Horizontal Frosted Glass Time Capsule (extending from x=0 to x=198dp)
      val barHalfH = 25.dp.toPx()
      val barRight = 198.dp.toPx()
      val barPath =
        Path().apply {
          addRoundRect(
            RoundRect(
              rect = Rect(left = -18.dp.toPx(), top = cy - barHalfH, right = barRight, bottom = cy + barHalfH),
              cornerRadius = CornerRadius(25.dp.toPx(), 25.dp.toPx()),
            )
          )
        }

      drawPath(
        path = barPath,
        brush =
          Brush.horizontalGradient(
            colors =
              listOf(
                ObsidianBgTop.copy(alpha = 0.94f),
                GlassSurfaceElevated.copy(alpha = 0.88f),
                GlassSurface.copy(alpha = 0.92f),
              ),
            startX = 0f,
            endX = barRight,
          ),
      )

      drawPath(
        path = barPath,
        brush =
          Brush.horizontalGradient(
            colors =
              listOf(
                GlassBorderSubtle,
                IceGlowAccent.copy(alpha = 0.45f),
                GlassBorderHighlight,
              ),
            startX = 0f,
            endX = barRight,
          ),
        style = Stroke(width = 1.2.dp.toPx()),
      )

      // 9. Active Hour Highlight Capsule inside the Horizontal Bar (around x=116..146dp)
      val capsuleLeft = 114.dp.toPx()
      val capsuleRight = 146.dp.toPx()
      val capsuleHalfH = 19.dp.toPx()
      drawRoundRect(
        color = IceGlowAccent.copy(alpha = 0.16f),
        topLeft = Offset(capsuleLeft, cy - capsuleHalfH),
        size = Size(capsuleRight - capsuleLeft, capsuleHalfH * 2f),
        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
      )
      drawRoundRect(
        color = IceGlowAccent.copy(alpha = 0.7f),
        topLeft = Offset(capsuleLeft, cy - capsuleHalfH),
        size = Size(capsuleRight - capsuleLeft, capsuleHalfH * 2f),
        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
        style = Stroke(width = 1.2.dp.toPx()),
      )

      // Re-render the active center hour on top of the capsule so it's crystal sharp
      val activeRawHour = (liveHour24 + animatedOffset).roundToInt()
      val wrapped24 = ((activeRawHour % 24) + 24) % 24
      val activeDisplayHour =
        if (use24Hour) {
          if (wrapped24 == 0) 24 else wrapped24
        } else {
          val h12 = wrapped24 % 12
          if (h12 == 0) 12 else h12
        }

      val activeHourPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = android.graphics.Color.WHITE
          textSize = 17.dp.toPx()
          textAlign = Paint.Align.CENTER
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
      drawContext.canvas.nativeCanvas.apply {
        val fm = activeHourPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        drawText(
          activeDisplayHour.toString(),
          (capsuleLeft + capsuleRight) / 2f,
          baseline,
          activeHourPaint,
        )
      }

      // 10. Minutes, Seconds & Mode Badge inside Horizontal Capsule
      drawGlassCapsuleTimeReadout(
        cy = cy,
        minute = liveMinute,
        second = liveSecond,
        isAm = isAm,
        use24Hour = use24Hour,
        scrubOffset = animatedOffset.roundToInt(),
      )
    }
  }
}

private fun DrawScope.drawGlassHourTrack(
  cy: Float,
  innerR: Float,
  outerR: Float,
  liveHour24: Int,
  hourOffset: Float,
  use24Hour: Boolean,
) {
  val textRadius = (innerR + outerR) * 0.50f
  val tickInnerR = outerR - 8.dp.toPx()
  val tickOuterR = outerR - 2.dp.toPx()
  val stepDegrees = 22f
  val baseHourFloat = liveHour24 + hourOffset

  val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

  // Subtle intermediate degree ticks along the outer glass rim
  for (deg in -80..80 step 5) {
    val rad = deg * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val isMajor = deg % 22 == 0
    val startR = if (isMajor) tickInnerR - 2.dp.toPx() else tickInnerR + 2.dp.toPx()
    val alpha = (1f - (abs(deg) / 90f)).coerceIn(0.08f, 0.45f)
    drawLine(
      color = Color.White.copy(alpha = if (isMajor) alpha else alpha * 0.5f),
      start = Offset(startR * cosA, cy + startR * sinA),
      end = Offset(tickOuterR * cosA, cy + tickOuterR * sinA),
      strokeWidth = if (isMajor) 1.2.dp.toPx() else 0.7.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }

  for (delta in -4..4) {
    val rawHour = (baseHourFloat.roundToInt() + delta)
    val fractionalShift = baseHourFloat - baseHourFloat.roundToInt()
    val angleDeg = -(delta - fractionalShift) * stepDegrees
    if (angleDeg < -82f || angleDeg > 82f) continue

    val distanceSteps = abs(delta - fractionalShift)
    // Skip drawing the center hour here since it's drawn inside the highlight capsule
    if (distanceSteps < 0.35f) continue

    val alpha =
      when {
        distanceSteps < 1.2f -> 0.82f
        distanceSteps < 2.2f -> 0.52f
        distanceSteps < 3.2f -> 0.28f
        else -> 0.14f
      }

    val wrapped24 = ((rawHour % 24) + 24) % 24
    val displayHour =
      if (use24Hour) {
        if (wrapped24 == 0) 24 else wrapped24
      } else {
        val h12 = wrapped24 % 12
        if (h12 == 0) 12 else h12
      }

    val rad = angleDeg * (PI / 180.0)
    val tx = textRadius * cos(rad).toFloat()
    val ty = cy + textRadius * sin(rad).toFloat()

    textPaint.textSize = 12.5.dp.toPx()
    textPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)

    drawContext.canvas.nativeCanvas.apply {
      save()
      translate(tx, ty)
      rotate(angleDeg * 0.75f)
      val fm = textPaint.fontMetrics
      val baselineY = -(fm.ascent + fm.descent) / 2f
      drawText(displayHour.toString(), 0f, baselineY, textPaint)
      restore()
    }
  }
}

private fun DrawScope.drawCraftedWeather(
  cy: Float,
  weather: WeatherCondition,
) {
  val baseX = 14.dp.toPx()
  val baseY = cy - 64.dp.toPx()

  // Warm glowing sun orb
  val sunCenter = Offset(baseX + 22.dp.toPx(), baseY + 8.dp.toPx())
  drawCircle(
    brush =
      Brush.radialGradient(
        colors = listOf(WarmSunAccent.copy(alpha = 0.45f), Color.Transparent),
        center = sunCenter,
        radius = 12.dp.toPx(),
      ),
    radius = 12.dp.toPx(),
    center = sunCenter,
  )
  drawCircle(
    color = WarmSunAccent,
    radius = 5.5.dp.toPx(),
    center = sunCenter,
  )

  // Sleek vector cloud in front of sun
  val cloudPath =
    Path().apply {
      moveTo(baseX + 4.dp.toPx(), baseY + 19.dp.toPx())
      lineTo(baseX + 26.dp.toPx(), baseY + 19.dp.toPx())
      cubicTo(
        baseX + 30.dp.toPx(),
        baseY + 19.dp.toPx(),
        baseX + 30.dp.toPx(),
        baseY + 12.dp.toPx(),
        baseX + 25.dp.toPx(),
        baseY + 12.dp.toPx(),
      )
      cubicTo(
        baseX + 23.dp.toPx(),
        baseY + 5.dp.toPx(),
        baseX + 12.dp.toPx(),
        baseY + 5.dp.toPx(),
        baseX + 10.dp.toPx(),
        baseY + 11.dp.toPx(),
      )
      cubicTo(
        baseX + 3.dp.toPx(),
        baseY + 11.dp.toPx(),
        baseX + 1.dp.toPx(),
        baseY + 19.dp.toPx(),
        baseX + 4.dp.toPx(),
        baseY + 19.dp.toPx(),
      )
      close()
    }

  drawPath(
    path = cloudPath,
    color = GlassSurfaceElevated.copy(alpha = 0.92f),
  )
  drawPath(
    path = cloudPath,
    color = Color.White.copy(alpha = 0.9f),
    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
  )

  // Temperature + Condition text
  val tempPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textSize = 16.dp.toPx()
      typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
  val condPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xFF94A3B8.toInt()
      textSize = 8.5.dp.toPx()
      typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

  drawContext.canvas.nativeCanvas.apply {
    drawText("${weather.tempCelsius}°", baseX + 36.dp.toPx(), baseY + 18.dp.toPx(), tempPaint)
    drawText(weather.label.uppercase(Locale.US), baseX + 4.dp.toPx(), baseY + 31.dp.toPx(), condPaint)
  }
}

private fun DrawScope.drawCraftedDateAndBattery(
  cy: Float,
  dayName: String,
  dateStr: String,
  batteryPct: Int,
  isCharging: Boolean,
) {
  val baseX = 14.dp.toPx()
  val baseY = cy + 46.dp.toPx()

  val datePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textSize = 11.dp.toPx()
      typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
      letterSpacing = 0.05f
    }

  val battPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (isCharging) 0xFFFBBF24.toInt() else 0xFF38BDF8.toInt()
      textSize = 9.5.dp.toPx()
      typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

  drawContext.canvas.nativeCanvas.apply {
    drawText("$dayName, $dateStr", baseX, baseY, datePaint)
    val battText = if (isCharging) "⚡ $batteryPct%" else "$batteryPct% BAT"
    drawText(battText, baseX, baseY + 15.dp.toPx(), battPaint)
  }
}

private fun DrawScope.drawGlassCapsuleTimeReadout(
  cy: Float,
  minute: Int,
  second: Int,
  isAm: Boolean,
  use24Hour: Boolean,
  scrubOffset: Int,
) {
  val minStr = String.format(Locale.US, ":%02d", minute)
  val secStr = String.format(Locale.US, " %02d", second)

  val minutePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textSize = 21.dp.toPx()
      typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
    }

  val secondPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xFF94A3B8.toInt()
      textSize = 11.dp.toPx()
      typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

  val badgePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (scrubOffset != 0) 0xFFFBBF24.toInt() else 0xFF38BDF8.toInt()
      textSize = 10.dp.toPx()
      typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }

  drawContext.canvas.nativeCanvas.apply {
    val fm = minutePaint.fontMetrics
    val baseline = cy - (fm.ascent + fm.descent) / 2f

    drawText(minStr, 14.dp.toPx(), baseline, minutePaint)
    val minWidth = minutePaint.measureText(minStr)
    drawText(secStr, 14.dp.toPx() + minWidth, baseline - 4.dp.toPx(), secondPaint)

    val modeTag =
      when {
        scrubOffset > 0 -> "+${scrubOffset}H"
        scrubOffset < 0 -> "${scrubOffset}H"
        use24Hour -> "24H"
        isAm -> "AM"
        else -> "PM"
      }
    val bFm = badgePaint.fontMetrics
    val bBaseline = cy - (bFm.ascent + bFm.descent) / 2f
    drawText(modeTag, 171.dp.toPx(), bBaseline, badgePaint)
  }
}
