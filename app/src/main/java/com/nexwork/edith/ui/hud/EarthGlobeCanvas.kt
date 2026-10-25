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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private val VoidTint = Color(0xFF0B0B0C)
private val DeepTint = Color(0xFF1A1B1D)
private val SteelTint = Color(0xFF8A8D91)
private val PaperTint = Color(0xFFF0F2F5)

// Coarse world landmass grid: 64 cols x 32 rows, '#' = land.
// Stylized generative cartography — continental silhouettes, not survey-grade.
private val LAND_MAP = listOf(
  "................................................................",
  "................................................................",
  ".........................#####..................................",
  ".................#####..#######.......................#.........",
  "..............########.#########..##..########..................",
  ".............#######################.###########################",
  ".............######################..###########################",
  "..............###################.##############################",
  "...............#################.###############################",
  "................###############.###############################.",
  "..................############..##############################..",
  "....................#########..#############################....",
  ".....................#######...###########################......",
  "......................######...#########################........",
  ".......................#####....#####################...........",
  "........................####....###################.............",
  "........................####.....########.########..............",
  "........................####......###......##..####.............",
  ".........................###.......#.......#.######.............",
  ".........................###.......#........######..............",
  ".........................##........#.........####...........",
  ".........................##...................###............",
  "........................##....................##.............",
  "........................#...........................#........",
  "........................#....................................",
  ".........................#...................................",
  "................................................................",
  "................................................................",
  "................................................................",
  "################################################################",
  "################################################################",
  "................................................................",
)

private fun gSin(x: Float): Float = sin(x.toDouble()).toFloat()
private fun gCos(x: Float): Float = cos(x.toDouble()).toFloat()

private data class Hub(val name: String, val lat: Double, val lon: Double)

private val HUBS = listOf(
  Hub("NYC", 40.7, -74.0),
  Hub("LON", 51.5, -0.1),
  Hub("DEL", 28.6, 77.2),
  Hub("TYO", 35.7, 139.7),
  Hub("GRU", -23.5, -46.6),
  Hub("SYD", -33.9, 151.2),
  Hub("DXB", 25.2, 55.3),
  Hub("SIN", 1.35, 103.8),
  Hub("JNB", -26.2, 28.0),
  Hub("CAI", 30.0, 31.2),
)

/**
 * Generative point-cloud Earth: orthographic globe cropped close, formed by
 * latitude raster arcs + continental dot-matrix point clouds + luminous
 * network hubs, slow spin, dithered matte-black void.
 * Palette pinned by brief: #0b0b0c #1a1b1d #8a8d91 #f0f2f5.
 */
@Composable
fun EarthGlobeCanvas(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "globe")

  // One full rotation per 90s; starts on the Asian landmass
  val spin by transition.animateFloat(
    initialValue = 0.62f,
    targetValue = 1.62f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 90_000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "spin",
  )

  // Land point cloud: jittered dots per '#' cell in (lat, lon) radians
  val landPts = remember {
    val rnd = Random(0xEA47)
    val out = ArrayList<Pair<Float, Float>>(1400)
    LAND_MAP.forEachIndexed { row, rawLine ->
      val line = rawLine.padEnd(64, '.')
      line.forEachIndexed { col, ch ->
        if (ch == '#') {
          repeat(4) {
            val lat = (90.0 - (row + 0.5 + rnd.nextFloat() - 0.5f) * 5.625) * PI / 180.0
            val lon = (-180.0 + (col + 0.5 + rnd.nextFloat() - 0.5f) * 5.625) * PI / 180.0
            out.add(lat.toFloat() to lon.toFloat())
          }
        }
      }
    }
    out
  }

  val textPaint = remember {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
      textAlign = android.graphics.Paint.Align.LEFT
    }
  }

  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height

    // --- Matte void with faint dithered core ---
    drawRect(VoidTint)
    drawRect(
      brush = Brush.radialGradient(
        colors = listOf(DeepTint.copy(alpha = 0.55f), Color.Transparent),
        center = Offset(w * 0.55f, h * 0.55f),
        radius = w,
      ),
    )
    val grnd = Random((spin * 240f).toInt())
    val specks = ArrayList<Offset>(420)
    repeat(420) { specks.add(Offset(grnd.nextFloat() * w, grnd.nextFloat() * h)) }
    drawPoints(specks, PointMode.Points, PaperTint.copy(alpha = 0.04f), 1.1f, cap = StrokeCap.Round)

    // --- Globe frame: slightly right, cropped close ---
    val cx = w * 0.55f
    val cy = h * 0.54f
    val r = w * 0.86f
    val camLat = 0.42f // oblique from ~24 deg N
    val camLon = spin * 6.2832f - 3.1416f

    fun project(latR: Float, lonR: Float): Triple<Float, Float, Float>? {
      val cl = gCos(latR)
      val dl = lonR - camLon
      val x = cl * gSin(dl)
      val y = gCos(camLat) * gSin(latR) - gSin(camLat) * cl * gCos(dl)
      val z = gSin(camLat) * gSin(latR) + gCos(camLat) * cl * gCos(dl)
      if (z < 0f) return null
      return Triple(cx + x * r, cy - y * r, z)
    }

    // --- Limb + halo ---
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(PaperTint.copy(alpha = 0.05f), Color.Transparent),
        center = Offset(cx, cy),
        radius = r * 1.12f,
      ),
      radius = r * 1.12f,
      center = Offset(cx, cy),
    )
    drawCircle(
      SteelTint.copy(alpha = 0.30f),
      radius = r,
        center = Offset(cx, cy),
      style = Stroke(1.1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), spin * 40f)),
    )

    // --- Latitude raster arcs ---
    for (latDeg in -75..75 step 15) {
      val latR = (latDeg * PI / 180.0).toFloat()
      var prev: Offset? = null
      var lon = -PI.toFloat()
      while (lon <= PI.toFloat()) {
        val p = project(latR, lon)
        if (p != null) {
          val pt = Offset(p.first, p.second)
          prev?.let { a ->
            drawLine(
              color = PaperTint.copy(alpha = 0.10f + 0.10f * p.third),
              start = a,
              end = pt,
              strokeWidth = 0.8f,
            )
          }
          prev = pt
        } else {
          prev = null
        }
        lon += 0.05f
      }
    }

    // --- Meridian hairlines ---
    for (mDeg in 0 until 180 step 30) {
      val mR = (mDeg * PI / 180.0).toFloat()
      var prev: Offset? = null
      var lat = -PI.toFloat() / 2f
      while (lat <= PI.toFloat() / 2f) {
        val p = project(lat, mR) ?: run { prev = null; lat += 0.06f; continue }
        val pt = Offset(p.first, p.second)
        prev?.let { drawLine(SteelTint.copy(alpha = 0.10f * p.third), it, pt, 0.7f) }
        prev = pt
        lat += 0.06f
      }
      // anti-meridian twin
      prev = null
      var lat2 = -PI.toFloat() / 2f
      while (lat2 <= PI.toFloat() / 2f) {
        val p = project(lat2, mR + PI.toFloat()) ?: run { prev = null; lat2 += 0.06f; continue }
        val pt = Offset(p.first, p.second)
        prev?.let { drawLine(SteelTint.copy(alpha = 0.10f * p.third), it, pt, 0.7f) }
        prev = pt
        lat2 += 0.06f
      }
    }

    // --- Continental point cloud ---
    val cloud = ArrayList<Offset>(landPts.size)
    val cloudAlpha = ArrayList<Float>(landPts.size)
    for ((latR, lonR) in landPts) {
      val p = project(latR, lonR)
      if (p != null) {
        cloud.add(Offset(p.first, p.second))
        cloudAlpha.add(0.20f + 0.75f * p.third)
      }
    }
    // layered draw: dim base + bright rim-weighted pass
    drawPoints(cloud, PointMode.Points, PaperTint.copy(alpha = 0.34f), 1.7f, cap = StrokeCap.Round)
    val bright = ArrayList<Offset>(cloud.size / 2)
    for (i in cloud.indices) {
      if (cloudAlpha[i] > 0.55f) bright.add(cloud[i])
    }
    drawPoints(bright, PointMode.Points, PaperTint.copy(alpha = 0.9f), 1.2f, cap = StrokeCap.Round)

    // --- Network hubs + links ---
    val hubPts = ArrayList<Offset>(HUBS.size)
    for (hub in HUBS) {
      val p = project((hub.lat * PI / 180.0).toFloat(), (hub.lon * PI / 180.0).toFloat())
      if (p != null) {
        val pt = Offset(p.first, p.second)
        hubPts.add(pt)
        val pulse = 0.5f + 0.5f * gSin(spin * 6.2832f * 6f + p.first * 0.05f)
        drawCircle(PaperTint.copy(alpha = 0.9f), radius = 2.2f, center = pt)
        drawCircle(
            PaperTint.copy(alpha = 0.25f + 0.35f * pulse),
            radius = 5f + 4f * pulse,
            center = pt,
            style = Stroke(0.8f),
        )
        textPaint.textSize = 7.5.sp.toPx()
        textPaint.color = android.graphics.Color.argb((140f * p.third + 40f).toInt(), 240, 242, 245)
        drawContext.canvas.nativeCanvas.drawText(hub.name, pt.x + 9f, pt.y + 3f, textPaint)
      }
    }
    for (i in 0 until hubPts.size - 1) {
      val a = hubPts[i]
      val b = hubPts[i + 1]
      if (hypot(a.x - b.x, a.y - b.y) < r * 1.4f) {
        drawLine(
          SteelTint.copy(alpha = 0.35f),
          a,
          b,
          0.7f,
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 8f), -spin * 120f),
        )
      }
    }

    // --- Hairline horizontal axes + coordinate typography ---
    val axisYs = listOf(h * 0.16f, h * 0.88f)
    for (ay in axisYs) {
      drawLine(SteelTint.copy(alpha = 0.14f), Offset(0f, ay), Offset(w, ay), 0.8f)
    }
    textPaint.textSize = 9.5.sp.toPx()
    textPaint.color = android.graphics.Color.argb(150, 138, 141, 145)
    val subLat = 24.0
    val subLon = (((-camLon * 180.0 / PI) + 540.0) % 360.0) - 180.0
    drawContext.canvas.nativeCanvas.drawText(
      "NADIR  %5.1f N  %6.1f E".format(subLat, subLon),
      12.dp.toPx(),
      h * 0.16f - 6.dp.toPx(),
      textPaint,
    )
    drawContext.canvas.nativeCanvas.drawText(
      "ORBIT  LEO 412 KM  INC 51.6",
      12.dp.toPx(),
      h * 0.88f + 14.dp.toPx(),
      textPaint,
    )
    textPaint.textAlign = android.graphics.Paint.Align.RIGHT
    drawContext.canvas.nativeCanvas.drawText(
      "GEO//POINT-CLOUD RASTER",
      w - 12.dp.toPx(),
      h * 0.16f - 6.dp.toPx(),
      textPaint,
    )
    textPaint.textAlign = android.graphics.Paint.Align.LEFT

    // --- Faint scanlines ---
    var sy = 0f
    val step = 3.5.dp.toPx()
    while (sy < h) {
      drawLine(PaperTint.copy(alpha = 0.012f), Offset(0f, sy), Offset(w, sy), 1f)
      sy += step
    }
  }
}
