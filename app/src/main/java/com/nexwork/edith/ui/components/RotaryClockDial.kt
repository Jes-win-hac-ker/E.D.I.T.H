package com.nexwork.edith.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.nexwork.edith.theme.GlassBorderHighlight
import com.nexwork.edith.theme.GlassBorderSubtle
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sleek Monochrome Obsidian & White Glass Multi-Ring Rotary Chronometer.
 *
 * Implements a pure Black & White Liquid Glass aesthetic:
 * 1. Concentric annular frosted glass rings (Hours, Minutes, Seconds) with pure monochrome gradients.
 * 2. Multi-stop specular white highlight borders along inner and outer ring perimeters.
 * 3. Diagonal specular light wash reflecting across the crystal face.
 * 4. Liquid Glass Time Capsule / Loupe with specular meniscus highlight and pure white radiance.
 * 5. Frosted glass mode indicator pill (12H / 24H) in the central hub.
 * 6. Smooth 120Hz continuous chronometer progression.
 */
@Composable
fun RotaryClockDial(
  use24Hour: Boolean = false,
  modifier: Modifier = Modifier,
) {
  var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

  // 120Hz display-synced smooth fluid progression
  LaunchedEffect(Unit) {
    while (true) {
      withFrameNanos { _ ->
        currentTimeMillis = System.currentTimeMillis()
      }
    }
  }

  val calendar = remember { Calendar.getInstance() }
  calendar.timeInMillis = currentTimeMillis

  val liveHour24 = calendar.get(Calendar.HOUR_OF_DAY)
  val liveMinute = calendar.get(Calendar.MINUTE)
  val liveSecond = calendar.get(Calendar.SECOND)
  val millisFraction = (currentTimeMillis % 1000L) / 1000f
  val isAm = calendar.get(Calendar.AM_PM) == Calendar.AM

  Box(
    modifier =
      modifier
        .width(228.dp)
        .height(396.dp)
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val cy = size.height / 2f

      // Concentric Radii (in dp to px)
      val r0 = 68.dp.toPx() // Central hub disc
      val r1In = 71.dp.toPx() // Inner Ring (Hours) start
      val r1Out = 108.dp.toPx() // Inner Ring (Hours) end
      val r2In = 111.dp.toPx() // Middle Ring (Minutes) start
      val r2Out = 148.dp.toPx() // Middle Ring (Minutes) end
      val r3In = 151.dp.toPx() // Outer Ring (Seconds) start
      val r3Out = 188.dp.toPx() // Outer Ring (Seconds) end

      // 0. Soft ambient monochrome white halo behind the outer rim
      drawCircle(
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                Color(0x18FFFFFF),
                Color(0x06FFFFFF),
                Color.Transparent,
              ),
            center = Offset(0f, cy),
            radius = r3Out * 1.18f,
          ),
        radius = r3Out * 1.18f,
        center = Offset(0f, cy),
      )

      // Annular paths
      val outerRingPath = makeRingPath(r3In, r3Out, cy)
      val middleRingPath = makeRingPath(r2In, r2Out, cy)
      val innerRingPath = makeRingPath(r1In, r1Out, cy)
      val hubPath = makeDiscPath(r0, cy)
      val fullDialArc = makeDiscPath(r3Out, cy)

      // 1. Concentric Frosted Monochrome Glass Ring Fills
      // Outer Ring (Seconds Track)
      drawPath(
        path = outerRingPath,
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                Color(0x28FFFFFF), // frosted specular highlight
                Color(0x10FFFFFF),
                Color(0x220B0D12), // deep obsidian glass base
                Color(0x14FFFFFF), // subtle silvery glass reflection
              ),
            center = Offset(0f, cy),
            radius = r3Out,
          ),
      )

      // Middle Ring (Minutes Track)
      drawPath(
        path = middleRingPath,
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                Color(0x2CFFFFFF),
                Color(0x12FFFFFF),
                Color(0x260B0D12),
                Color(0x16FFFFFF),
              ),
            center = Offset(0f, cy),
            radius = r2Out,
          ),
      )

      // Inner Ring (Hours Track)
      drawPath(
        path = innerRingPath,
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                Color(0x30FFFFFF),
                Color(0x14FFFFFF),
                Color(0x2A0B0D12),
                Color(0x18FFFFFF),
              ),
            center = Offset(0f, cy),
            radius = r1Out,
          ),
      )

      // Central Hub Disc
      drawPath(
        path = hubPath,
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                Color(0x34FFFFFF),
                Color(0x16FFFFFF),
                Color(0x350B0D12),
                Color(0x220B0D12),
              ),
            center = Offset(18.dp.toPx(), cy),
            radius = r0,
          ),
      )

      // 2. Rotary Chronometer Tracks inside the 3 Rings (Clipped cleanly to each annular band)
      // 2a. Outer Ring: Seconds Track (ss)
      clipPath(outerRingPath) {
        drawGlassSecondsTrack(
          cy = cy,
          innerR = r3In,
          outerR = r3Out,
          liveSecond = liveSecond,
          millisFraction = millisFraction,
        )
      }

      // 2b. Middle Ring: Minutes Track (mm)
      clipPath(middleRingPath) {
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
      clipPath(innerRingPath) {
        drawGlassHourTrack(
          cy = cy,
          innerR = r1In,
          outerR = r1Out,
          liveHour24 = liveHour24,
          liveMinute = liveMinute,
          liveSecond = liveSecond,
          millisFraction = millisFraction,
          use24Hour = use24Hour,
        )
      }

      // 3. Multi-stop Specular White Glass Rim Strokes for all Rings & Hub
      // Outer Ring outer rim (r3Out)
      drawArc(
        brush =
          Brush.linearGradient(
            colors =
              listOf(
                Color(0x99FFFFFF),
                Color(0x55FFFFFF),
                Color(0x18FFFFFF),
                Color(0x55FFFFFF),
                Color(0x80FFFFFF),
              ),
            start = Offset(0f, cy - r3Out),
            end = Offset(r3Out, cy + r3Out),
          ),
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r3Out, cy - r3Out),
        size = Size(r3Out * 2f, r3Out * 2f),
        style = Stroke(width = 1.2.dp.toPx()),
      )
      // Outer Ring inner rim (r3In)
      drawArc(
        color = GlassBorderSubtle.copy(alpha = 0.35f),
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r3In, cy - r3In),
        size = Size(r3In * 2f, r3In * 2f),
        style = Stroke(width = 0.8.dp.toPx()),
      )

      // Middle Ring outer rim (r2Out)
      drawArc(
        brush =
          Brush.linearGradient(
            colors =
              listOf(
                Color(0x85FFFFFF),
                Color(0x45FFFFFF),
                Color(0x18FFFFFF),
                Color(0x65FFFFFF),
              ),
            start = Offset(0f, cy - r2Out),
            end = Offset(r2Out, cy + r2Out),
          ),
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r2Out, cy - r2Out),
        size = Size(r2Out * 2f, r2Out * 2f),
        style = Stroke(width = 1.0.dp.toPx()),
      )
      // Middle Ring inner rim (r2In)
      drawArc(
        color = GlassBorderSubtle.copy(alpha = 0.30f),
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r2In, cy - r2In),
        size = Size(r2In * 2f, r2In * 2f),
        style = Stroke(width = 0.8.dp.toPx()),
      )

      // Inner Ring outer rim (r1Out)
      drawArc(
        brush =
          Brush.linearGradient(
            colors =
              listOf(
                Color(0x90FFFFFF),
                Color(0x50FFFFFF),
                Color(0x18FFFFFF),
                Color(0x70FFFFFF),
              ),
            start = Offset(0f, cy - r1Out),
            end = Offset(r1Out, cy + r1Out),
          ),
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r1Out, cy - r1Out),
        size = Size(r1Out * 2f, r1Out * 2f),
        style = Stroke(width = 1.0.dp.toPx()),
      )
      // Inner Ring inner rim (r1In)
      drawArc(
        color = GlassBorderSubtle.copy(alpha = 0.30f),
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r1In, cy - r1In),
        size = Size(r1In * 2f, r1In * 2f),
        style = Stroke(width = 0.8.dp.toPx()),
      )

      // Central Hub outer rim (r0)
      drawArc(
        brush =
          Brush.sweepGradient(
            colors =
              listOf(
                GlassBorderSubtle,
                GlassBorderHighlight,
                Color(0x80FFFFFF),
                GlassBorderHighlight,
                GlassBorderSubtle,
              ),
            center = Offset(0f, cy),
          ),
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(-r0, cy - r0),
        size = Size(r0 * 2f, r0 * 2f),
        style = Stroke(width = 1.0.dp.toPx()),
      )

      // 4. Diagonal Specular Glass Light Sheen Across Crystal Face
      clipPath(fullDialArc) {
        drawRect(
          brush =
            Brush.linearGradient(
              colors =
                listOf(
                  Color(0x22FFFFFF),
                  Color(0x06FFFFFF),
                  Color.Transparent,
                  Color(0x10FFFFFF),
                  Color(0x1EFFFFFF),
                  Color.Transparent,
                ),
              start = Offset(0f, cy - r3Out),
              end = Offset(r3Out * 1.08f, cy + r3Out),
            )
        )
      }

      // 5. Liquid Glass Time Capsule / Optical Loupe framing [ HH : MM : SS ]
      val barHalfH = 19.5.dp.toPx()
      val barRight = r3Out + 2.dp.toPx() // perfectly aligned with outer ring rim
      val capsulePath =
        Path().apply {
          addRoundRect(
            RoundRect(
              rect =
                Rect(
                  left = -6.dp.toPx(),
                  top = cy - barHalfH,
                  right = barRight,
                  bottom = cy + barHalfH,
                ),
              topRight = CornerRadius(barHalfH, barHalfH),
              bottomRight = CornerRadius(barHalfH, barHalfH),
              topLeft = CornerRadius.Zero,
              bottomLeft = CornerRadius.Zero,
            )
          )
        }

      // Frosted liquid monochrome glass backdrop with subtle occlusion
      drawPath(
        path = capsulePath,
        brush =
          Brush.linearGradient(
            colors =
              listOf(
                Color(0x450B0D12),
                Color(0x351A202C),
                Color(0x280B0D12),
                Color(0x20FFFFFF),
              ),
            start = Offset(0f, cy - barHalfH),
            end = Offset(barRight, cy + barHalfH),
          ),
      )

      // Specular white border around capsule
      drawPath(
        path = capsulePath,
        brush =
          Brush.linearGradient(
            colors =
              listOf(
                Color(0x99FFFFFF),
                Color(0x55FFFFFF),
                Color(0x20FFFFFF),
                Color(0x55FFFFFF),
                Color(0x80FFFFFF),
              ),
            start = Offset(0f, cy - barHalfH),
            end = Offset(barRight, cy + barHalfH),
          ),
        style = Stroke(width = 1.0.dp.toPx()),
      )

      // Specular meniscus highlight along top edge of capsule
      val meniscusPath =
        Path().apply {
          addRoundRect(
            RoundRect(
              rect =
                Rect(
                  left = -6.dp.toPx(),
                  top = cy - barHalfH,
                  right = barRight,
                  bottom = cy - barHalfH * 0.15f,
                ),
              topRight = CornerRadius(barHalfH, barHalfH),
              bottomRight = CornerRadius.Zero,
              topLeft = CornerRadius.Zero,
              bottomLeft = CornerRadius.Zero,
            )
          )
        }
      drawPath(
        path = meniscusPath,
        brush =
          Brush.verticalGradient(
            colors =
              listOf(
                Color(0x55FFFFFF),
                Color(0x18FFFFFF),
                Color.Transparent,
              ),
            startY = cy - barHalfH,
            endY = cy - barHalfH * 0.15f,
          ),
      )

      // 6. Time Presentation Coordinates in [hh : mm : ss] across the 3 rings
      val hourCenterX = (r1In + r1Out) * 0.5f // ~89.5dp
      val minCenterX = (r2In + r2Out) * 0.5f // ~129.5dp
      val secCenterX = (r3In + r3Out) * 0.5f // ~169.5dp
      val colon1X = (hourCenterX + minCenterX) * 0.5f // ~109.5dp
      val colon2X = (minCenterX + secCenterX) * 0.5f // ~149.5dp
      val badgeCenterX = 36.dp.toPx() // neatly centered in Inner Hub

      // Vertical glass hairline dividers between cells
      val divTop = cy - barHalfH * 0.60f
      val divBottom = cy + barHalfH * 0.60f
      drawLine(
        brush =
          Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color(0x35FFFFFF), Color.Transparent),
            startY = divTop,
            endY = divBottom,
          ),
        start = Offset(colon1X, divTop),
        end = Offset(colon1X, divBottom),
        strokeWidth = 0.8.dp.toPx(),
      )
      drawLine(
        brush =
          Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color(0x35FFFFFF), Color.Transparent),
            startY = divTop,
            endY = divBottom,
          ),
        start = Offset(colon2X, divTop),
        end = Offset(colon2X, divBottom),
        strokeWidth = 0.8.dp.toPx(),
      )

      // Active hour calculation
      val activeRawHour = liveHour24
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

      // Optical white radiance behind active digits
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
            center = Offset(hourCenterX, cy),
            radius = 16.dp.toPx(),
          ),
        radius = 16.dp.toPx(),
        center = Offset(hourCenterX, cy),
      )
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
            center = Offset(minCenterX, cy),
            radius = 16.dp.toPx(),
          ),
        radius = 16.dp.toPx(),
        center = Offset(minCenterX, cy),
      )
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
            center = Offset(secCenterX, cy),
            radius = 16.dp.toPx(),
          ),
        radius = 16.dp.toPx(),
        center = Offset(secCenterX, cy),
      )

      // Typography for time presentation (pure white monochrome)
      val numberPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = android.graphics.Color.WHITE
          textSize = 15.dp.toPx()
          textAlign = Paint.Align.CENTER
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

      val secPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = android.graphics.Color.WHITE
          textSize = 15.dp.toPx()
          textAlign = Paint.Align.CENTER
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

      val colonPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = android.graphics.Color.WHITE
          textSize = 14.dp.toPx()
          textAlign = Paint.Align.CENTER
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

      val badgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = android.graphics.Color.WHITE
          textSize = 9.dp.toPx()
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
            use24Hour -> "24H"
            isAm -> "AM"
            else -> "PM"
          }

        val badgeHalfW = 15.dp.toPx()
        val badgeHalfH = 10.dp.toPx()
        val badgeRect =
          Rect(
            badgeCenterX - badgeHalfW,
            cy - badgeHalfH,
            badgeCenterX + badgeHalfW,
            cy + badgeHalfH,
          )
        val badgePath =
          Path().apply {
            addRoundRect(RoundRect(badgeRect, CornerRadius(5.dp.toPx(), 5.dp.toPx())))
          }

        // Translucent frosted monochrome glass badge fill
        drawPath(
          path = badgePath,
          brush =
            Brush.linearGradient(
              colors =
                listOf(
                  Color(0x25FFFFFF),
                  Color(0x12FFFFFF),
                  Color(0x180B0D12),
                ),
              start = Offset(badgeRect.left, badgeRect.top),
              end = Offset(badgeRect.right, badgeRect.bottom),
            ),
        )
        // Specular white badge border
        drawPath(
          path = badgePath,
          brush =
            Brush.linearGradient(
              colors =
                listOf(
                  Color(0x99FFFFFF),
                  Color(0x50FFFFFF),
                  Color(0x75FFFFFF),
                ),
              start = Offset(badgeRect.left, badgeRect.top),
              end = Offset(badgeRect.right, badgeRect.bottom),
            ),
          style = Stroke(width = 0.9.dp.toPx()),
        )

        val bFm = badgePaint.fontMetrics
        val bBaseline = cy - (bFm.ascent + bFm.descent) / 2f
        drawText(modeTag, badgeCenterX, bBaseline, badgePaint)
      }
    }
  }
}

/**
 * Creates a semi-annular ring path between [innerR] and [outerR] anchored to the left.
 */
private fun makeRingPath(innerR: Float, outerR: Float, cy: Float): Path {
  return Path().apply {
    arcTo(
      rect = Rect(left = -outerR, top = cy - outerR, right = outerR, bottom = cy + outerR),
      startAngleDegrees = -90f,
      sweepAngleDegrees = 180f,
      forceMoveTo = false,
    )
    arcTo(
      rect = Rect(left = -innerR, top = cy - innerR, right = innerR, bottom = cy + innerR),
      startAngleDegrees = 90f,
      sweepAngleDegrees = -180f,
      forceMoveTo = false,
    )
    close()
  }
}

/**
 * Creates a semi-circular disc path with radius [r] anchored to the left.
 */
private fun makeDiscPath(r: Float, cy: Float): Path {
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

/**
 * 1. Inner Ring: Hours Track (hh) with smooth rotation
 */
private fun DrawScope.drawGlassHourTrack(
  cy: Float,
  innerR: Float,
  outerR: Float,
  liveHour24: Int,
  liveMinute: Int,
  liveSecond: Int,
  millisFraction: Float,
  use24Hour: Boolean,
) {
  val textRadius = (innerR + outerR) * 0.50f
  val stepDegrees = if (use24Hour) 15f else 26f
  val fractionalShift = (liveMinute + (liveSecond + millisFraction) / 60f) / 60f

  val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

  val baseHour = if (use24Hour) liveHour24 else (liveHour24 % 12)
  val deltaRange = if (use24Hour) -7..7 else -5..5

  // 1. Rotating Hour Graduation Ticks (Synchronized with hour rotation)
  for (delta in deltaRange) {
    for (fracStep in 0..3) {
      val frac = fracStep * 0.25f
      val angleDeg = -(delta + frac - fractionalShift) * stepDegrees
      if (angleDeg < -84f || angleDeg > 84f) continue

      val absDeg = abs(angleDeg)
      if (absDeg < 8f) continue // Directly behind active hour in capsule

      val isHourMark = (fracStep == 0)
      val isHalfHour = (fracStep == 2)

      val tickLen = when {
        isHourMark -> 5.2.dp.toPx()
        isHalfHour -> 3.2.dp.toPx()
        else -> 2.0.dp.toPx()
      }
      val tickStroke = when {
        isHourMark -> 1.1.dp.toPx()
        isHalfHour -> 0.8.dp.toPx()
        else -> 0.5.dp.toPx()
      }
      val baseAlpha = when {
        isHourMark -> 0.55f
        isHalfHour -> 0.30f
        else -> 0.16f
      }

      val centerFade = ((absDeg - 8f) / 6f).coerceIn(0f, 1f)
      val edgeFade = ((85f - absDeg) / 85f).coerceIn(0f, 1f).pow(1.2f)
      val alpha = baseAlpha * centerFade * edgeFade
      if (alpha <= 0.01f) continue

      val rad = angleDeg * (PI / 180.0)
      val cosA = cos(rad).toFloat()
      val sinA = sin(rad).toFloat()
      val startR = outerR - tickLen

      drawLine(
        color = Color.White.copy(alpha = alpha),
        start = Offset(startR * cosA, cy + startR * sinA),
        end = Offset(outerR * cosA, cy + outerR * sinA),
        strokeWidth = tickStroke,
        cap = StrokeCap.Round,
      )
    }
  }

  // 2. Rotating Bezel Serrations along Inner Ring Outer Rim (r1Out)
  for (deltaBezel in -32..32) {
    val bezelAngle = -(deltaBezel * 0.125f - fractionalShift) * stepDegrees
    if (bezelAngle < -84f || bezelAngle > 84f) continue
    val absAngle = abs(bezelAngle)
    val edgeFade = ((85f - absAngle) / 85f).coerceIn(0f, 1f).pow(1.2f)
    val alpha = 0.18f * edgeFade
    if (alpha <= 0.01f) continue

    val rad = bezelAngle * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val startR = outerR - 1.2.dp.toPx()

    drawLine(
      color = Color.White.copy(alpha = alpha),
      start = Offset(startR * cosA, cy + startR * sinA),
      end = Offset(outerR * cosA, cy + outerR * sinA),
      strokeWidth = 0.6.dp.toPx(),
    )
  }

  // 3. Rotating Whole Hour Numerals
  for (delta in deltaRange) {
    val angleDeg = -(delta - fractionalShift) * stepDegrees
    if (angleDeg < -84f || angleDeg > 84f) continue

    val absAngle = abs(angleDeg)
    if (absAngle < 8f) continue // Directly behind active hour in capsule

    val centerFade = ((absAngle - 8f) / 6f).coerceIn(0f, 1f)
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
 * 2. Middle Ring: Minutes Track (mm) with smooth rotation
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

  // 1. Rotating Minute Graduation Ticks (Synchronized with minute rotation)
  for (delta in -16..16) {
    for (fracStep in 0..1) {
      val frac = fracStep * 0.5f
      val angleDeg = -(delta + frac - fractionalShift) * stepDegrees
      if (angleDeg < -84f || angleDeg > 84f) continue

      val absDeg = abs(angleDeg)
      if (absDeg < 6.8f) continue // Directly behind active minute in capsule

      val minuteVal = ((liveMinute + delta) % 60 + 60) % 60
      val isMinuteMark = (fracStep == 0)
      val isMultipleOf5 = isMinuteMark && (minuteVal % 5 == 0)

      val tickLen = when {
        isMultipleOf5 -> 4.8.dp.toPx()
        isMinuteMark -> 3.2.dp.toPx()
        else -> 1.8.dp.toPx()
      }
      val tickStroke = when {
        isMultipleOf5 -> 1.1.dp.toPx()
        isMinuteMark -> 0.75.dp.toPx()
        else -> 0.5.dp.toPx()
      }
      val baseAlpha = when {
        isMultipleOf5 -> 0.55f
        isMinuteMark -> 0.28f
        else -> 0.14f
      }

      val centerFade = ((absDeg - 6.8f) / 3.2f).coerceIn(0f, 1f)
      val edgeFade = ((85f - absDeg) / 85f).coerceIn(0f, 1f).pow(1.2f)
      val alpha = baseAlpha * centerFade * edgeFade
      if (alpha <= 0.01f) continue

      val rad = angleDeg * (PI / 180.0)
      val cosA = cos(rad).toFloat()
      val sinA = sin(rad).toFloat()
      val startR = outerR - tickLen

      drawLine(
        color = Color.White.copy(alpha = alpha),
        start = Offset(startR * cosA, cy + startR * sinA),
        end = Offset(outerR * cosA, cy + outerR * sinA),
        strokeWidth = tickStroke,
        cap = StrokeCap.Round,
      )
    }
  }

  // 2. Rotating Bezel Serrations along Middle Ring Outer Rim (r2Out)
  for (deltaBezel in -42..42) {
    val bezelAngle = -(deltaBezel * 0.333f - fractionalShift) * stepDegrees
    if (bezelAngle < -84f || bezelAngle > 84f) continue
    val absAngle = abs(bezelAngle)
    val edgeFade = ((85f - absAngle) / 85f).coerceIn(0f, 1f).pow(1.2f)
    val alpha = 0.16f * edgeFade
    if (alpha <= 0.01f) continue

    val rad = bezelAngle * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val startR = outerR - 1.2.dp.toPx()

    drawLine(
      color = Color.White.copy(alpha = alpha),
      start = Offset(startR * cosA, cy + startR * sinA),
      end = Offset(outerR * cosA, cy + outerR * sinA),
      strokeWidth = 0.6.dp.toPx(),
    )
  }

  // 3. Rotating Whole Minute Numerals
  for (delta in -14..14) {
    val minuteVal = ((liveMinute + delta) % 60 + 60) % 60
    val angleDeg = -(delta - fractionalShift) * stepDegrees
    if (angleDeg < -84f || angleDeg > 84f) continue

    val absAngle = abs(angleDeg)
    if (absAngle < 6.8f) continue // Directly behind active minute in capsule

    val centerFade = ((absAngle - 6.8f) / 3.2f).coerceIn(0f, 1f)
    val edgeFade = ((84f - absAngle) / (84f - 10f)).coerceIn(0f, 1f).pow(1.25f)

    val isMultipleOf5 = minuteVal % 5 == 0
    val baseAlpha = if (isMultipleOf5) 0.90f else 0.45f
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
 * 3. Outer Ring: Seconds Track (ss) with 120Hz smooth continuous rotation
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

  // 1. Rotating Seconds Graduation Ticks (Synchronized with seconds rotation)
  for (delta in -16..16) {
    for (fracStep in 0..1) {
      val frac = fracStep * 0.5f
      val angleDeg = -(delta + frac - fractionalShift) * stepDegrees
      if (angleDeg < -84f || angleDeg > 84f) continue

      val absDeg = abs(angleDeg)
      if (absDeg < 5.2f) continue // Directly behind active second in capsule

      val secondVal = ((liveSecond + delta) % 60 + 60) % 60
      val isSecondMark = (fracStep == 0)
      val isMultipleOf5 = isSecondMark && (secondVal % 5 == 0)

      val tickLen = when {
        isMultipleOf5 -> 5.0.dp.toPx()
        isSecondMark -> 3.2.dp.toPx()
        else -> 1.8.dp.toPx()
      }
      val tickStroke = when {
        isMultipleOf5 -> 1.2.dp.toPx()
        isSecondMark -> 0.75.dp.toPx()
        else -> 0.5.dp.toPx()
      }
      val baseAlpha = when {
        isMultipleOf5 -> 0.60f
        isSecondMark -> 0.28f
        else -> 0.15f
      }

      val centerFade = ((absDeg - 5.2f) / 3.3f).coerceIn(0f, 1f)
      val edgeFade = ((85f - absDeg) / 85f).coerceIn(0f, 1f).pow(1.2f)
      val alpha = baseAlpha * centerFade * edgeFade
      if (alpha <= 0.01f) continue

      val rad = angleDeg * (PI / 180.0)
      val cosA = cos(rad).toFloat()
      val sinA = sin(rad).toFloat()
      val startR = outerR - tickLen

      drawLine(
        color = Color.White.copy(alpha = alpha),
        start = Offset(startR * cosA, cy + startR * sinA),
        end = Offset(outerR * cosA, cy + outerR * sinA),
        strokeWidth = tickStroke,
        cap = StrokeCap.Round,
      )
    }
  }

  // 2. Rotating Bezel Serrations along Outer Ring Outer Rim (r3Out)
  for (deltaBezel in -54..54) {
    val bezelAngle = -(deltaBezel * 0.25f - fractionalShift) * stepDegrees
    if (bezelAngle < -84f || bezelAngle > 84f) continue
    val absAngle = abs(bezelAngle)
    val edgeFade = ((85f - absAngle) / 85f).coerceIn(0f, 1f).pow(1.2f)
    val alpha = 0.18f * edgeFade
    if (alpha <= 0.01f) continue

    val rad = bezelAngle * (PI / 180.0)
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val startR = outerR - 1.2.dp.toPx()

    drawLine(
      color = Color.White.copy(alpha = alpha),
      start = Offset(startR * cosA, cy + startR * sinA),
      end = Offset(outerR * cosA, cy + outerR * sinA),
      strokeWidth = 0.6.dp.toPx(),
    )
  }

  // 3. Rotating Whole Second Numerals
  for (delta in -14..14) {
    val secondVal = ((liveSecond + delta) % 60 + 60) % 60
    val angleDeg = -(delta - fractionalShift) * stepDegrees
    if (angleDeg < -84f || angleDeg > 84f) continue

    val absAngle = abs(angleDeg)
    if (absAngle < 5.2f) continue // Directly behind active second in capsule

    val centerFade = ((absAngle - 5.2f) / 3.3f).coerceIn(0f, 1f)
    val edgeFade = ((84f - absAngle) / (84f - 8.5f)).coerceIn(0f, 1f).pow(1.25f)

    val isMultipleOf5 = secondVal % 5 == 0
    val baseAlpha = if (isMultipleOf5) 0.95f else 0.45f
    val alpha = baseAlpha * centerFade * edgeFade
    if (alpha <= 0.01f) continue

    val rad = angleDeg * (PI / 180.0)
    val tx = textRadius * cos(rad).toFloat()
    val ty = cy + textRadius * sin(rad).toFloat()

    textPaint.textSize = if (isMultipleOf5) 9.5.dp.toPx() else 8.dp.toPx()
    textPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
    textPaint.color = if (isMultipleOf5) android.graphics.Color.WHITE else 0xFFCBD5E1.toInt()

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
