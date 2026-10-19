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
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Sleek Dark Glass Multi-Ring Rotary Clock & Weather Dial.
 *
 * Anchored flush to the left screen edge:
 * - Three concentric frosted obsidian glass rings presenting time in [hh : mm : ss : am/pm]:
 *     1. Inner Ring: Hours (hh) with micro-ticks and interactive spring scrubbing.
 *     2. Middle Ring: Minutes (mm) with dynamic minute track.
 *     3. Outer Ring: Seconds (ss) with 120Hz smooth fluid chronometer rotation.
 * - Horizontal liquid glass time capsule passing across all three rings framing:
 *     [ HH ] : [ MM ] : [ SS ] [ AM/PM ]
 * - Inner glass hub chamber housing crafted weather, temperature, date, and battery telemetry.
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

  // 120Hz display-synced smooth fluid progression
  LaunchedEffect(Unit) {
    while (true) {
      withFrameNanos { _ ->
        currentTimeMillis = System.currentTimeMillis()
      }
    }
  }

  val animatedHourOffset by
    animateFloatAsState(
      targetValue = dragHourOffset,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessLow,
        ),
      label = "glassDialRotation",
    )

  val calendar = remember { Calendar.getInstance() }
  calendar.timeInMillis = currentTimeMillis

  val liveHour24 = calendar.get(Calendar.HOUR_OF_DAY)
  val liveMinute = calendar.get(Calendar.MINUTE)
  val liveSecond = calendar.get(Calendar.SECOND)
  val millisFraction = (currentTimeMillis % 1000L) / 1000f
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
        .width(228.dp)
        .height(396.dp)
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
              val barHalfH = 20.dp.toPx()
              when {
                // Tapping mode badge in hub (left of time) toggles 12/24H mode
                tapOffset.y in (cy - barHalfH)..(cy + barHalfH) && tapOffset.x < 64.dp.toPx() -> {
                  onToggleTimeFormat()
                }
                // Tapping reset when hour is scrubbed
                abs(dragHourOffset) > 0.1f -> {
                  dragHourOffset = 0f
                }
                // Tapping upper weather chamber cycles weather
                tapOffset.x < 76.dp.toPx() && tapOffset.y < (cy - barHalfH) -> {
                  onWeatherClick()
                }
                // Tapping time readout inside capsule launches clock/alarms
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

      // Concentric Radii
      val r0 = 70.dp.toPx() // Inner chamber hub
      val r1In = 72.dp.toPx() // Inner Ring (Hours) start
      val r1Out = 110.dp.toPx() // Inner Ring (Hours) end
      val r2In = 112.dp.toPx() // Middle Ring (Minutes) start
      val r2Out = 150.dp.toPx() // Middle Ring (Minutes) end
      val r3In = 152.dp.toPx() // Outer Ring (Seconds) start
      val r3Out = 190.dp.toPx() // Outer Ring (Seconds) end

      // 0. Soft ambient ice glow behind the outer rim
      drawCircle(
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                IceGlowAccent.copy(alpha = 0.16f),
                Color.Transparent,
              ),
            center = Offset(0f, cy),
            radius = r3Out * 1.15f,
          ),
        radius = r3Out * 1.15f,
        center = Offset(0f, cy),
      )

      // 1. Concentric Glass Ring Semi-Circles
      fun makeSemiCirclePath(r: Float): Path {
        return Path().apply {
          moveTo(0f, cy - r)
          arcTo(
            rect = Rect(left = -r, top = cy - r, right = r, bottom = cy + r),
            startAngleDegrees = -90f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
          )
          close()
        }
      }

      val outerArcPath = makeSemiCirclePath(r3Out)
      val middleArcPath = makeSemiCirclePath(r2Out)
      val innerArcPath = makeSemiCirclePath(r1Out)
      val hubArcPath = makeSemiCirclePath(r0)

      // Outer Ring Background (Seconds Track)
      drawPath(
        path = outerArcPath,
        brush =
          Brush.horizontalGradient(
            colors =
              listOf(
                GlassSurface.copy(alpha = 0.45f),
                GlassSurfaceElevated.copy(alpha = 0.65f),
              ),
            startX = 0f,
            endX = r3Out,
          ),
      )

      // Middle Ring Background (Minutes Track)
      drawPath(
        path = middleArcPath,
        brush =
          Brush.horizontalGradient(
            colors =
              listOf(
                GlassSurface.copy(alpha = 0.55f),
                GlassSurfaceElevated.copy(alpha = 0.75f),
              ),
            startX = 0f,
            endX = r2Out,
          ),
      )

      // Inner Ring Background (Hours Track)
      drawPath(
        path = innerArcPath,
        brush =
          Brush.horizontalGradient(
            colors =
              listOf(
                GlassSurface.copy(alpha = 0.65f),
                GlassSurfaceElevated.copy(alpha = 0.85f),
              ),
            startX = 0f,
            endX = r1Out,
          ),
      )

      // Inner Chamber Hub Background (Weather & Date)
      drawPath(
        path = hubArcPath,
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                ObsidianBgTop.copy(alpha = 0.95f),
                GlassSurface.copy(alpha = 0.82f),
              ),
            center = Offset(14.dp.toPx(), cy),
            radius = r0,
          ),
      )

      // 2. Rotary Tracks inside the 3 Rings
      // 2a. Outer Ring: Seconds Track (ss)
      clipPath(outerArcPath) {
        drawGlassSecondsTrack(
          cy = cy,
          innerR = r3In,
          outerR = r3Out,
          liveSecond = liveSecond,
          millisFraction = millisFraction,
        )
      }

      // 2b. Middle Ring: Minutes Track (mm)
      clipPath(middleArcPath) {
        drawGlassMinutesTrack(
          cy = cy,
          innerR = r2In,
          outerR = r2Out,
          liveMinute = liveMinute,
          liveSecond = liveSecond,
          millisFraction = millisFraction,
        )
      }

      // 2c. Inner Ring: Hours Track (hh)
      clipPath(innerArcPath) {
        drawGlassHourTrack(
          cy = cy,
          innerR = r1In,
          outerR = r1Out,
          liveHour24 = liveHour24,
          hourOffset = animatedHourOffset,
          use24Hour = use24Hour,
        )
      }

      // 3. Delicate Specular Glass Rim Strokes for all 3 Rings + Hub
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
        topLeft = Offset(-r3Out, cy - r3Out),
        size = Size(r3Out * 2f, r3Out * 2f),
        style = Stroke(width = 1.2.dp.toPx()),
      )

      drawArc(
        color = GlassBorderSubtle,
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r2Out, cy - r2Out),
        size = Size(r2Out * 2f, r2Out * 2f),
        style = Stroke(width = 0.9.dp.toPx()),
      )

      drawArc(
        color = GlassBorderSubtle,
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r1Out, cy - r1Out),
        size = Size(r1Out * 2f, r1Out * 2f),
        style = Stroke(width = 0.9.dp.toPx()),
      )

      drawArc(
        color = GlassBorderSubtle,
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r0, cy - r0),
        size = Size(r0 * 2f, r0 * 2f),
        style = Stroke(width = 0.9.dp.toPx()),
      )

      // 4. Upper Inner Chamber: Weather Icon + Temperature
      drawCraftedWeather(
        cy = cy,
        weather = weather,
      )

      // 5. Lower Inner Chamber: Date & Battery Telemetry
      drawCraftedDateAndBattery(
        cy = cy,
        dayName = dayName,
        dateStr = dateStr,
        batteryPct = batteryPct,
        isCharging = isCharging,
      )

      // 6. Horizontal Liquid Frosted Glass Time Capsule across all 3 rings
      val barHalfH = 19.dp.toPx()
      val barRight = r3Out // exactly flush with outer ring rim (190dp)!
      val barPath =
        Path().apply {
          addRoundRect(
            RoundRect(
              rect = Rect(left = -8.dp.toPx(), top = cy - barHalfH, right = barRight, bottom = cy + barHalfH),
              cornerRadius = CornerRadius(barHalfH, barHalfH),
            )
          )
        }

      // Frosted obsidian glass backdrop to cleanly occlude track numbers under the time
      drawPath(
        path = barPath,
        brush =
          Brush.horizontalGradient(
            colors =
              listOf(
                ObsidianBgTop.copy(alpha = 0.96f),
                GlassSurfaceElevated.copy(alpha = 0.90f),
                GlassSurface.copy(alpha = 0.86f),
              ),
            startX = 0f,
            endX = barRight,
          ),
      )

      // Subtle specular hairlines along top and bottom edges - NO harsh cyan stroke box!
      drawLine(
        brush =
          Brush.horizontalGradient(
            colors =
              listOf(
                GlassBorderSubtle.copy(alpha = 0.15f),
                GlassBorderHighlight.copy(alpha = 0.40f),
                Color.Transparent,
              ),
            startX = 0f,
            endX = barRight,
          ),
        start = Offset(0f, cy - barHalfH),
        end = Offset(barRight, cy - barHalfH),
        strokeWidth = 0.7.dp.toPx(),
      )
      drawLine(
        brush =
          Brush.horizontalGradient(
            colors =
              listOf(
                GlassBorderSubtle.copy(alpha = 0.15f),
                GlassBorderSubtle.copy(alpha = 0.30f),
                Color.Transparent,
              ),
            startX = 0f,
            endX = barRight,
          ),
        start = Offset(0f, cy + barHalfH),
        end = Offset(barRight, cy + barHalfH),
        strokeWidth = 0.7.dp.toPx(),
      )

      // 7. Time Presentation in [hh : mm : ss] across the 3 rings + Mode Badge in Hub
      val hourCenterX = (r1In + r1Out) * 0.5f // ~91dp
      val minCenterX = (r2In + r2Out) * 0.5f // ~131dp
      val secCenterX = (r3In + r3Out) * 0.5f // ~171dp
      val colon1X = (hourCenterX + minCenterX) * 0.5f // ~111dp
      val colon2X = (minCenterX + secCenterX) * 0.5f // ~151dp
      val badgeCenterX = 40.dp.toPx() // neatly centered in Inner Hub!

      // Calculate active display hour
      val activeRawHour = (liveHour24 + animatedHourOffset).roundToInt()
      val wrapped24 = ((activeRawHour % 24) + 24) % 24
      val activeDisplayHour =
        if (use24Hour) {
          if (wrapped24 == 0) 24 else wrapped24
        } else {
          val h12 = wrapped24 % 12
          if (h12 == 0) 12 else h12
        }
      val hourStr = if (use24Hour) String.format(Locale.US, "%02d", activeDisplayHour) else activeDisplayHour.toString()
      val minStr = String.format(Locale.US, "%02d", liveMinute)
      val secStr = String.format(Locale.US, "%02d", liveSecond)

      // Soft optical radiance behind active digits (zero box borders, zero collisions)
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(IceGlowAccent.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(hourCenterX, cy),
            radius = 16.dp.toPx(),
          ),
        radius = 16.dp.toPx(),
        center = Offset(hourCenterX, cy),
      )
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(IceGlowAccent.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(minCenterX, cy),
            radius = 16.dp.toPx(),
          ),
        radius = 16.dp.toPx(),
        center = Offset(minCenterX, cy),
      )
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(IceGlowAccent.copy(alpha = 0.18f), Color.Transparent),
            center = Offset(secCenterX, cy),
            radius = 16.dp.toPx(),
          ),
        radius = 16.dp.toPx(),
        center = Offset(secCenterX, cy),
      )

      // Typography for time presentation
      val numberPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = android.graphics.Color.WHITE
          textSize = 15.dp.toPx()
          textAlign = Paint.Align.CENTER
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

      val secPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = 0xFF38BDF8.toInt()
          textSize = 15.dp.toPx()
          textAlign = Paint.Align.CENTER
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

      val colonPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = 0xFF38BDF8.toInt()
          textSize = 14.dp.toPx()
          textAlign = Paint.Align.CENTER
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

      val badgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = if (animatedHourOffset.roundToInt() != 0) 0xFFFBBF24.toInt() else 0xFF38BDF8.toInt()
          textSize = 8.5.dp.toPx()
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
          textAlign = Paint.Align.CENTER
        }

      drawContext.canvas.nativeCanvas.apply {
        val nFm = numberPaint.fontMetrics
        val nBaseline = cy - (nFm.ascent + nFm.descent) / 2f

        val cFm = colonPaint.fontMetrics
        val cBaseline = cy - (cFm.ascent + cFm.descent) / 2f - 0.5.dp.toPx()

        // Draw Hour
        drawText(hourStr, hourCenterX, nBaseline, numberPaint)

        // Draw Colon 1
        drawText(":", colon1X, cBaseline, colonPaint)

        // Draw Minute
        drawText(minStr, minCenterX, nBaseline, numberPaint)

        // Draw Colon 2
        drawText(":", colon2X, cBaseline, colonPaint)

        // Draw Second
        drawText(secStr, secCenterX, nBaseline, secPaint)

        // Draw Mode Tag (AM / PM / 24H) Badge in Hub
        val modeTag =
          when {
            animatedHourOffset.roundToInt() > 0 -> "+${animatedHourOffset.roundToInt()}H"
            animatedHourOffset.roundToInt() < 0 -> "${animatedHourOffset.roundToInt()}H"
            use24Hour -> "24H"
            isAm -> "AM"
            else -> "PM"
          }

        val badgeHalfW = 14.dp.toPx()
        val badgeHalfH = 10.dp.toPx()
        drawRoundRect(
          color = Color(0x2438BDF8),
          topLeft = Offset(badgeCenterX - badgeHalfW, cy - badgeHalfH),
          size = Size(badgeHalfW * 2f, badgeHalfH * 2f),
          cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
        )
        drawRoundRect(
          color = IceGlowAccent.copy(alpha = 0.40f),
          topLeft = Offset(badgeCenterX - badgeHalfW, cy - badgeHalfH),
          size = Size(badgeHalfW * 2f, badgeHalfH * 2f),
          cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
          style = Stroke(width = 0.8.dp.toPx()),
        )

        val bFm = badgePaint.fontMetrics
        val bBaseline = cy - (bFm.ascent + bFm.descent) / 2f
        drawText(modeTag, badgeCenterX, bBaseline, badgePaint)
      }
    }
  }
}

/**
 * 1. Inner Ring: Hours Track (hh)
 */
private fun DrawScope.drawGlassHourTrack(
  cy: Float,
  innerR: Float,
  outerR: Float,
  liveHour24: Int,
  hourOffset: Float,
  use24Hour: Boolean,
) {
  val textRadius = (innerR + outerR) * 0.50f
  val stepDegrees = if (use24Hour) 15f else 26f

  val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

  // Intermediate ticks spanning -84° to 84° with smooth distance fade
  for (deg in -84..84 step 4) {
    val rad = deg * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val isMajor = deg % 12 == 0
    val tickLen = if (isMajor) 5.5.dp.toPx() else 2.5.dp.toPx()
    val startR = outerR - tickLen
    val absDeg = abs(deg)
    val centerFade = ((absDeg - 8f) / 6f).coerceIn(0f, 1f)
    val edgeFade = ((85f - absDeg) / 85f).coerceIn(0f, 1f).pow(1.2f)
    val alpha = (if (isMajor) 0.45f else 0.18f) * centerFade * edgeFade
    if (alpha <= 0.01f) continue
    drawLine(
      color = Color.White.copy(alpha = alpha),
      start = Offset(startR * cosA, cy + startR * sinA),
      end = Offset(outerR * cosA, cy + outerR * sinA),
      strokeWidth = if (isMajor) 1.1.dp.toPx() else 0.6.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }

  // Render all visible hours across the ring (hours above and below the center)
  val baseHour = if (use24Hour) liveHour24 else (liveHour24 % 12)
  val deltaRange = if (use24Hour) -7..7 else -5..5
  for (delta in deltaRange) {
    val angleDeg = -(delta + hourOffset) * stepDegrees
    if (angleDeg < -84f || angleDeg > 84f) continue

    val absAngle = abs(angleDeg)
    if (absAngle < 8f) continue // Directly behind the active hour text in capsule

    // Smooth fade in as it exits the active line (8° -> 14°)
    val centerFade = ((absAngle - 8f) / 6f).coerceIn(0f, 1f)

    // Smooth fade out as it travels away toward the edges (14° -> 84°)
    val edgeFade = ((84f - absAngle) / (84f - 14f)).coerceIn(0f, 1f).pow(1.25f)
    val alpha = 0.85f * centerFade * edgeFade
    if (alpha <= 0.01f) continue

    val rawHour = baseHour + delta
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

    textPaint.textSize = 11.5.dp.toPx()
    textPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)

    drawContext.canvas.nativeCanvas.apply {
      save()
      translate(tx, ty)
      rotate(angleDeg * 0.70f)
      val fm = textPaint.fontMetrics
      val baselineY = -(fm.ascent + fm.descent) / 2f
      drawText(displayHour.toString(), 0f, baselineY, textPaint)
      restore()
    }
  }
}

/**
 * 2. Middle Ring: Minutes Track (mm)
 */
private fun DrawScope.drawGlassMinutesTrack(
  cy: Float,
  innerR: Float,
  outerR: Float,
  liveMinute: Int,
  liveSecond: Int,
  millisFraction: Float,
) {
  val textRadius = (innerR + outerR) * 0.50f
  val stepDegrees = 6.0f
  val fractionalShift = (liveSecond + millisFraction) / 60f

  val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

  // Minute ticks spanning -84° to 84° with smooth distance fade
  for (deg in -84..84 step 3) {
    val rad = deg * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val isMajor = deg % 6 == 0
    val tickLen = if (isMajor) 5.dp.toPx() else 2.5.dp.toPx()
    val startR = outerR - tickLen
    val absDeg = abs(deg)
    val centerFade = ((absDeg - 7f) / 7f).coerceIn(0f, 1f)
    val edgeFade = ((85f - absDeg) / 85f).coerceIn(0f, 1f).pow(1.2f)
    val alpha = (if (isMajor) 0.45f else 0.18f) * centerFade * edgeFade
    if (alpha <= 0.01f) continue
    drawLine(
      color = Color.White.copy(alpha = alpha),
      start = Offset(startR * cosA, cy + startR * sinA),
      end = Offset(outerR * cosA, cy + outerR * sinA),
      strokeWidth = if (isMajor) 1.0.dp.toPx() else 0.6.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }

  // Render whole numbers in the minutes ring across visible arc, fading away from center
  for (delta in -14..14) {
    val minuteVal = ((liveMinute + delta) % 60 + 60) % 60
    val angleDeg = -(delta - fractionalShift) * stepDegrees
    if (angleDeg < -84f || angleDeg > 84f) continue

    val absAngle = abs(angleDeg)
    if (absAngle < 6.8f) continue // Directly behind the active pill text

    // Smooth fade in as it exits the pill (6.8° -> 10.0°)
    val centerFade = ((absAngle - 6.8f) / 3.2f).coerceIn(0f, 1f)

    // Smooth fade out as it travels away toward the edges (10° -> 84°)
    val edgeFade = ((84f - absAngle) / (84f - 10f)).coerceIn(0f, 1f).pow(1.25f)

    val isMultipleOf5 = minuteVal % 5 == 0
    val baseAlpha = if (isMultipleOf5) 0.85f else 0.45f
    val alpha = baseAlpha * centerFade * edgeFade
    if (alpha <= 0.01f) continue

    val rad = angleDeg * (PI / 180.0)
    val tx = textRadius * cos(rad).toFloat()
    val ty = cy + textRadius * sin(rad).toFloat()

    textPaint.textSize = if (isMultipleOf5) 9.5.dp.toPx() else 8.dp.toPx()
    textPaint.color = if (isMultipleOf5) android.graphics.Color.WHITE else 0xFFCBD5E1.toInt()
    textPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)

    drawContext.canvas.nativeCanvas.apply {
      save()
      translate(tx, ty)
      rotate(angleDeg * 0.70f)
      val fm = textPaint.fontMetrics
      val baselineY = -(fm.ascent + fm.descent) / 2f
      drawText(String.format(Locale.US, "%02d", minuteVal), 0f, baselineY, textPaint)
      restore()
    }
  }
}

/**
 * 3. Outer Ring: Seconds Track (ss) with 120Hz smooth progression
 */
private fun DrawScope.drawGlassSecondsTrack(
  cy: Float,
  innerR: Float,
  outerR: Float,
  liveSecond: Int,
  millisFraction: Float,
) {
  val textRadius = (innerR + outerR) * 0.50f
  val stepDegrees = 6.0f
  val fractionalShift = millisFraction

  val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

  // Seconds ticks every second spanning -84° to 84° with smooth distance fade
  for (deg in -84..84 step 3) {
    val rad = deg * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val isMajor = deg % 6 == 0
    val tickLen = if (isMajor) 5.dp.toPx() else 2.5.dp.toPx()
    val startR = outerR - tickLen
    val absDeg = abs(deg)
    val centerFade = ((absDeg - 5.2f) / 3.3f).coerceIn(0f, 1f)
    val edgeFade = ((85f - absDeg) / 85f).coerceIn(0f, 1f).pow(1.2f)
    val alpha = (if (isMajor) 0.50f else 0.20f) * centerFade * edgeFade
    if (alpha <= 0.01f) continue
    drawLine(
      color = IceGlowAccent.copy(alpha = alpha),
      start = Offset(startR * cosA, cy + startR * sinA),
      end = Offset(outerR * cosA, cy + outerR * sinA),
      strokeWidth = if (isMajor) 1.0.dp.toPx() else 0.6.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }

  // Render whole numbers in the seconds ring across visible arc, fading away from center
  for (delta in -14..14) {
    val secondVal = ((liveSecond + delta) % 60 + 60) % 60
    val angleDeg = -(delta - fractionalShift) * stepDegrees
    if (angleDeg < -84f || angleDeg > 84f) continue

    val absAngle = abs(angleDeg)
    if (absAngle < 5.2f) continue // Directly behind active pill text

    // Smooth fade in as it exits the pill (5.2° -> 8.5°)
    val centerFade = ((absAngle - 5.2f) / 3.3f).coerceIn(0f, 1f)

    // Smooth fade out as it travels away toward the edges (8.5° -> 84°)
    val edgeFade = ((84f - absAngle) / (84f - 8.5f)).coerceIn(0f, 1f).pow(1.25f)

    val isMultipleOf5 = secondVal % 5 == 0
    val baseAlpha = if (isMultipleOf5) 0.85f else 0.45f
    val alpha = baseAlpha * centerFade * edgeFade
    if (alpha <= 0.01f) continue

    val rad = angleDeg * (PI / 180.0)
    val tx = textRadius * cos(rad).toFloat()
    val ty = cy + textRadius * sin(rad).toFloat()

    textPaint.textSize = if (isMultipleOf5) 9.5.dp.toPx() else 8.dp.toPx()
    textPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
    textPaint.color = if (isMultipleOf5) 0xFF38BDF8.toInt() else android.graphics.Color.WHITE

    drawContext.canvas.nativeCanvas.apply {
      save()
      translate(tx, ty)
      rotate(angleDeg * 0.70f)
      val fm = textPaint.fontMetrics
      val baselineY = -(fm.ascent + fm.descent) / 2f
      drawText(String.format(Locale.US, "%02d", secondVal), 0f, baselineY, textPaint)
      restore()
    }
  }
}

/**
 * 4. Weather Chamber in Upper Hub
 */
private fun DrawScope.drawCraftedWeather(
  cy: Float,
  weather: WeatherCondition,
) {
  val baseX = 8.dp.toPx()
  val baseY = cy - 54.dp.toPx()

  // Warm glowing sun orb
  val sunCenter = Offset(baseX + 18.dp.toPx(), baseY + 8.dp.toPx())
  drawCircle(
    brush =
      Brush.radialGradient(
        colors = listOf(WarmSunAccent.copy(alpha = 0.45f), Color.Transparent),
        center = sunCenter,
        radius = 11.dp.toPx(),
      ),
    radius = 11.dp.toPx(),
    center = sunCenter,
  )
  drawCircle(
    color = WarmSunAccent,
    radius = 5.dp.toPx(),
    center = sunCenter,
  )

  // Sleek vector cloud in front of sun
  val cloudPath =
    Path().apply {
      moveTo(baseX + 4.dp.toPx(), baseY + 18.dp.toPx())
      lineTo(baseX + 24.dp.toPx(), baseY + 18.dp.toPx())
      cubicTo(
        baseX + 28.dp.toPx(),
        baseY + 18.dp.toPx(),
        baseX + 28.dp.toPx(),
        baseY + 12.dp.toPx(),
        baseX + 23.dp.toPx(),
        baseY + 12.dp.toPx(),
      )
      cubicTo(
        baseX + 21.dp.toPx(),
        baseY + 5.dp.toPx(),
        baseX + 11.dp.toPx(),
        baseY + 5.dp.toPx(),
        baseX + 9.dp.toPx(),
        baseY + 11.dp.toPx(),
      )
      cubicTo(
        baseX + 3.dp.toPx(),
        baseY + 11.dp.toPx(),
        baseX + 1.dp.toPx(),
        baseY + 18.dp.toPx(),
        baseX + 4.dp.toPx(),
        baseY + 18.dp.toPx(),
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
    style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
  )

  // Temperature + Condition text
  val tempPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textSize = 14.dp.toPx()
      typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
  val condPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xFF94A3B8.toInt()
      textSize = 7.5.dp.toPx()
      typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

  drawContext.canvas.nativeCanvas.apply {
    drawText("${weather.tempCelsius}°", baseX + 32.dp.toPx(), baseY + 16.dp.toPx(), tempPaint)
    drawText(weather.label.uppercase(Locale.US), baseX + 4.dp.toPx(), baseY + 28.dp.toPx(), condPaint)
  }
}

/**
 * 5. Date & Battery Telemetry in Lower Hub
 */
private fun DrawScope.drawCraftedDateAndBattery(
  cy: Float,
  dayName: String,
  dateStr: String,
  batteryPct: Int,
  isCharging: Boolean,
) {
  val baseX = 8.dp.toPx()
  val baseY = cy + 42.dp.toPx()

  val datePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textSize = 10.dp.toPx()
      typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
      letterSpacing = 0.04f
    }

  val battPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (isCharging) 0xFFFBBF24.toInt() else 0xFF38BDF8.toInt()
      textSize = 9.dp.toPx()
      typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

  drawContext.canvas.nativeCanvas.apply {
    drawText("$dayName, $dateStr", baseX, baseY, datePaint)
    val battText = if (isCharging) "⚡ $batteryPct%" else "$batteryPct% BAT"
    drawText(battText, baseX, baseY + 14.dp.toPx(), battPaint)
  }
}
