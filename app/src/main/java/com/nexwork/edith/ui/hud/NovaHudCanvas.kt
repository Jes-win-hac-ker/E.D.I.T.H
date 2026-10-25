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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

private val NovaBg = Color(0xFF000000)
private val NovaFg = Color(0xFFFFFFFF)
private val NovaFgDim = Color(0xFFB0B0B0)

private fun nSin(x: Float): Float = sin(x.toDouble()).toFloat()
private fun nCos(x: Float): Float = cos(x.toDouble()).toFloat()
private const val NTAU = 6.2832f

/**
 * "NOVA" poster HUD — Circinus Galaxy technical plate after Alexander Montes.
 * Faithful monochrome recreation: corner-cut diagonals, Saturn-ring nova glyph,
 * CJK emblem badge, star swarms, crosshatched eye-diamond, hexagonal moiré,
 * vertical star rails, a glowing hero burst and a corner spider-web. Everything
 * hugs the margins; the center stays black void. #000 #FFF #B0B0B0 only.
 */
@Composable
fun NovaHudCanvas(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "nova")
  val t by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(18_000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "t",
  )

  val paint = remember {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
      textAlign = android.graphics.Paint.Align.LEFT
    }
  }

  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    fun U(x: Float) = x * w
    fun V(y: Float) = y * h
    fun P(x: Float, y: Float) = Offset(U(x), V(y))

    drawRect(NovaBg)

    // ============ corner-cut diagonals (top-left motion slashes) ============
    drawLine(NovaFg, P(0.34f, 0.0f), P(0.0f, 0.36f), 1.2.dp.toPx())
    drawLine(NovaFg.copy(alpha = 0.6f), P(0.40f, 0.0f), P(0.0f, 0.42f), 0.8.dp.toPx())
    drawLine(NovaFg.copy(alpha = 0.4f), P(0.46f, 0.0f), P(0.0f, 0.48f), 0.6.dp.toPx())

    // ============ header_top_left ============
    // circular star badge
    drawCircle(NovaFg, U(0.022f), P(0.032f, 0.022f), style = Stroke(1.dp.toPx()))
    drawStar4(P(0.032f, 0.022f), U(0.012f), NovaFg, true, t)
    // micro-paragraph
    paint.textSize = 6.5.sp.toPx()
    paint.color = android.graphics.Color.argb(225, 255, 255, 255)
    paint.letterSpacing = 0.02f
    val para = "The Circinus Galaxy (ESO 97-G13) is a Seyfert galaxy in the constellation of Circinus. It is located 4 degrees below the Galactic plane, and, at a distance of 4.0 Mpc (13 Mly), is one of the closest major galaxies to the Milky Way."
    var line = StringBuilder()
    var py = V(0.014f)
    for (word in para.split(" ")) {
      if (line.length + word.length > 46) {
        drawContext.canvas.nativeCanvas.drawText(line.toString().trimEnd(), U(0.062f), py, paint)
        py += 8.5.sp.toPx()
        line = StringBuilder()
      }
      line.append(word).append(' ')
    }
    drawContext.canvas.nativeCanvas.drawText(line.toString().trimEnd(), U(0.062f), py, paint)

    // Saturn-ring nova glyph (hero mark)
    drawNovaGlyph(P(0.078f, 0.084f), U(0.026f), -22f, t)

    // right-aligned catalog (kept clear of the glyph)
    paint.textSize = 8.5.sp.toPx()
    paint.letterSpacing = 0.12f
    paint.color = android.graphics.Color.WHITE
    paint.textAlign = android.graphics.Paint.Align.RIGHT
    drawContext.canvas.nativeCanvas.drawText("E S O   9 7 - G 1 3", U(0.345f), V(0.078f), paint)
    drawContext.canvas.nativeCanvas.drawText("1 4 0 0   l i g h t - y e a r s", U(0.345f), V(0.090f), paint)
    paint.textAlign = android.graphics.Paint.Align.LEFT
    paint.letterSpacing = 0f
    // header bracket under text
    drawPolyline(listOf(P(0.115f, 0.100f), P(0.26f, 0.100f), P(0.30f, 0.062f)), NovaFgDim, 0.8.dp.toPx())

    // ============ header_top_right — CJK emblem badge ============
    drawRoundRect(
      color = NovaFg,
      topLeft = P(0.845f, 0.010f),
      size = Size(U(0.135f), V(0.070f)),
      cornerRadius = CornerRadius(U(0.018f)),
      style = Stroke(1.4.dp.toPx()),
    )
    // emblem circle with eye
    drawCircle(NovaFg, U(0.016f), P(0.872f, 0.030f), style = Stroke(1.dp.toPx()))
    drawCircle(NovaFg, U(0.006f), P(0.872f, 0.030f))
    drawCircle(NovaBg, U(0.0028f), P(0.872f, 0.030f))
    paint.textSize = 5.5.sp.toPx()
    paint.color = android.graphics.Color.argb(200, 255, 255, 255)
    drawContext.canvas.nativeCanvas.drawText("MADE IN MEXICO", U(0.900f), V(0.030f), paint)
    // 超新星
    paint.textSize = 22.sp.toPx()
    paint.color = android.graphics.Color.WHITE
    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
    drawContext.canvas.nativeCanvas.drawText("超新星", U(0.858f), V(0.070f), paint)
    paint.typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
    // sparkle pluses trailing the CJK
    drawStar4(P(0.968f, 0.062f), U(0.008f), NovaFg, true, t)
    drawStar4(P(0.982f, 0.056f), U(0.005f), NovaFgDim, true, t)

    // ============ left column: star swarms + credits + roundels ============
    drawStarMatrix(P(0.022f, 0.115f), 5, 4, U(0.020f), V(0.014f), U(0.0045f), t)
    drawStarMatrix(P(0.155f, 0.115f), 3, 8, U(0.016f), V(0.010f), U(0.0035f), t)
    // rotated vertical credit block
    rotatedText(paint, P(0.128f, 0.20f), -90f, 6.sp.toPx(), 0.10f, NovaFgDim, "Copyright \\ Alexander Montes")
    rotatedText(paint, P(0.138f, 0.20f), -90f, 6.sp.toPx(), 0.10f, NovaFgDim, "Made in Mexico  2021 \\ Next Gen")
    rotatedText(paint, P(0.148f, 0.20f), -90f, 6.sp.toPx(), 0.10f, NovaFgDim, "San Cristobal de las Casas")
    // two roundels
    drawCircle(NovaFg, U(0.010f), P(0.128f, 0.228f), style = Stroke(0.8.dp.toPx()))
    drawStar4(P(0.128f, 0.228f), U(0.005f), NovaFg, true, t)
    drawCircle(NovaFg, U(0.010f), P(0.150f, 0.228f), style = Stroke(0.8.dp.toPx()))
    drawBurst(P(0.150f, 0.228f), U(0.007f), 8, t * NTAU * 0.2f, NovaFg, U(0.0018f))
    // filled triangle
    val tri = Path().apply { moveTo(U(0.165f), V(0.235f)); lineTo(U(0.205f), V(0.235f)); lineTo(U(0.205f), V(0.265f)); close() }
    drawPath(tri, NovaFg)
    // -45 catalog label
    rotatedText(paint, P(0.02f, 0.30f), -42f, 8.sp.toPx(), 0.14f, NovaFg, "ESO 97-G13  —  1,400 LY")

    // grid rhombus panel with starburst (left, ~y 0.31)
    drawGridRhombus(P(0.115f, 0.345f), U(0.055f), V(0.045f))
    drawBurst(P(0.115f, 0.345f), U(0.020f), 8, -t * NTAU * 0.15f, NovaFg, U(0.004f))
    drawStar4(P(0.115f, 0.345f), U(0.010f), NovaFg, true, t)

    // ============ left vertical star rail + rotated title ============
    drawStarRail(U(0.058f), V(0.42f), V(0.84f), U(0.006f), t)
    drawLine(NovaFg.copy(alpha = 0.5f), P(0.058f, 0.42f), P(0.058f, 0.84f), 0.6.dp.toPx())
    rotatedText(paint, P(0.048f, 0.66f), -90f, 11.sp.toPx(), 0.28f, NovaFg, "N O V A   \\\\   Part of the  \"Origins\"  Serie")

    // ============ right column: diagonal staircase + eye-diamond ============
    drawPolyline(
      listOf(
        P(1.0f, 0.10f), P(0.86f, 0.10f), P(0.86f, 0.16f), P(0.80f, 0.22f),
        P(0.80f, 0.30f), P(0.86f, 0.36f), P(0.86f, 0.44f), P(0.94f, 0.52f),
      ),
      NovaFg, 1.2.dp.toPx(),
    )
    drawPolyline(
      listOf(P(1.0f, 0.14f), P(0.90f, 0.14f), P(0.845f, 0.20f), P(0.845f, 0.30f)),
      NovaFg.copy(alpha = 0.55f), 0.8.dp.toPx(),
    )
    drawEyeRhombus(P(0.905f, 0.195f), U(0.045f), V(0.038f))

    // right star rail (upper) + CIRCINUS label
    drawStarRail(U(0.868f), V(0.30f), V(0.44f), U(0.005f), t)
    rotatedText(paint, P(0.902f, 0.46f), -90f, 8.sp.toPx(), 0.24f, NovaFg, "C I R C I N U S   G A L A X Y")

    // ============ hero nova burst with glow (right, ~y 0.52) ============
    val heroC = P(0.945f, 0.52f)
    val heroR = U(0.045f)
    for (g in 7 downTo 1) drawCircle(NovaFg.copy(alpha = 0.05f * g), heroR * 0.42f * g, heroC)
    drawBurst(heroC, heroR * 2.4f, 8, t * NTAU * 0.08f, NovaFg, heroR * 0.16f)
    drawBurst(heroC, heroR * 1.3f, 8, (t * NTAU * 0.08f) + PI.toFloat() / 8f, NovaFgDim, heroR * 0.08f)
    drawStar4(heroC, heroR, NovaFg, true, t)

    // right star rail (lower) + KOKOPELIO label
    drawStarRail(U(0.868f), V(0.58f), V(0.72f), U(0.005f), t)
    rotatedText(paint, P(0.902f, 0.70f), -90f, 8.sp.toPx(), 0.20f, NovaFgDim, "K O K O P E L I O")

    // ============ bottom-left: hexagonal moiré + KOKOPELI box + stamp ============
    drawStarMatrix(P(0.022f, 0.855f), 4, 3, U(0.018f), V(0.012f), U(0.005f), t)
    drawHexMoire(P(0.155f, 0.935f), U(0.075f), t)
    // KOKOPELI filled callout
    drawRect(NovaFg, P(0.195f, 0.884f), Size(U(0.128f), V(0.028f)))
    paint.textSize = 10.sp.toPx()
    paint.color = android.graphics.Color.BLACK
    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
    drawContext.canvas.nativeCanvas.drawText("KOKOPELI©", U(0.204f), V(0.904f), paint)
    paint.typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
    // small diamond outline
    drawPolyline(
      listOf(Offset(U(0.252f), V(0.932f)), Offset(U(0.266f), V(0.948f)), Offset(U(0.252f), V(0.964f)), Offset(U(0.238f), V(0.948f)), Offset(U(0.252f), V(0.932f))),
      NovaFg, 1.dp.toPx(),
    )
    // MX stamp
    paint.textSize = 9.sp.toPx()
    paint.color = android.graphics.Color.WHITE
    paint.letterSpacing = 0.15f
    val stampX = U(0.285f)
    drawContext.canvas.nativeCanvas.drawText("MX", stampX, V(0.948f), paint)
    drawContext.canvas.nativeCanvas.drawText("2021", stampX, V(0.960f), paint)
    drawContext.canvas.nativeCanvas.drawText("MMXXI", stampX, V(0.972f), paint)
    paint.letterSpacing = 0f
    // circular logo with diamonds bottom-left corner
    drawCircle(NovaFg, U(0.020f), P(0.055f, 0.968f), style = Stroke(1.dp.toPx()))
    drawStar4(P(0.055f, 0.968f), U(0.008f), NovaFg, true, t)
    drawStar4(P(0.055f, 0.968f), U(0.004f), NovaBg, true, t)

    // ============ bottom-right corner spider-web ============
    drawSpiderWeb(P(0.92f, 0.98f), U(0.30f), t * 0.15f)
    drawStar4(P(0.86f, 0.90f), U(0.022f), NovaFg, true, t)
    drawBurst(P(0.86f, 0.90f), U(0.040f), 8, t * NTAU * 0.1f, NovaFg.copy(alpha = 0.7f), U(0.004f))

    // ============ footer credit line ============
    paint.textSize = 6.5.sp.toPx()
    paint.color = android.graphics.Color.argb(200, 255, 255, 255)
    drawContext.canvas.nativeCanvas.drawText(
      "Made with Love and Passion in Mexico (c) 2021  \\\\  Alexander Montes",
      U(0.06f), V(0.990f), paint,
    )
    drawContext.canvas.nativeCanvas.drawText(
      "\"NOVA\"   Part of the Artworks NEXT GEN",
      U(0.06f), V(0.997f), paint,
    )
  }
}

// ---------------------------------------------------------------------------
// primitives
// ---------------------------------------------------------------------------

private fun DrawScope.drawStar4(c: Offset, r: Float, color: Color, filled: Boolean, t: Float = 0f) {
  val twinkle = 0.72f + 0.28f * nSin(t * NTAU * 3f + c.x * 0.05f + c.y * 0.03f)
  val col = if (filled) color.copy(alpha = color.alpha * twinkle) else color
  val path = Path()
  val ir = r * 0.24f
  for (k in 0 until 8) {
    val a = k * PI.toFloat() / 4f - PI.toFloat() / 2f
    val rr = if (k % 2 == 0) r else ir
    val pt = Offset(c.x + rr * nCos(a), c.y + rr * nSin(a))
    if (k == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
  }
  path.close()
  if (filled) drawPath(path, col) else drawPath(path, col, style = Stroke(1.dp.toPx()))
}

private fun DrawScope.drawBurst(c: Offset, r: Float, spikes: Int, rot: Float, color: Color, coreR: Float) {
  val half = spikes / 2f
  for (k in 0 until spikes) {
    val a = k * PI.toFloat() / half + rot
    val len = if (k % 2 == 0) r else r * 0.42f
    drawLine(color, c, Offset(c.x + len * nCos(a), c.y + len * nSin(a)), 1.dp.toPx())
  }
  drawCircle(color, coreR, c)
}

private fun DrawScope.drawNovaGlyph(c: Offset, r: Float, rot: Float, t: Float) {
  rotate(rot, c) {
    drawOval(
      NovaFg.copy(alpha = 0.9f),
      topLeft = Offset(c.x - r, c.y - r * 0.26f),
      size = Size(r * 2f, r * 0.52f),
      style = Stroke(1.1.dp.toPx()),
    )
    drawOval(
      NovaFgDim.copy(alpha = 0.55f),
      topLeft = Offset(c.x - r * 1.18f, c.y - r * 0.34f),
      size = Size(r * 2.36f, r * 0.68f),
      style = Stroke(0.6.dp.toPx()),
    )
  }
  drawStar4(c, r * 0.92f, NovaFg, true, t)
  drawCircle(NovaFg, r * 0.14f, c)
}

private fun DrawScope.drawEyeRhombus(c: Offset, hw: Float, hh: Float) {
  val diamond = Path().apply {
    moveTo(c.x, c.y - hh); lineTo(c.x + hw, c.y); lineTo(c.x, c.y + hh); lineTo(c.x - hw, c.y); close()
  }
  clipPath(diamond) {
    val step = 5.dp.toPx()
    var o = -2f * hh
    while (o <= 2f * hh) {
      drawLine(NovaFg.copy(alpha = 0.45f), Offset(c.x - hw + o, c.y - hh), Offset(c.x - hw + o + 2f * hh, c.y + hh), 0.5.dp.toPx())
      drawLine(NovaFg.copy(alpha = 0.45f), Offset(c.x - hw + o, c.y + hh), Offset(c.x - hw + o + 2f * hh, c.y - hh), 0.5.dp.toPx())
      o += step
    }
  }
  drawPath(diamond, NovaFg, style = Stroke(1.dp.toPx()))
  val ew = hw * 0.52f
  val eh = hh * 0.46f
  val eye = Path().apply {
    moveTo(c.x - ew, c.y)
    quadraticBezierTo(c.x, c.y - eh * 1.7f, c.x + ew, c.y)
    quadraticBezierTo(c.x, c.y + eh * 1.7f, c.x - ew, c.y)
    close()
  }
  drawPath(eye, NovaFg, style = Stroke(1.dp.toPx()))
  drawCircle(NovaFg, eh * 0.52f, c)
  drawCircle(NovaBg, eh * 0.26f, c)
}

private fun DrawScope.drawGridRhombus(c: Offset, hw: Float, hh: Float) {
  val diamond = Path().apply {
    moveTo(c.x, c.y - hh); lineTo(c.x + hw, c.y); lineTo(c.x, c.y + hh); lineTo(c.x - hw, c.y); close()
  }
  clipPath(diamond) {
    val n = 7
    for (i in -n..n) {
      val f = i.toFloat() / n
      drawLine(
        NovaFg.copy(alpha = 0.4f),
        Offset(c.x - hw, c.y + f * hh), Offset(c.x + hw, c.y + f * hh), 0.5.dp.toPx(),
      )
      drawLine(
        NovaFg.copy(alpha = 0.4f),
        Offset(c.x + f * hw, c.y - hh), Offset(c.x + f * hw, c.y + hh), 0.5.dp.toPx(),
      )
    }
  }
  drawPath(diamond, NovaFg, style = Stroke(1.1.dp.toPx()))
}

private fun DrawScope.drawHexMoire(c: Offset, maxR: Float, t: Float) {
  val rings = 26
  for (i in 1..rings) {
    val base = maxR * (i.toFloat() / rings)
    val path = Path()
    var first = true
    var th = 0f
    val wobble = 0.10f + 0.05f * nSin(i * 0.7f + t * NTAU * 0.1f)
    while (th <= NTAU + 0.001f) {
      val hex = 1f / hypot(nCos(th * 3f), nSin(th * 3f) * 0.58f)
      val rr = base * (0.82f + 0.18f * hex) * (1f + wobble * nSin(6f * th + i * 0.5f))
      val pt = Offset(c.x + rr * nCos(th), c.y + rr * nSin(th))
      if (first) { path.moveTo(pt.x, pt.y); first = false } else path.lineTo(pt.x, pt.y)
      th += 0.06f
    }
    path.close()
    drawPath(path, NovaFg.copy(alpha = 0.4f + 0.5f * (i.toFloat() / rings)), style = Stroke(0.6.dp.toPx()))
  }
}

private fun DrawScope.drawSpiderWeb(c: Offset, maxR: Float, rot: Float) {
  val spokes = 22
  for (k in 0 until spokes) {
    val a = k * NTAU / spokes + rot
    drawLine(NovaFg.copy(alpha = 0.5f), c, Offset(c.x + maxR * nCos(a), c.y + maxR * nSin(a)), 0.6.dp.toPx())
  }
  val rings = 11
  for (i in 1..rings) {
    val rr = maxR * (i.toFloat() / rings).pow(1.3f)
    val path = Path()
    var first = true
    for (k in 0..spokes) {
      val a = (k % spokes) * NTAU / spokes + rot
      val rad = rr * (1f + 0.04f * nSin(k * 3f))
      val pt = Offset(c.x + rad * nCos(a), c.y + rad * nSin(a))
      if (first) { path.moveTo(pt.x, pt.y); first = false } else path.lineTo(pt.x, pt.y)
    }
    path.close()
    drawPath(path, NovaFg.copy(alpha = 0.3f + 0.4f * i / rings), style = Stroke(0.6.dp.toPx()))
  }
}

private fun DrawScope.drawStarRail(x: Float, yTop: Float, yBot: Float, r: Float, t: Float) {
  val n = 10
  val step = (yBot - yTop) / n
  var y = yTop
  var i = 0
  while (y <= yBot + 0.5f) {
    when (i % 4) {
      0 -> drawStar4(Offset(x, y), r * 2.4f, NovaFg, true, t)
      1 -> drawStar4(Offset(x, y), r * 1.1f, NovaFg, true, t)
      2 -> drawCircle(NovaFg, r * 0.5f, Offset(x, y))
      else -> drawStar4(Offset(x, y), r * 1.7f, NovaFgDim, true, t)
    }
    y += step; i++
  }
}

private fun DrawScope.drawPolyline(pts: List<Offset>, color: Color, stroke: Float) {
  for (i in 0 until pts.size - 1) {
    drawLine(color, pts[i], pts[i + 1], stroke, cap = StrokeCap.Round)
  }
}

private fun DrawScope.rotatedText(
  paint: android.graphics.Paint,
  anchor: Offset,
  deg: Float,
  sizePx: Float,
  tracking: Float,
  color: Color,
  text: String,
) {
  rotate(deg, anchor) {
    val p = android.graphics.Paint(paint)
    p.textSize = sizePx
    p.letterSpacing = tracking
    p.color = android.graphics.Color.argb(
      (color.alpha * 255).toInt(),
      (color.red * 255).toInt(),
      (color.green * 255).toInt(),
      (color.blue * 255).toInt(),
    )
    drawContext.canvas.nativeCanvas.drawText(text, anchor.x, anchor.y, p)
  }
}

private fun DrawScope.drawStarMatrix(o: Offset, cols: Int, rows: Int, sx: Float, sy: Float, r: Float, t: Float) {
  var idx = 0
  for (c in 0 until cols) for (rr in 0 until rows) {
    drawStar4(Offset(o.x + c * sx, o.y + rr * sy), r, NovaFg, filled = true, t = t + idx * 0.13f)
    idx++
  }
}
