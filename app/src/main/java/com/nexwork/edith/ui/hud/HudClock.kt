package com.nexwork.edith.ui.hud

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.CrimsonDim
import com.nexwork.edith.theme.LocalCrimsonMonetActive
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

private val OrbitalSlate = Color(0xFF64748B)

private val Months = arrayOf(
  "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
)
private val Days = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

/**
 * Wall-clock face. The drawing only reads it, so a second costs no recomposition and no
 * allocation, and the strings that need a [Calendar] refresh on their own slower cadence.
 */
private class ClockFace {
  var date = ""
  var weekday = ""
  var zone = ""
  var hour = 0
  var minute = 0
  var second = 0
  val digits = CharArray(6)

  fun tick(now: Long) {
    val local = now + TimeZone.getDefault().getOffset(now)
    hour = ((local / 3_600_000L) % 24L).toInt()
    minute = ((local / 60_000L) % 60L).toInt()
    second = ((local / 1000L) % 60L).toInt()
    digits[0] = ('0' + hour / 10)
    digits[1] = ('0' + hour % 10)
    digits[2] = ('0' + minute / 10)
    digits[3] = ('0' + minute % 10)
    digits[4] = ('0' + second / 10)
    digits[5] = ('0' + second % 10)
  }

  fun syncStrings(now: Long) {
    val tz = TimeZone.getDefault()
    val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
    date = "${cal.get(Calendar.DAY_OF_MONTH)} ${Months[cal.get(Calendar.MONTH)]}"
    weekday = Days[cal.get(Calendar.DAY_OF_WEEK) - 1]
    zone = tz.getDisplayName(false, TimeZone.SHORT)
  }
}

/**
 * Home clock, on the same 312-unit sheet as the console and the cards and in the same bay, so
 * the two read as one instrument panel: when active the bay shows the clock, and when the console
 * takes over or the screen enters ambient center idle the clock unwinds completely off screen,
 * redrawing itself on screen activity.
 *
 * Seconds are carried three ways: as numerals in the field's right column, as the colon that
 * brightens and drops each second, and as a 60-cell comb that fills through the minute and
 * clears on the stroke. The ticker pauses whenever unmounted so idle mode costs zero ticks.
 */
@Composable
fun HudClock(
  isOpen: Boolean,
  modifier: Modifier = Modifier,
  redrawSignal: Int = 0,
) {
  val density = LocalDensity.current
  val crimsonMonetActive = rememberUpdatedState(LocalCrimsonMonetActive.current)
  val face = remember { ClockFace().apply { tick(System.currentTimeMillis()) } }
  val secondTick = remember { mutableIntStateOf(0) }

  val revealAnim = remember { Animatable(0f) }
  var mounted by remember { mutableStateOf(!isOpen) }
  LaunchedEffect(isOpen, redrawSignal) {
    if (isOpen) {
      val hideMs = (revealAnim.value.coerceIn(0f, 1f) * 500f).roundToInt().coerceAtLeast(1)
      revealAnim.animateTo(0f, tween(hideMs, easing = LinearEasing))
      mounted = false
    } else {
      mounted = true
      if (redrawSignal > 0) {
        revealAnim.snapTo(0f)
      }
      val showMs = ((1f - revealAnim.value).coerceIn(0f, 1f) * 650f).roundToInt().coerceAtLeast(1)
      revealAnim.animateTo(1f, tween(showMs, easing = LinearEasing))
    }
  }

  LaunchedEffect(face, mounted) {
    if (!mounted) return@LaunchedEffect
    val initNow = System.currentTimeMillis()
    face.syncStrings(initNow)
    face.tick(initNow)
    secondTick.intValue = (initNow / 1000L).toInt()
    while (true) {
      val now = System.currentTimeMillis()
      face.tick(now)
      secondTick.intValue = (now / 1000L).toInt()
      delay(1000L - now % 1000L + 4L)
    }
  }
  LaunchedEffect(face, mounted) {
    if (!mounted) return@LaunchedEffect
    while (true) {
      delay(20_000L - System.currentTimeMillis() % 20_000L)
      face.syncStrings(System.currentTimeMillis())
    }
  }

  if (!mounted) return

  val eyebrowPaint = remember(density) { monoPaint(density, 7.5f, 1.2f, Paint.Align.LEFT, 120) }
  val timePaint = remember(density) { monoPaint(density, 46f, 0.8f, Paint.Align.CENTER, 255) }
  val wingPaint = remember(density) { monoPaint(density, 8.5f, 1.0f, Paint.Align.LEFT, 185) }
  val wingRightPaint = remember(density) { monoPaint(density, 8.5f, 1.0f, Paint.Align.RIGHT, 185) }
  val secReadoutPaint = remember(density) { monoPaint(density, 10.5f, 0.8f, Paint.Align.RIGHT, 240) }
  val secChars = remember { CharArray(3).apply { this[0] = ':' } }

  Box(
    modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp)
      .height(114.dp)
      .graphicsLayer { alpha = phase(revealAnim.value, 0f, 0.18f) },
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .drawWithCache {
          val ux = size.width / 312f
          val uy = size.height / 114f
          fun X(sx: Float) = sx * ux
          fun Y(sy: Float) = sy * uy

          val cx = size.width * 0.5f
          val arcHalfSpan = size.width * 0.45f
          val arcZenithY = Y(74f)
          val arcWingY = Y(98f)
          val sagitta = (arcWingY - arcZenithY).coerceAtLeast(4f)
          val arcRadius = (arcHalfSpan * arcHalfSpan + sagitta * sagitta) / (2f * sagitta)
          val arcCenterY = arcZenithY + arcRadius
          val maxTheta = asin((arcHalfSpan / arcRadius).coerceIn(-1f, 1f))

          val tickU = FloatArray(60)
          val tickX = FloatArray(60)
          val tickY = FloatArray(60)
          val tickNx = FloatArray(60)
          val tickNy = FloatArray(60)
          for (i in 0 until 60) {
            val u = -1f + 2f * (i / 59f)
            val theta = u * maxTheta
            val s = sin(theta)
            val c = cos(theta)
            tickU[i] = u
            tickX[i] = cx + arcRadius * s
            tickY[i] = arcCenterY - arcRadius * c
            tickNx[i] = s
            tickNy[i] = -c
          }

          val arcPath = Path()
          val arcStroke = Stroke(width = 0.9f.dp.toPx(), cap = StrokeCap.Round)
          val spineStroke = 0.8f.dp.toPx()
          val zenithStroke = 1.3f.dp.toPx()
          val crossArm = 2.8f.dp.toPx()
          val crossStroke = 1.1f.dp.toPx()

          val eyebrowText = "SYS.TIME // ORBITAL"
          val eyebrowWidth = eyebrowPaint.measureText(eyebrowText)
          val eyebrowX = cx - eyebrowWidth * 0.5f
          val eyebrowBaseline = centerBaseline(eyebrowPaint, Y(10f))
          val timeBaseline = centerBaseline(timePaint, Y(41f))
          val wingBaseline = centerBaseline(wingPaint, Y(105f))
          val secBaseline = centerBaseline(secReadoutPaint, Y(105f))
          val secWidth = secReadoutPaint.measureText(":00")

          onDrawBehind {
            val reveal = revealAnim.value
            val ticked = secondTick.intValue
            if (reveal <= 0.001f) return@onDrawBehind

            val trace = phase(reveal, 0f, 0.62f)
            val eyebrowP = phase(reveal, 0.18f, 0.58f)
            val timeP = phase(reveal, 0.22f, 0.72f)
            val wingP = phase(reveal, 0.38f, 0.78f)
            val combP = phase(reveal, 0.50f, 1.00f)
            val isMonet = crimsonMonetActive.value

            // 1. Curved Orbital Horizon Arc tracing outward from zenith center
            if (trace > 0.01f) {
              arcPath.reset()
              val segments = 48
              for (sIdx in 0..segments) {
                val frac = sIdx / segments.toFloat()
                val u = -trace + 2f * trace * frac
                val theta = u * maxTheta
                val px = cx + arcRadius * sin(theta)
                val py = arcCenterY - arcRadius * cos(theta)
                if (sIdx == 0) arcPath.moveTo(px, py) else arcPath.lineTo(px, py)
              }
              drawPath(
                path = arcPath,
                color = if (isMonet) Crimson.copy(alpha = 0.62f) else OrbitalSlate.copy(alpha = 0.52f),
                style = arcStroke,
              )

              // Crimson zenith anchor mark
              drawLine(
                color = CrimsonBright.copy(alpha = 0.9f * phase(trace, 0f, 0.25f)),
                start = Offset(cx, arcZenithY - 3f * uy),
                end = Offset(cx, arcZenithY + 4f * uy),
                strokeWidth = zenithStroke,
                cap = StrokeCap.Round,
              )

              // Tracing laser heads on expanding left & right arc tips
              if (trace in 0.02f..0.98f) {
                val tipTheta = trace * maxTheta
                val tipLx = cx - arcRadius * sin(tipTheta)
                val tipRx = cx + arcRadius * sin(tipTheta)
                val tipY = arcCenterY - arcRadius * cos(tipTheta)
                drawCircle(CrimsonBright.copy(alpha = 0.35f), 4.2f.dp.toPx(), Offset(tipLx, tipY))
                drawCircle(Color.White.copy(alpha = 0.92f), 1.5f.dp.toPx(), Offset(tipLx, tipY))
                drawCircle(CrimsonBright.copy(alpha = 0.35f), 4.2f.dp.toPx(), Offset(tipRx, tipY))
                drawCircle(Color.White.copy(alpha = 0.92f), 1.5f.dp.toPx(), Offset(tipRx, tipY))
              }

              val crossP = phase(trace, 0.85f, 1.0f)
              if (crossP > 0f) {
                drawCross(cx - arcHalfSpan, arcWingY + 4f * uy, crossArm, crossStroke, CrimsonBright.copy(alpha = 0.72f * crossP))
                drawCross(cx + arcHalfSpan, arcWingY + 4f * uy, crossArm, crossStroke, Color.White.copy(alpha = 0.45f * crossP))
              }
            }

            // 2. Centered Eyebrow & Zenith Status Dot
            if (eyebrowP > 0f) {
              drawDecrypt(eyebrowText, eyebrowP, 12, eyebrowPaint, eyebrowX, eyebrowBaseline)
              drawCircle(
                color = (if (ticked % 2 == 0) CrimsonBright else CrimsonDim).copy(alpha = eyebrowP),
                radius = 2.0f.dp.toPx(),
                center = Offset(cx, Y(21f)),
              )
            }

            val canvas = drawContext.canvas.nativeCanvas

            // 3. Giant Frameless HH:MM Numerals & Central Crimson Pulse Spine
            if (timeP > 0f) {
              val timeRise = (1f - timeP) * 4f.dp.toPx()
              clipRect(left = 0f, top = Y(16f), right = size.width, bottom = Y(16f) + Y(52f) * timeP) {
                canvas.drawText(face.digits, 0, 2, cx - 46f * ux, timeBaseline + timeRise, timePaint)
                canvas.drawText(face.digits, 2, 2, cx + 46f * ux, timeBaseline + timeRise, timePaint)
              }

              drawLine(
                color = OrbitalSlate.copy(alpha = 0.35f * timeP),
                start = Offset(cx, Y(26f)),
                end = Offset(cx, Y(55f)),
                strokeWidth = spineStroke,
                cap = StrokeCap.Round,
              )
              val colonColor = (if (ticked % 2 == 0) CrimsonBright else CrimsonDim).copy(
                alpha = if (ticked % 2 == 0) timeP else 0.45f * timeP,
              )
              val pipSize = 3.2f.dp.toPx()
              drawRect(
                color = colonColor,
                topLeft = Offset(cx - pipSize * 0.5f, Y(34f)),
                size = Size(pipSize, pipSize),
              )
              drawRect(
                color = colonColor,
                topLeft = Offset(cx - pipSize * 0.5f, Y(45f)),
                size = Size(pipSize, pipSize),
              )
            }

            // 4. 60-Tick Radial Second Sweep Along the Orbital Arc
            if (combP > 0f) {
              for (cell in 0 until 60) {
                if (abs(tickU[cell]) > combP) continue
                val bx = tickX[cell]
                val by = tickY[cell]
                val nx = tickNx[cell]
                val ny = tickNy[cell]

                val isHead = (cell == face.second)
                val isPassed = (cell <= face.second)
                val isMajor = (cell % 5 == 0)

                val tickLen = when {
                  isHead -> 7.5f * uy
                  isMajor -> 4.6f * uy
                  else -> 2.6f * uy
                }
                val tickStroke = when {
                  isHead -> 1.6f.dp.toPx()
                  isMajor -> 1.1f.dp.toPx()
                  else -> 0.8f.dp.toPx()
                }
                val tickColor = when {
                  isHead -> CrimsonBright.copy(alpha = combP)
                  isPassed -> Color.White.copy(alpha = (if (isMajor) 0.65f else 0.34f) * combP)
                  else -> OrbitalSlate.copy(alpha = (if (isMajor) 0.24f else 0.12f) * combP)
                }

                drawLine(
                  color = tickColor,
                  start = Offset(bx, by),
                  end = Offset(bx + nx * tickLen, by + ny * tickLen),
                  strokeWidth = tickStroke,
                  cap = StrokeCap.Round,
                )

                if (isHead) {
                  val orbCenter = Offset(bx + nx * (tickLen + 2.5f * uy), by + ny * (tickLen + 2.5f * uy))
                  drawCircle(CrimsonBright.copy(alpha = 0.35f * combP), 4.0f.dp.toPx(), orbCenter)
                  drawCircle(CrimsonBright.copy(alpha = combP), 2.1f.dp.toPx(), orbCenter)
                }
              }
            }

            // 5. Left & Right Wing Telemetry Below Arc Wings
            if (wingP > 0f) {
              drawDecrypt("${face.weekday} // ${face.date}", wingP, 19, wingPaint, cx - arcHalfSpan + 6f * ux, wingBaseline)

              val rightWingX = cx + arcHalfSpan - 6f * ux
              secChars[1] = face.digits[4]
              secChars[2] = face.digits[5]
              secReadoutPaint.color = if (ticked % 2 == 0) {
                android.graphics.Color.argb((255 * wingP).toInt(), 255, 77, 94)
              } else {
                android.graphics.Color.argb((235 * wingP).toInt(), 248, 250, 252)
              }
              canvas.drawText(secChars, 0, 3, rightWingX, secBaseline, secReadoutPaint)

              wingRightPaint.alpha = (165 * wingP).toInt()
              canvas.drawText(face.zone, rightWingX - secWidth - 5f * ux, wingBaseline, wingRightPaint)
            }
          }
        },
    )
  }
}
