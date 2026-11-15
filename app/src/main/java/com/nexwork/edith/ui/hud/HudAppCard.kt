package com.nexwork.edith.ui.hud

import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.model.AppFootprint
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.CrimsonDim
import com.nexwork.edith.theme.LocalCrimsonMonetActive
import kotlin.math.roundToInt

internal val RadialNodeSlate = Color(0xFF64748B)
internal val RadialNodeHeight = 66.dp

/** Builds the Option 4 chamfered silhouette shared by app and category radial nodes. */
internal fun radialNodePath(w: Float, h: Float, cut: Float): Path {
  val c = cut.coerceAtMost(minOf(w, h) * 0.35f)
  return Path().apply {
    moveTo(0f, 0f)
    lineTo(w - c, 0f)
    lineTo(w, c)
    lineTo(w, h)
    lineTo(c, h)
    lineTo(0f, h - c)
    close()
  }
}

/** Draws the chamfered surface fill, trace border, bottom usage rail, and charge wavefront. */
internal fun DrawScope.drawRadialNodeShell(
  path: Path,
  reveal: Float,
  leader: Boolean,
  isMonet: Boolean,
  usageFraction: Float,
  charge: Float,
  chargeFill: Brush,
) {
  val bg = when {
    isMonet && leader -> Color(0xEB0A0A0A)
    isMonet -> Color(0xCC0A0A0A)
    leader -> Color(0xEB080B10)
    else -> Color(0xCC07090D)
  }
  val borderColor = when {
    leader && isMonet -> CrimsonBright.copy(alpha = 0.88f)
    leader -> CrimsonBright.copy(alpha = 0.78f)
    isMonet -> CrimsonDim.copy(alpha = 0.65f)
    else -> RadialNodeSlate.copy(alpha = 0.45f)
  }

  val revealP = phase(reveal, 0f, 0.58f)
  if (revealP < 0.999f) {
    clipRect(right = size.width * revealP) {
      drawPath(path, bg)
      drawPath(path, borderColor, style = Stroke(width = if (leader) 1.25.dp.toPx() else 0.9.dp.toPx()))
    }
  } else {
    drawPath(path, bg)
    drawPath(path, borderColor, style = Stroke(width = if (leader) 1.25.dp.toPx() else 0.9.dp.toPx()))
  }

  if (charge > 0f) {
    val headX = size.width * charge.coerceIn(0f, 1f)
    clipRect(right = headX) {
      drawPath(path, brush = chargeFill)
    }
    drawLine(
      color = CrimsonBright.copy(alpha = 0.92f),
      start = Offset(headX, 2.dp.toPx()),
      end = Offset(headX, size.height - 2.dp.toPx()),
      strokeWidth = 1.5.dp.toPx(),
    )
  }

  // Bottom orbital usage bar
  val meterP = phase(reveal, 0.48f, 0.96f)
  val barStart = 54.dp.toPx()
  val barMaxW = (size.width - barStart - 16.dp.toPx()).coerceAtLeast(12.dp.toPx())
  val barY = size.height - 6.dp.toPx()
  drawLine(
    color = RadialNodeSlate.copy(alpha = 0.22f * revealP),
    start = Offset(barStart, barY),
    end = Offset(barStart + barMaxW, barY),
    strokeWidth = 1.6.dp.toPx(),
    cap = StrokeCap.Round,
  )
  val filledW = barMaxW * usageFraction.coerceIn(0.05f, 1f) * meterP
  if (filledW > 1f) {
    drawLine(
      color = if (leader) CrimsonBright.copy(alpha = 0.88f) else RadialNodeSlate.copy(alpha = 0.65f),
      start = Offset(barStart, barY),
      end = Offset(barStart + filledW, barY),
      strokeWidth = 1.8.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }

  // Right-edge tether terminal anchor dot
  val anchorAlpha = phase(reveal, 0.35f, 0.8f)
  drawCircle(
    color = (if (leader) CrimsonBright else RadialNodeSlate).copy(alpha = 0.85f * anchorAlpha),
    radius = if (leader) 2.6.dp.toPx() else 1.8.dp.toPx(),
    center = Offset(size.width - 1.dp.toPx(), size.height * 0.5f),
  )
}

/**
 * Option 4 Radial Target Node representing one indexed app anchored beside the corner Earth.
 */
@Composable
fun HudAppCard(
  app: AppEntry,
  modifier: Modifier = Modifier,
  usageFraction: Float = 0.24f,
  footprint: AppFootprint? = null,
  reveal: () -> Float = { 1f },
  charge: () -> Float = { 0f },
  focused: () -> Boolean = { true },
  slotIndex: Int = 0,
) {
  val density = LocalDensity.current
  val crimsonMonetActive = rememberUpdatedState(LocalCrimsonMonetActive.current)
  val monochrome = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
  val iconBitmap = remember(app.packageName, app.icon) { app.cachedIconBitmap() }
  val indexPrefix = remember(slotIndex) { "%02d".format(slotIndex + 1) }
  val label = remember(app.label, indexPrefix) { "$indexPrefix ${app.label.uppercase()}" }
  val meta = remember(app, footprint) {
    val base = cardMetaLine(app)
    val fp = footprint?.let(::compactFootprint)
    if (fp != null) "$base  ·  $fp" else base
  }
  val iconRise = remember(density) { with(density) { 2.dp.toPx() } }

  val titlePaint = remember(density) { monoPaint(density, 13.5f, 1.0f, Paint.Align.LEFT, 250) }
  val metaPaint = remember(density) { monoPaint(density, 8.8f, 0.8f, Paint.Align.LEFT, 150) }
  val meter = usageFraction.coerceIn(0.05f, 1f)

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(RadialNodeHeight)
      .graphicsLayer {
        alpha = phase(reveal(), 0f, 0.2f)
      }
      .drawWithCache {
        val chamfer = 9.dp.toPx()
        val nodePath = radialNodePath(size.width, size.height, chamfer)
        val chargeFill = Brush.horizontalGradient(
          0f to CrimsonBright.copy(alpha = 0.06f),
          1f to CrimsonBright.copy(alpha = 0.30f),
          startX = 0f,
          endX = size.width,
        )
        val textLeft = 54.dp.toPx()
        val textRight = size.width - 14.dp.toPx()
        val advance = titlePaint.measureText("MM") - titlePaint.measureText("M")
        val maxChars = ((textRight - textLeft) / advance).toInt().coerceAtLeast(4)
        val shownLabel =
          if (label.length <= maxChars) label else label.take(maxChars - 1) + "…"
        val titleBaseline = centerBaseline(titlePaint, size.height * 0.38f)
        val metaBaseline = centerBaseline(metaPaint, size.height * 0.65f)
        val ringCenter = Offset(28.dp.toPx(), size.height * 0.5f)
        val ringR = 16.dp.toPx()
        val seed = app.label.hashCode()

        onDrawBehind {
          val progress = reveal()
          if (progress <= 0.001f) return@onDrawBehind
          val leader = focused()
          val isMonet = crimsonMonetActive.value

          drawRadialNodeShell(
            path = nodePath,
            reveal = progress,
            leader = leader,
            isMonet = isMonet,
            usageFraction = meter,
            charge = charge(),
            chargeFill = chargeFill,
          )

          // Target ring around app icon
          val ringP = phase(progress, 0.16f, 0.65f)
          if (ringP > 0.01f) {
            drawArc(
              color = if (leader) CrimsonBright.copy(alpha = 0.88f * ringP) else RadialNodeSlate.copy(alpha = 0.42f * ringP),
              startAngle = -90f,
              sweepAngle = 290f * ringP,
              useCenter = false,
              topLeft = Offset(ringCenter.x - ringR, ringCenter.y - ringR),
              size = Size(ringR * 2f, ringR * 2f),
              style = Stroke(width = if (leader) 1.3.dp.toPx() else 0.9.dp.toPx(), cap = StrokeCap.Round),
            )
          }

          val textP = phase(progress, 0.25f, 0.72f)
          if (textP > 0.001f) {
            val canvas = drawContext.canvas.nativeCanvas
            titlePaint.color = android.graphics.Color.WHITE
            titlePaint.alpha = ((if (leader) 250 else 205) * textP).roundToInt()
            drawDecrypt(shownLabel, textP, seed, titlePaint, textLeft, titleBaseline)

            metaPaint.color = android.graphics.Color.argb(
              ((if (leader) 175 else 130) * textP).roundToInt(),
              190,
              200,
              215,
            )
            val metaShown = if (meta.length <= maxChars + 6) meta else meta.take(maxChars + 5) + "…"
            canvas.drawText(metaShown, textLeft, metaBaseline, metaPaint)
          }
        }
      },
  ) {
    Layout(
      modifier = Modifier.fillMaxSize(),
      content = {
        Box(
          modifier = Modifier
            .graphicsLayer {
              val settle = phase(reveal(), 0.22f, 0.62f)
              alpha = settle
              scaleX = 0.88f + 0.12f * settle
              scaleY = 0.88f + 0.12f * settle
              translationY = (1f - settle) * iconRise
            }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.8.dp, Color.White.copy(alpha = 0.18f), CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          if (iconBitmap != null) {
            Image(
              bitmap = iconBitmap,
              contentDescription = null,
              colorFilter = monochrome,
              modifier = Modifier.fillMaxSize(0.68f),
            )
          } else {
            Text(
              text = app.initial.toString(),
              color = Color.White,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      },
    ) { measurables, constraints ->
      val iconPx = 28.dp.roundToPx().coerceAtLeast(1)
      val leftPx = (28.dp.toPx() - iconPx / 2f).roundToInt()
      val topPx = ((constraints.maxHeight - iconPx) / 2f).roundToInt()
      val placeable = measurables[0].measure(Constraints.fixed(iconPx, iconPx))
      layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeRelative(leftPx, topPx)
      }
    }
  }
}

private fun cardMetaLine(app: AppEntry): String {
  val minutes = app.usageTimeMillis14d / 60_000L
  val hours = minutes / 60L
  val usage = if (hours > 0) "${hours}H ${minutes % 60}M" else "${minutes}M"
  val tag = when {
    app.isEssentialQuickAccess -> "ESSENTIAL"
    app.isPinned -> "PINNED"
    else -> "14D"
  }
  return "$usage  ·  $tag"
}

private fun compactFootprint(footprint: AppFootprint): String {
  val total = footprint.appBytes + footprint.dataBytes + footprint.cacheBytes
  val mb = total / (1024.0 * 1024.0)
  return if (mb >= 1024.0) "${((mb / 1024.0) * 10).roundToInt() / 10f}G" else "${mb.roundToInt()}M"
}
