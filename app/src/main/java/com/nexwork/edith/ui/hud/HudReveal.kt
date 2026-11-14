package com.nexwork.edith.ui.hud

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/**
 * Perimeter trace of a rectangle, walked clockwise from the top-left — the frame builds
 * itself before anything else resolves. Inset by half the stroke so it is not clipped.
 * `head` puts a bright tracing dot on the leading end while the trace is still running.
 */
internal fun DrawScope.drawTracedRect(
  width: Float,
  height: Float,
  progress: Float,
  strokeWidth: Float,
  color: Color,
  head: Float = 0f,
) {
  if (width <= 0f || height <= 0f || progress <= 0f) return
  val inset = strokeWidth / 2f
  val x0 = inset
  val y0 = inset
  var remain = 2f * (width + height) * progress.coerceIn(0f, 1f)
  var from = Offset(x0, y0)
  fun step(to: Offset) {
    drawLine(color, from, to, strokeWidth, cap = StrokeCap.Round)
    from = to
  }
  val top = minOf(remain, width)
  step(Offset(x0 + top, y0))
  remain -= top
  if (remain > 0f) {
    val right = minOf(remain, height)
    step(Offset(x0 + width, y0 + right))
    remain -= right
  }
  if (remain > 0f) {
    val bottom = minOf(remain, width)
    step(Offset(x0 + width - bottom, y0 + height))
    remain -= bottom
  }
  if (remain > 0f) {
    step(Offset(x0, y0 + height - minOf(remain, height)))
  }
  if (head > 0f && progress < 1f) {
    drawCircle(Color.White.copy(alpha = 0.22f * head), strokeWidth * 5f, from)
    drawCircle(Color.White.copy(alpha = 0.95f * head), strokeWidth * 1.6f, from)
  }
}

/** Baseline y for text whose optical centre should sit at [centreY]. */
internal fun centerBaseline(paint: Paint, centreY: Float): Float {
  val fm = paint.fontMetrics
  return centreY - (fm.ascent + fm.descent) / 2f
}

/** Monospace HUD readout: [trackingSp] is extra tracking in sp, [alpha] is 0..255. */
internal fun monoPaint(
  density: Density,
  sizeSp: Float,
  trackingSp: Float,
  align: Paint.Align,
  alpha: Int,
): Paint = with(density) {
  Paint().apply {
    isAntiAlias = true
    typeface = Typeface.MONOSPACE
    textSize = sizeSp.sp.toPx()
    letterSpacing = trackingSp / sizeSp
    textAlign = align
    color = android.graphics.Color.argb(alpha, 255, 255, 255)
  }
}

/** A 0..1 slice of the reveal, so each element of a card resolves in its own slot. */
internal fun phase(reveal: Float, from: Float, to: Float): Float =
  ((reveal - from) / (to - from)).coerceIn(0f, 1f)

internal fun hudEaseOutCubic(t: Float): Float {
  val inv = 1f - t.coerceIn(0f, 1f)
  return 1f - inv * inv * inv
}

internal fun hudEaseInCubic(t: Float): Float {
  val x = t.coerceIn(0f, 1f)
  return x * x * x
}

private const val GlyphPool = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789#%&*+=/<>_"

/** Deterministic noise char: same seed + slot + salt always decrypts to the same glyph. */
internal fun decryptGlyph(seed: Int, index: Int, salt: Int): Char {
  var h = seed * 31 + index * 131 + salt * 977
  h = h xor (h ushr 13)
  h *= 0x5bd1e995
  h = h xor (h ushr 15)
  return GlyphPool[(h and 0x7fffffff) % GlyphPool.length]
}

/**
 * Monospace text that resolves left to right out of cipher noise. The settled prefix is
 * drawn in one run; every still-unresolved slot is drawn one glyph at a time as a
 * deterministic noise character, dimmer and slightly off-baseline, so the word decodes
 * rather than fades. Requires a left-aligned [paint] — the tail is placed by advance.
 */
internal fun DrawScope.drawDecrypt(
  text: String,
  progress: Float,
  seed: Int,
  paint: Paint,
  x: Float,
  baseline: Float,
  jitter: Float = 0f,
) {
  if (text.isEmpty()) return
  val canvas = drawContext.canvas.nativeCanvas
  if (progress >= 1f) {
    canvas.drawText(text, x, baseline, paint)
    return
  }
  val resolved = (progress * (text.length + 1)).toInt().coerceAtMost(text.length)
  if (resolved > 0) canvas.drawText(text, 0, resolved, x, baseline, paint)
  if (resolved >= text.length) return
  val advance = paint.measureText("MM") - paint.measureText("M")
  val salt = (progress * 18f).toInt()
  val settledAlpha = paint.alpha
  paint.alpha = (settledAlpha * 0.62f).toInt()
  val buffer = StringBuffer()
  for (i in resolved until text.length) {
    val ch = if (text[i] == ' ') ' ' else decryptGlyph(seed, i, salt)
    buffer.append(ch)
    val wobble = if (jitter == 0f || text[i] == ' ') 0f else jitter * sin((i * 2.4f) + salt * 1.7f)
    canvas.drawText(buffer, 0, 1, x + i * advance, baseline + wobble, paint)
    buffer.setLength(0)
  }
  paint.alpha = settledAlpha
}

/**
 * One card's slice of a master 0..1 sweep: each step owns a unit of the timeline and
 * starts `stagger` units after the previous one, so the whole deck deals in (or out)
 * from a single animation driver.
 */
internal fun staggeredReveal(
  master: Float,
  step: Int,
  steps: Int,
  stagger: Float = 0.24f,
): Float {
  if (steps <= 1) return master.coerceIn(0f, 1f)
  val span = 1f + stagger * (steps - 1)
  return (master * span - stagger * step).coerceIn(0f, 1f)
}

/** Front of the reveal: frame and grid, snapped in early. */
internal fun frameProgress(reveal: Float): Float = (reveal * 2.6f).coerceIn(0f, 1f)

/** Back of the reveal: text and icon, arriving after the frame is drawn. */
internal fun contentProgress(reveal: Float): Float = ((reveal - 0.3f) / 0.7f).coerceIn(0f, 1f)
