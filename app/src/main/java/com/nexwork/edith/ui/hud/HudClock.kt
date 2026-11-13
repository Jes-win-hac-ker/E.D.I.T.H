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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.CrimsonDim
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
 * the two read as one instrument panel: at rest the bay shows the clock, on summon the console
 * takes it over and the clock unwinds. It initialises the way everything else here does — the
 * keyline traces itself, the brackets snap on at each corner, the designator decrypts, the time
 * prints out left to right and the seconds comb ticks in last.
 *
 * Seconds are carried three ways: as numerals in the field's right column, as the colon that
 * brightens and drops each second, and as a 60-cell comb that fills through the minute and
 * clears on the stroke. Nothing here moves between second boundaries — the sheet is a permanent
 * home-screen element, so it redraws once a second, on the tick, rather than every frame.
 */
@Composable
fun HudClock(
  isOpen: Boolean,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val face = remember { ClockFace().apply { tick(System.currentTimeMillis()) } }
  val secondTick = remember { mutableIntStateOf(0) }
  LaunchedEffect(face) {
    face.syncStrings(System.currentTimeMillis())
    while (true) {
      val now = System.currentTimeMillis()
      face.tick(now)
      secondTick.intValue = (now / 1000L).toInt()
      delay(1000L - now % 1000L + 4L)
    }
  }
  LaunchedEffect(face) {
    while (true) {
      delay(20_000L - System.currentTimeMillis() % 20_000L)
      face.syncStrings(System.currentTimeMillis())
    }
  }

  val revealAnim = remember { Animatable(0f) }
  var mounted by remember { mutableStateOf(!isOpen) }
  LaunchedEffect(isOpen) {
    if (isOpen) {
      revealAnim.animateTo(0f, tween(1000, easing = LinearEasing))
      mounted = false
    } else {
      mounted = true
      revealAnim.animateTo(1f, tween(1200, easing = LinearEasing))
    }
  }

  if (!mounted) return

  val eyebrowPaint = remember(density) { monoPaint(density, 7.5f, 1.1f, Paint.Align.LEFT, 107) }
  val timePaint = remember(density) { monoPaint(density, 26f, 1.4f, Paint.Align.LEFT, 255) }
  val colonPaint = remember(density) { monoPaint(density, 26f, 0f, Paint.Align.LEFT, 255) }
  val secPaint = remember(density) { monoPaint(density, 20f, 0.5f, Paint.Align.LEFT, 255) }
  val captionPaint = remember(density) { monoPaint(density, 7.5f, 1.1f, Paint.Align.RIGHT, 107) }
  val weekdayPaint = remember(density) { monoPaint(density, 11f, 0.8f, Paint.Align.CENTER, 140) }
  val zonePaint = remember(density) { monoPaint(density, 7.5f, 1.1f, Paint.Align.CENTER, 107) }

  Box(
    modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp, vertical = 84.dp)
      .height(64.dp)
      .graphicsLayer { alpha = phase(revealAnim.value, 0f, 0.22f) },
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .drawWithCache {
          val ux = size.width / 312f
          val uy = size.height / 64f
          fun X(sx: Float) = sx * ux
          fun Y(sy: Float) = sy * uy
          val hair = 0.6f.dp.toPx()

          val gridBitmap = cachedHudGridBitmap(size.width.roundToInt(), size.height.roundToInt(), 64f, hair)
          val platePath = Path().apply {
            moveTo(X(260f), Y(2f))
            lineTo(size.width - 8f * ux, Y(2f))
            lineTo(size.width, Y(2f) + 8f * uy)
            lineTo(size.width, Y(62f))
            lineTo(X(260f), Y(62f))
            close()
          }
          val bar = 1.2f.dp.toPx()
          val plateStroke = Stroke(width = 0.8f.dp.toPx())
          val perimeter = 2f * (size.width + size.height)
          val cornerAt = floatArrayOf(
            0f,
            size.width / perimeter,
            (size.width + size.height) / perimeter,
            (2f * size.width + size.height) / perimeter,
          )

          val crossArm = 3f.dp.toPx()
          val crossStroke = 1.2f.dp.toPx()
          val stroke = 1f.dp.toPx()

          val eyebrowBaseline = centerBaseline(eyebrowPaint, Y(17f))
          val timeBaseline = centerBaseline(timePaint, Y(37f))
          val colonBaseline = centerBaseline(colonPaint, Y(37f))
          val secBaseline = centerBaseline(secPaint, Y(24f))
          val captionBaseline = centerBaseline(captionPaint, Y(44f))
          val weekdayBaseline = centerBaseline(weekdayPaint, Y(24f))
          val zoneBaseline = centerBaseline(zonePaint, Y(40f))
          val timeAdvance = timePaint.measureText("MM") - timePaint.measureText("M")
          val secAdvance = secPaint.measureText("MM") - secPaint.measureText("M")
          val timeLeft = X(33f)
          val timeRight = timeLeft + timeAdvance * 5f
          val secRight = X(248f)
          val colonX = timeLeft + timeAdvance * 2f

          val combLeft = X(33f)
          val combRight = X(248f)
          val combStep = (combRight - combLeft) / 60f
          val combCell = combStep * 0.55f
          val combTop = Y(56f)
          val combH = 2.4f * uy
          val combRise = 1.4f * uy

          onDrawBehind {
            val reveal = revealAnim.value
            val ticked = secondTick.intValue
            if (reveal <= 0.001f) return@onDrawBehind

            val trace = phase(reveal, 0f, 0.6f)
            val eyebrowP = phase(reveal, 0.34f, 0.68f)
            val timeP = phase(reveal, 0.3f, 0.72f)
            val secP = phase(reveal, 0.48f, 0.74f)
            val captionP = phase(reveal, 0.56f, 0.78f)
            val plateP = phase(reveal, 0.52f, 0.8f)
            val crossP = phase(reveal, 0.42f, 0.6f)
            val combP = phase(reveal, 0.62f, 1f)

            fun drawPlateAndGrid() {
              drawPath(platePath, Color(0xFF0F141E))
              drawPath(platePath, CrimsonDim, style = plateStroke)
              gridBitmap?.let { drawImage(it) }
            }

            val plateClip = phase(trace, 0f, cornerAt[1])
            if (plateClip < 0.999f) {
              clipRect(right = size.width * plateClip) { drawPlateAndGrid() }
            } else {
              drawPlateAndGrid()
            }

            if (crossP > 0f) {
              drawCross(X(9.5f), Y(16.5f), crossArm, crossStroke, CrimsonBright.copy(0.7f * crossP))
              drawCross(X(9.5f), Y(56.5f), crossArm, crossStroke, Color.White.copy(0.4f * crossP))
            }

            val canvas = drawContext.canvas.nativeCanvas
            if (eyebrowP > 0f) {
              drawDecrypt("SYS.TIME // T-00", eyebrowP, 12, eyebrowPaint, timeLeft, eyebrowBaseline)
              if (eyebrowP > 0.5f) {
                drawCircle(
                  color = if (ticked % 2 == 0) CrimsonBright else CrimsonDim,
                  radius = 2.2f * ux,
                  center = Offset(X(18f), Y(16.5f)),
                )
              }
            }
            if (timeP > 0f) {
              if (timeP < 0.999f) {
                clipRect(right = timeLeft + (timeRight - timeLeft) * timeP) {
                  canvas.drawText(face.digits, 0, 2, timeLeft, timeBaseline, timePaint)
                  canvas.drawText(face.digits, 2, 2, timeLeft + timeAdvance * 3f, timeBaseline, timePaint)
                }
              } else {
                canvas.drawText(face.digits, 0, 2, timeLeft, timeBaseline, timePaint)
                canvas.drawText(face.digits, 2, 2, timeLeft + timeAdvance * 3f, timeBaseline, timePaint)
              }
              if (timeP >= 1f) {
                colonPaint.color = if (ticked % 2 == 0) {
                  android.graphics.Color.argb(255, 255, 77, 94)
                } else {
                  android.graphics.Color.argb(70, 224, 20, 50)
                }
                canvas.drawText(":", colonX, colonBaseline, colonPaint)
              }
            }
            if (secP > 0f) {
              secPaint.color = if (ticked % 2 == 0) {
                android.graphics.Color.argb((255 * secP).toInt(), 255, 255, 255)
              } else {
                android.graphics.Color.argb((220 * secP).toInt(), 203, 213, 225)
              }
              canvas.drawText(face.digits, 4, 2, secRight - secAdvance * 2f, secBaseline, secPaint)
            }
            if (captionP > 0f) {
              captionPaint.alpha = (120 * captionP).toInt()
              if (captionP < 0.999f) {
                val width = captionPaint.measureText(face.date)
                clipRect(right = secRight - width + width * captionP) {
                  canvas.drawText(face.date, secRight, captionBaseline, captionPaint)
                }
              } else {
                canvas.drawText(face.date, secRight, captionBaseline, captionPaint)
              }
            }
            if (combP > 0f) {
              val head = face.second
              val shown = combP * 60f
              repeat(60) { cell ->
                if (cell >= shown || cell > head) return@repeat
                val isHead = (cell == head)
                val major = cell % 5 == 0
                val cellColor = if (isHead) CrimsonBright else Color.White.copy(alpha = (if (major) 0.55f else 0.30f) * combP)
                val topPos = if (isHead) combTop - combRise * 1.4f else if (major) combTop - combRise else combTop
                val height = if (isHead) combH + combRise * 1.4f else if (major) combH + combRise else combH
                val cellW = if (isHead) combCell * 1.3f else combCell

                drawRect(
                  color = cellColor,
                  topLeft = Offset(combLeft + cell * combStep, topPos),
                  size = Size(cellW, height),
                )
                if (isHead) {
                  drawCircle(
                    color = CrimsonBright.copy(alpha = 0.5f * combP),
                    radius = combCell * 1.6f,
                    center = Offset(combLeft + cell * combStep + cellW / 2f, topPos),
                  )
                }
              }
            }
            if (plateP > 0f) {
              weekdayPaint.alpha = (230 * plateP).toInt()
              zonePaint.alpha = (140 * plateP).toInt()
              canvas.drawText(face.weekday, X(286f), weekdayBaseline, weekdayPaint)
              canvas.drawText(face.zone, X(286f), zoneBaseline, zonePaint)
            }

            val bracketTint = Color.White
            drawHudCornerBrackets(
              trace = trace,
              cornerAt = cornerAt,
              barPx = bar,
              tint = bracketTint,
              trTint = CrimsonBright.copy(alpha = 0.85f),
              blTint = bracketTint,
            )

            drawRect(
              color = CrimsonBright.copy(alpha = 0.75f * phase(reveal, 0.85f, 1f)),
              topLeft = Offset(X(283.5f), Y(52f)),
              size = Size(3f * ux, 3f * uy),
            )

            drawTracedRect(
              width = size.width - stroke,
              height = size.height - stroke,
              progress = trace,
              strokeWidth = stroke,
              color = Color.White.copy(alpha = 0.85f),
              head = if (trace in 0.02f..0.98f) 1f else 0f,
            )
          }
        },
    )
  }
}
