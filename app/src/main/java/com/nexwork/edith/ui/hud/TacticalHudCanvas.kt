package com.nexwork.edith.ui.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val InkBlack = Color(0xFF000000)
private val DeepGray = Color(0xFF111111)
private val MidGray = Color(0xFF888888)
private val PureWhite = Color(0xFFFFFFFF)

private fun fsin(x: Float): Float = sin(x.toDouble()).toFloat()
private fun fcos(x: Float): Float = cos(x.toDouble()).toFloat()

private data class Dust(val x: Float, val y: Float, val r: Float, val a: Float, val ph: Float)

/**
 * Monochrome tactical HUD: point-cloud wireframe reconnaissance satellite in the
 * right quadrant, secondary orbital node upper-left, vector tracking mesh,
 * telemetry data block, dense star dust, per-frame digital grain and scan sweep.
 * Palette pinned by the brief: #000 #111 #888 #FFF — no crimson.
 */
@Composable
fun TacticalHudCanvas(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "tac")

  // Single 8s master cycle drives dash phase, grain reseed, sweep, blink, wobble
  val t by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 8_000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "t",
  )

  val dust = remember {
    val rnd = Random(0x5A7E)
    List(230) {
      Dust(
        x = rnd.nextFloat(),
        y = rnd.nextFloat(),
        r = 0.5f + rnd.nextFloat() * 1.6f,
        a = 0.12f + rnd.nextFloat() * 0.7f,
        ph = rnd.nextFloat() * 6.2832f,
      )
    }
  }

  val bootTime = remember { System.currentTimeMillis() }
  val textPaint = remember {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
      textAlign = android.graphics.Paint.Align.LEFT
    }
  }

  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val u = w * 0.0022f // geometry unit

    // --- Matte void ---
    drawRect(InkBlack)
    drawRect(
      brush = Brush.radialGradient(
        colors = listOf(DeepGray.copy(alpha = 0.5f), InkBlack),
        center = Offset(w * 0.68f, h * 0.62f),
        radius = w * 0.9f,
      ),
    )

    // --- Star particle dust with twinkle ---
    for (s in dust) {
      val tw = 0.6f + 0.4f * fsin(t * 6.2832f * 2f + s.ph)
      drawCircle(
        color = PureWhite.copy(alpha = s.a * tw),
        radius = s.r.dp.toPx() * 0.5f,
        center = Offset(s.x * w, s.y * h),
      )
    }

    // --- Per-frame digital grain (quantized to ~12fps by the master cycle) ---
    val grainSeed = (t * 100f).toInt()
    val grnd = Random(grainSeed)
    val grainPts = ArrayList<Offset>(620)
    repeat(620) { grainPts.add(Offset(grnd.nextFloat() * w, grnd.nextFloat() * h)) }
    drawPoints(
      points = grainPts,
      pointMode = PointMode.Points,
      color = PureWhite.copy(alpha = 0.05f),
      strokeWidth = 1.1f,
      cap = StrokeCap.Round,
    )

    // --- Faint scanlines + travelling compression band ---
    val scanStep = 3.5.dp.toPx()
    var sy = 0f
    while (sy < h) {
      drawLine(PureWhite.copy(alpha = 0.018f), Offset(0f, sy), Offset(w, sy), 1f)
      sy += scanStep
    }
    val bandY = ((t * 1.6f) % 1.3f - 0.15f) * h
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, PureWhite.copy(alpha = 0.035f), Color.Transparent),
        startY = bandY - 50.dp.toPx(),
        endY = bandY + 50.dp.toPx(),
      ),
    )

    // --- Orbital nodes (tracking crosses) ---
    val nodes = listOf(
      Offset(w * 0.50f, h * 0.30f),
      Offset(w * 0.88f, h * 0.22f),
      Offset(w * 0.16f, h * 0.72f),
      Offset(w * 0.70f, h * 0.86f),
    )
    val dash = PathEffect.dashPathEffect(floatArrayOf(7f, 9f), (t * 90f) % 16f)
    val satA = Offset(w * 0.68f, h * 0.60f)
    val satB = Offset(w * 0.28f, h * 0.11f)

    // --- Vector tracking mesh ---
    for (n in nodes) {
      drawLine(
        color = MidGray.copy(alpha = 0.45f),
        start = satA,
        end = n,
        strokeWidth = 0.9f,
        pathEffect = dash,
      )
    }
    drawLine(MidGray.copy(alpha = 0.35f), satB, nodes[0], 0.9f, pathEffect = dash)
    drawLine(MidGray.copy(alpha = 0.35f), nodes[0], nodes[1], 0.9f, pathEffect = dash)
    drawLine(MidGray.copy(alpha = 0.25f), nodes[2], nodes[3], 0.9f, pathEffect = dash)

    for (n in nodes) {
      val arm = 6.dp.toPx()
      drawLine(PureWhite.copy(alpha = 0.8f), Offset(n.x - arm, n.y), Offset(n.x + arm, n.y), 1.1f)
      drawLine(PureWhite.copy(alpha = 0.8f), Offset(n.x, n.y - arm), Offset(n.x, n.y + arm), 1.1f)
      drawCircle(MidGray.copy(alpha = 0.5f), radius = arm + 3.dp.toPx(), center = n, style = Stroke(0.8f))
    }

    // --- Satellites (slow attitude wobble) ---
    val wobble = fsin(t * 6.2832f) * 1.6f
    rotate(wobble, satB) {
      drawWireSatellite(center = satB, u = u * 0.5f, bodyAlpha = 0.65f, dotAlpha = 0.35f, seed = 11)
    }
    rotate(-wobble * 0.7f, satA) {
      drawWireSatellite(center = satA, u = u, bodyAlpha = 0.92f, dotAlpha = 0.6f, seed = 7)
      // range rings + corner brackets on the primary subject
      drawCircle(
        MidGray.copy(alpha = 0.28f),
        radius = 95.dp.toPx(),
        center = satA,
        style = Stroke(0.9f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 9f))),
      )
      drawCircle(
        MidGray.copy(alpha = 0.16f),
        radius = 125.dp.toPx(),
        center = satA,
        style = Stroke(0.9f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 14f))),
      )
      val bx = 105.dp.toPx()
      val by = 105.dp.toPx()
      val arm = 16.dp.toPx()
      val brk = PureWhite.copy(alpha = 0.85f)
      val bw = 1.6f
      val cs = listOf(
        Triple(satA.x - bx, satA.y - by, 1 to 1),
        Triple(satA.x + bx, satA.y - by, -1 to 1),
        Triple(satA.x - bx, satA.y + by, 1 to -1),
        Triple(satA.x + bx, satA.y + by, -1 to -1),
      )
      for ((cx, cy, s) in cs) {
        drawLine(brk, Offset(cx, cy), Offset(cx + s.first * arm, cy), bw)
        drawLine(brk, Offset(cx, cy), Offset(cx, cy + s.second * arm), bw)
      }
    }

    // --- Telemetry data block, centered-left ---
    val bx0 = 14.dp.toPx()
    var by = h * 0.40f
    val lh = 15.dp.toPx()
    val uptime = (System.currentTimeMillis() - bootTime) / 1000L
    val blink = if (((t * 16f) % 2f) < 1f) "_" else " "
    val lines = listOf(
      "[ ORBITAL TELEMETRY ]" to 0.95f,
      "" to 0f,
      "SAT-07   STATUS: NOMINAL" to 0.85f,
      "ALT    412.7 KM" to 0.7f,
      "VEL    7.66 KM/S" to 0.7f,
      "INC    51.64 DEG" to 0.7f,
      "AOS    %02d:%02d:%02d".format(uptime / 3600, (uptime / 60) % 60, uptime % 60) to 0.7f,
      "TRK    ██████░░░░ 62%" to 0.8f,
      "0x5F3A %04X %04X%s".format(grainSeed * 7 % 65536, grainSeed * 13 % 65536, blink) to 0.5f,
    )
    textPaint.textSize = 11.5.sp.toPx()
    for ((text, a) in lines) {
      if (text.isNotEmpty()) {
        textPaint.color = android.graphics.Color.argb((a * 255).toInt(), 255, 255, 255)
        if (text.startsWith("[")) textPaint.color = android.graphics.Color.argb(242, 200, 200, 200)
        drawContext.canvas.nativeCanvas.drawText(text, bx0, by, textPaint)
      }
      by += lh
    }
    // callout box around title
    textPaint.textSize = 11.5.sp.toPx()
    val titleW = textPaint.measureText(lines[0].first)
    drawRect(
      color = MidGray.copy(alpha = 0.6f),
      topLeft = Offset(bx0 - 5.dp.toPx(), by - lh * lines.size - 12.dp.toPx()),
      size = Size(titleW + 10.dp.toPx(), lh + 6.dp.toPx()),
      style = Stroke(0.9f),
    )
    // leader line from block to primary satellite
    drawLine(
      color = MidGray.copy(alpha = 0.5f),
      start = Offset(bx0 + titleW + 8.dp.toPx(), by - lh * lines.size - lh / 2),
      end = Offset(satA.x - 100.dp.toPx(), satA.y - 60.dp.toPx()),
      strokeWidth = 0.9f,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 7f), (t * 60f) % 12f),
    )
  }
}

/**
 * Point-cloud wireframe reconnaissance satellite drawn in local units `u`:
 * box bus with fake 3/4 perspective, dot-matrix solar wings, dish, antenna boom.
 */
private fun DrawScope.drawWireSatellite(
  center: Offset,
  u: Float,
  bodyAlpha: Float,
  dotAlpha: Float,
  seed: Int,
) {
  val body = PureWhite.copy(alpha = bodyAlpha)
  val back = PureWhite.copy(alpha = bodyAlpha * 0.45f)
  val sw = 1.2f

  fun P(x: Float, y: Float) = Offset(center.x + x * u, center.y + y * u)

  // Bus: front face + offset back face
  val f = listOf(P(-14f, -10f), P(14f, -10f), P(14f, 10f), P(-14f, 10f))
  val b = listOf(P(-9f, -16f), P(19f, -16f), P(19f, 4f), P(-9f, 4f))
  for (i in 0 until 4) {
    drawLine(back, b[i], b[(i + 1) % 4], sw)
    drawLine(body, f[i], f[(i + 1) % 4], sw)
    drawLine(back, f[i], b[i], sw)
  }
  // inner detail lines on front face
  drawLine(body, P(-14f, -2f), P(14f, -2f), 0.7f)
  drawLine(body, P(-4f, -10f), P(-4f, 10f), 0.7f)

  // Solar wings (foreshortened right side for 3/4 view)
  for ((dir, len, al) in listOf(Triple(-1f, 34f, 1f), Triple(1f, 27f, 0.8f))) {
    val x0 = dir * 18f
    val x1 = dir * (18f + len)
    val wing = PureWhite.copy(alpha = bodyAlpha * al)
    drawLine(wing, P(x0, -1f), P(x1, -1f), 1f)
    drawLine(wing, P(x0, 1f), P(x1, 1f), 1f)
    val tipY = 9f
    drawLine(wing, P(x1, -tipY), P(x1, tipY), 1f)
    drawLine(wing, P(x0, -1f), P(x1, -tipY), 0.8f)
    drawLine(wing, P(x0, 1f), P(x1, tipY), 0.8f)
    // transverse cell lines
    for (k in 1..6) {
      val fx = x0 + (x1 - x0) * k / 7f
      val fy = tipY * k / 7f
      drawLine(wing, P(fx, -fy), P(fx, fy), 0.6f)
    }
  }

  // Dish under the bus
  val dishC = P(0f, 16f)
  drawCircle(body.copy(alpha = bodyAlpha * 0.8f), radius = 7f * u, center = dishC, style = Stroke(sw))
  drawArc(
    color = body.copy(alpha = bodyAlpha * 0.5f),
    startAngle = 200f,
    sweepAngle = 140f,
    useCenter = false,
    topLeft = Offset(dishC.x - 4.5f * u, dishC.y - 4.5f * u),
    size = Size(9f * u, 9f * u),
    style = Stroke(0.8f),
  )
  drawLine(body, dishC, P(0f, 10f), 0.8f)

  // Antenna boom with tip crosshair
  drawLine(body, P(8f, -10f), P(13f, -21f), 0.9f)
  drawLine(PureWhite.copy(alpha = 0.9f), P(11f, -21f), P(15f, -21f), 1.1f)
  drawLine(PureWhite.copy(alpha = 0.9f), P(13f, -23f), P(13f, -19f), 1.1f)

  // Point-cloud scatter: dense dot matrix over wings + sparse halo around bus
  val rnd = Random(seed)
  val pts = ArrayList<Offset>(260)
  repeat(120) {
    val side = if (it % 2 == 0) -1f else 1f
    val lx = side * (18f + rnd.nextFloat() * 32f)
    val ly = (rnd.nextFloat() * 2f - 1f) * 8f * (kotlin.math.abs(lx) - 18f) / 32f
    pts.add(P(lx, ly))
  }
  repeat(60) {
    pts.add(P(rnd.nextFloat() * 40f - 20f, rnd.nextFloat() * 28f - 16f))
  }
  repeat(40) {
    val ang = rnd.nextFloat() * 6.2832f
    val rr = 24f + rnd.nextFloat() * 10f
    pts.add(P(fcos(ang) * rr, fsin(ang) * rr))
  }
  drawPoints(
    points = pts,
    pointMode = PointMode.Points,
    color = PureWhite.copy(alpha = dotAlpha),
    strokeWidth = 1.4f,
    cap = StrokeCap.Round,
  )
}
