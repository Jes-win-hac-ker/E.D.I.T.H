package com.nexwork.edith.ui.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.nexwork.edith.model.AppEntry

private const val MAX_ICON_PX = 96

private data class CachedIcon(val drawable: Drawable, val bitmap: ImageBitmap)
private data class HudGridKey(val widthPx: Int, val heightPx: Int, val designH: Int, val hairTenths: Int)

private val iconCache = object : LruCache<String, CachedIcon>(192) {}
private val gridCache = object : LruCache<HudGridKey, ImageBitmap>(8) {}

/** Computes aspect-preserving target bounds capped at [maxPx] on the longest edge. */
internal fun computeScaledDimensions(srcWidth: Int, srcHeight: Int, maxPx: Int = MAX_ICON_PX): Pair<Int, Int> {
  val w = srcWidth.coerceAtLeast(1)
  val h = srcHeight.coerceAtLeast(1)
  if (w <= maxPx && h <= maxPx) return w to h
  val scale = maxPx.toFloat() / maxOf(w, h)
  return (w * scale).toInt().coerceIn(1, maxPx) to (h * scale).toInt().coerceIn(1, maxPx)
}

/** Down-samples an app drawable once so list rows and card decks reuse a compact texture. */
internal fun Drawable.toImageBitmap(maxPx: Int = MAX_ICON_PX): ImageBitmap? {
  return try {
    if (this is BitmapDrawable && bitmap != null && !bitmap.isRecycled) {
      if (bitmap.width <= maxPx && bitmap.height <= maxPx) {
        return bitmap.asImageBitmap()
      }
      val (w, h) = computeScaledDimensions(bitmap.width, bitmap.height, maxPx)
      return Bitmap.createScaledBitmap(bitmap, w, h, true).asImageBitmap()
    }
    val (w, h) = computeScaledDimensions(intrinsicWidth, intrinsicHeight, maxPx)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    bmp.asImageBitmap()
  } catch (_: Throwable) {
    null
  }
}

/** Returns a cached [ImageBitmap] for [entry], rasterizing on first miss. */
internal fun AppEntry.cachedIconBitmap(): ImageBitmap? {
  val d = icon ?: return null
  val existing = iconCache.get(packageName)
  if (existing != null && existing.drawable === d) {
    return existing.bitmap
  }
  val rendered = d.toImageBitmap() ?: return null
  iconCache.put(packageName, CachedIcon(d, rendered))
  return rendered
}

/** Pre-rasterizes app icons off the main thread so scrolling cards triggers zero bitmap uploads. */
internal fun warmAppIcons(apps: List<AppEntry>) {
  apps.forEach { app ->
    if (app.icon != null) {
      app.cachedIconBitmap()
    }
  }
}

/** Pre-renders both HUD hairline grids once per plate size so Skia skips software path masks. */
internal fun cachedHudGridBitmap(
  widthPx: Int,
  heightPx: Int,
  designH: Float,
  hairPx: Float,
): ImageBitmap? {
  if (widthPx <= 0 || heightPx <= 0 || designH <= 4f || hairPx <= 0f) return null
  val key = HudGridKey(widthPx, heightPx, designH.toInt(), (hairPx * 10f).toInt())
  gridCache.get(key)?.let { return it }

  val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  val ux = widthPx / 312f
  val uy = heightPx / designH
  fun x(sx: Float) = sx * ux
  fun y(sy: Float) = sy * uy

  val fieldPaint = Paint().apply {
    shader = LinearGradient(
      0f, 0f, 0f, heightPx.toFloat(),
      android.graphics.Color.argb(0, 255, 255, 255),
      android.graphics.Color.argb(43, 255, 255, 255),
      Shader.TileMode.CLAMP,
    )
  }
  var gx = 10f
  while (gx < 259f) {
    canvas.drawRect(x(gx), y(2f), x(gx) + hairPx, y(designH - 2f), fieldPaint)
    gx += 10f
  }
  var gy = 6f
  val maxGy = designH - 2f
  while (gy < maxGy) {
    canvas.drawRect(x(2f), y(gy), x(259f), y(gy) + hairPx, fieldPaint)
    gy += 10f
  }

  val columnPaint = Paint().apply {
    shader = LinearGradient(
      0f, 0f, 0f, heightPx.toFloat(),
      android.graphics.Color.argb(13, 255, 255, 255),
      android.graphics.Color.argb(31, 255, 255, 255),
      Shader.TileMode.CLAMP,
    )
  }
  var rx = 269f
  while (rx < 304f) {
    canvas.drawRect(x(rx), y(2f), x(rx) + hairPx, y(designH - 2f), columnPaint)
    rx += 10f
  }
  var ry = 6f
  val maxRy = designH - 4f
  while (ry < maxRy) {
    canvas.drawRect(x(260f), y(ry), x(310f), y(ry) + hairPx, columnPaint)
    ry += 10f
  }

  val image = bmp.asImageBitmap()
  gridCache.put(key, image)
  return image
}

/** Draws the four HUD corner brackets as plain GPU quads instead of compound Path masks. */
internal fun DrawScope.drawHudCornerBrackets(
  trace: Float,
  cornerAt: FloatArray,
  barPx: Float,
  tint: Color,
  trTint: Color,
  blTint: Color,
) {
  val w = size.width
  val h = size.height
  if (trace >= cornerAt[0]) {
    val v = 17.5f.dp.toPx()
    val l = 18f.dp.toPx()
    drawRect(tint, Offset.Zero, Size(barPx, v))
    drawRect(tint, Offset.Zero, Size(l, barPx))
  }
  if (trace >= cornerAt[1]) {
    val v = 17.5f.dp.toPx()
    val l = 32.5f.dp.toPx()
    drawRect(trTint, Offset(w - barPx, 0f), Size(barPx, v))
    drawRect(trTint, Offset(w - l, 0f), Size(l, barPx))
  }
  if (trace >= cornerAt[2]) {
    val v = 6f.dp.toPx()
    val l = 39f.dp.toPx()
    drawRect(tint, Offset(w - barPx, h - v), Size(barPx, v))
    drawRect(tint, Offset(w - l, h - barPx), Size(l, barPx))
  }
  if (trace >= cornerAt[3]) {
    val v = 11.5f.dp.toPx()
    val l = 44.5f.dp.toPx()
    drawRect(blTint, Offset(0f, h - v), Size(barPx, v))
    drawRect(blTint, Offset(0f, h - barPx), Size(l, barPx))
  }
}
