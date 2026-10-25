package com.nexwork.edith.ui.hud

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.HudLine
import com.nexwork.edith.theme.HudLineDim
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val TAU = (2f * PI).toFloat()

private fun pt(c: Offset, r: Float, angle: Float): Offset =
  Offset(c.x + r * cos(angle), c.y + r * sin(angle))

/**
 * Stark radar-scope clock instrument:
 *  - crimson sweep line + decaying phosphor trail, one revolution per minute
 *  - hour blip on the outer ring, minute blip on the inner ring
 *  - battery arc on the rim, minute burn-down arc inside it (live telemetry)
 *  - digital time inside, monospace
 *  - every 5 minutes the whole scope "recalibrates": damped rotational wobble
 *    and a flickering [ SYNC ] label
 */
@Composable
fun StarkClockDial(
  batteryPct: Int,
  isCharging: Boolean,
  modifier: Modifier = Modifier,
) {
  val transition = rememberInfiniteTransition(label = "clockDial")

  // Sweep completes one revolution per minute; cycle restarts on the minute
  val sweepAngle by transition.animateFloat(
    initialValue = 0f,
    targetValue = TAU,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 60_000, easing = LinearEasing),
      repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
    ),
    label = "sweep",
  )

  // 3600s cycle: drives live minute/hour so the dial ticks without recomposition
  val minuteCycle by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 3_600_000, easing = LinearEasing),
      repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
    ),
    label = "minuteCycle",
  )

  // 300s recalibration cycle: wobble decays over ~0.9s, then long idle
  val cycleT by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 300_000, easing = LinearEasing),
      repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
    ),
    label = "cycle",
  )

  val syncPhase = (cycleT * 300_000f / 900f).coerceAtMost(1.1f)
  val wobbleDeg: Float = if (syncPhase < 1f) {
    val p = syncPhase.coerceAtMost(1f)
    (9f * exp(-4.5 * p) * sin(p * PI.toFloat() * 4f)).toFloat()
  } else {
    0f
  }
  val syncLabelAlpha = when {
    syncPhase < 0.85f -> 0.4f + 0.6f * abs01(sin(syncPhase * 22f))
    syncPhase < 1f -> (1f - syncPhase) / 0.15f
    else -> 0f
  }

  val base = remember {
    val cal = java.util.Calendar.getInstance().apply {
      timeInMillis = System.currentTimeMillis()
    }
    Triple(
      cal.get(java.util.Calendar.HOUR_OF_DAY),
      cal.get(java.util.Calendar.MINUTE),
      cal.get(java.util.Calendar.SECOND),
    )
  }
  val baseHour = base.first
  val baseMinute = (base.second + 1 - base.third / 60f).coerceAtLeast(0f)

  val textPaint = remember {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
      textAlign = android.graphics.Paint.Align.CENTER
      letterSpacing = 0.08f
    }
  }
  val labelPaint = remember {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
      textAlign = android.graphics.Paint.Align.CENTER
      letterSpacing = 0.22f
    }
  }

  Canvas(modifier = modifier) {
    val minute = (baseMinute + minuteCycle * 60f) % 60f
    val hour24 = ((baseHour + ((baseMinute + minuteCycle * 60f) / 60f).toInt()) % 24 + 24) % 24
    val hour12Int = hour24 % 12
    val hour12 = hour24 % 12 + (minute / 60f)
    val timeText = "%02d:%02d".format(hour24, minute.toInt())

    val c = Offset(size.width / 2f, size.height / 2f)
    val rMax = size.minDimension / 2f

    val rRim = rMax * 0.96f     // battery arc
    val rHour = rMax * 0.86f    // hour ring + hour blip
    val rMin = rMax * 0.70f     // minute ring + minute blip
    val rBurn = rMax * 0.58f    // minute burn-down arc
    val rHub = rMax * 0.09f

    rotate(wobbleDeg, c) {
      // Scope frame
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(Color.White.copy(alpha = 0.035f), Color.Transparent),
          center = c,
          radius = rMax,
        ),
        radius = rMax,
        center = c,
      )
      drawCircle(
        color = HudLine.copy(alpha = 0.22f),
        radius = rMax * 0.995f,
        center = c,
        style = Stroke(1.1f),
      )
      // Faint range rings
      drawCircle(
        color = HudLine.copy(alpha = 0.09f),
        radius = rMin,
        center = c,
        style = Stroke(0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 7f))),
      )
      drawCircle(
        color = HudLine.copy(alpha = 0.07f),
        radius = rBurn,
        center = c,
        style = Stroke(0.8f),
      )

      // Hour ticks: current hour crimson, quarter hours silver, rest dim
      for (i in 0 until 12) {
        val a = i / 12f * TAU - PI.toFloat() / 2f
        val major = i % 3 == 0
        val isNow = i == hour12Int
        drawLine(
          color = when {
            isNow -> Crimson
            major -> HudLine.copy(alpha = 0.75f)
            else -> HudLine.copy(alpha = 0.28f)
          },
          start = pt(c, rHour - (if (major) 10f else 6f), a),
          end = pt(c, rHour + (if (major) 7f else 3f), a),
          strokeWidth = if (isNow) 2.6f else if (major) 1.8f else 1f,
          cap = StrokeCap.Round,
        )
      }
      // Minute ticks
      for (m in 0 until 60 step 5) {
        val a = m / 60f * TAU - PI.toFloat() / 2f
        drawLine(
          color = HudLine.copy(alpha = 0.20f),
          start = pt(c, rMin - 4f, a),
          end = pt(c, rMin + 4f, a),
          strokeWidth = 0.9f,
        )
      }

      // Battery arc on the rim (starts at 12, clockwise)
      val batteryFrac = (batteryPct.coerceIn(0, 100)) / 100f
      val batteryColor = when {
        isCharging -> CrimsonBright
        batteryPct <= 20 -> CrimsonBright
        else -> HudLine.copy(alpha = 0.80f)
      }
      drawArc(
        color = batteryColor.copy(alpha = batteryColor.alpha * 0.25f),
        startAngle = -90f,
        sweepAngle = 360f * batteryFrac,
        useCenter = false,
        topLeft = Offset(c.x - rRim, c.y - rRim),
        size = Size(rRim * 2f, rRim * 2f),
        style = Stroke(4.5f),
      )
      drawArc(
        color = batteryColor,
        startAngle = -90f,
        sweepAngle = 360f * batteryFrac,
        useCenter = false,
        topLeft = Offset(c.x - rRim, c.y - rRim),
        size = Size(rRim * 2f, rRim * 2f),
        style = Stroke(1.8f, cap = StrokeCap.Round),
      )

      // Minute burn-down arc: remaining minutes in this hour, trailing the current minute blip
      val burnStart = (minute + 1) / 60f * TAU - PI.toFloat() / 2f
      val burnSweep = ((59 - minute) / 60f) * TAU
      drawArc(
        color = HudLineDim.copy(alpha = 0.55f),
        startAngle = burnStart * 180f / PI.toFloat(),
        sweepAngle = burnSweep * 180f / PI.toFloat(),
        useCenter = false,
        topLeft = Offset(c.x - rBurn, c.y - rBurn),
        size = Size(rBurn * 2f, rBurn * 2f),
        style = Stroke(1.4f),
      )

      // Phosphor trail: 44 segments fading behind the sweep
      val trailSpan = 0.62f * TAU
      for (k in 0 until 44) {
        val f0 = k / 44f
        val f1 = (k + 1f) / 44f
        val alpha = (1f - f0) * (1f - f0) * 0.32f
        drawArc(
          color = Crimson.copy(alpha = alpha),
          startAngle = (sweepAngle - trailSpan * f1) * 180f / PI.toFloat(),
          sweepAngle = (trailSpan * (f1 - f0)) * 180f / PI.toFloat(),
          useCenter = true,
          topLeft = Offset(c.x - rHour, c.y - rHour),
          size = Size(rHour * 2f, rHour * 2f),
        )
      }

      // Sweep line + tip
      drawLine(
        color = CrimsonBright,
        start = c,
        end = pt(c, rHour, sweepAngle - PI.toFloat() / 2f),
        strokeWidth = 1.6f,
        cap = StrokeCap.Round,
      )
      drawCircle(CrimsonBright, radius = 3.2f, center = pt(c, rHour, sweepAngle - PI.toFloat() / 2f))

      // Hour blip (crimson, pulses once as the sweep passes over it)
      val hourAngle = hour12 / 12f * TAU - PI.toFloat() / 2f
      val hourDist = (((sweepAngle - hourAngle) % TAU + TAU) % TAU)
      val hourGlow = exp(-hourDist * 2.2f)
      drawCircle(
        color = Crimson,
        radius = 4.2f + 2.6f * hourGlow,
        center = pt(c, rHour, hourAngle),
      )
      drawCircle(
        color = Crimson.copy(alpha = 0.35f * hourGlow),
        radius = 11f + 6f * hourGlow,
        center = pt(c, rHour, hourAngle),
      )

      // Minute blip (silver)
      val minAngle = minute / 60f * TAU - PI.toFloat() / 2f
      drawCircle(color = HudLine, radius = 2.6f, center = pt(c, rMin, minAngle))

      // Hub
      drawCircle(color = HudVoidTint, radius = rHub, center = c)
      drawCircle(
        color = Crimson.copy(alpha = 0.5f + 0.5f * syncLabelAlpha),
        radius = rHub,
        center = c,
        style = Stroke(1.2f),
      )

      // Digital readout
      textPaint.textSize = 26.sp.toPx()
      textPaint.color = android.graphics.Color.argb(242, 230, 237, 243)
      drawContext.canvas.nativeCanvas.drawText(timeText, c.x, c.y + 9.sp.toPx(), textPaint)

      labelPaint.textSize = 8.5.sp.toPx()
      labelPaint.color = android.graphics.Color.argb(
        (syncLabelAlpha * 255).toInt(),
        147, 161, 176,
      )
      drawContext.canvas.nativeCanvas.drawText("[ SYNC ]", c.x, c.y + 26.sp.toPx(), labelPaint)
    }
  }
}

private val HudVoidTint = Color(0xCC04060B)

private fun abs01(v: Float): Float = if (v < 0f) -v else v
