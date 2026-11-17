package com.nexwork.edith.ui.hud

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.CrimsonDim
import com.nexwork.edith.theme.LocalCrimsonMonetActive
import com.nexwork.edith.theme.LocalLauncherStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.model.AppFootprint
import kotlin.math.roundToInt

/**
 * HUD app card on a 312 x 124 sheet. It does not slide in — it initialises: the keyline
 * constructs itself clockwise from the top-left with a bright tracing head, each corner
 * bracket snaps on as the trace passes it, the app name decrypts out of cipher noise, the
 * meta line wipes in, the percentage rolls up and the meter cells and storage strip tick
 * in last. Every one of those phases is read from `reveal()` inside the draw pass, so the
 * whole build costs no recomposition and allocates nothing per frame.
 *
 * `charge()` is the auto-launch ramp: a filled wavefront sweeps left to right across the
 * sheet under the type, with the head band and hairline drawn last so the scan cuts over the
 * label instead of hiding behind it.
 */
@Composable
fun HudAppCard(
  app: AppEntry,
  modifier: Modifier = Modifier,
  usageFraction: Float = 0.24f,
  footprint: AppFootprint? = null,
  unreadCount: Int = 0,
  reveal: () -> Float = { 1f },
  charge: () -> Float = { 0f },
  focused: () -> Boolean = { true },
) {
  val density = LocalDensity.current
  val crimsonMonetActive = rememberUpdatedState(LocalCrimsonMonetActive.current)
  val style = LocalLauncherStyle.current
  val styleState = rememberUpdatedState(style)
  val monochrome = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
  val iconBitmap = remember(app.packageName, app.icon) { app.cachedIconBitmap() }
  val ghost = remember(app.label) { app.label.filter { it.isLetterOrDigit() }.take(2).uppercase() }
  val fineText = remember(footprint) { footprint?.let(::footprintLine) }
  val label = remember(app.label) { app.label.uppercase() }
  val meta = remember(app, unreadCount) { cardMetaLine(app, unreadCount) }
  val peakValue = remember(usageFraction) { (usageFraction.coerceIn(0.04f, 1f) * 100f).roundToInt() }
  val iconRise = remember(density) { with(density) { 2.dp.toPx() } }
  val leaderBrush = remember(style.voidTop, style.voidBottom) {
    Brush.verticalGradient(listOf(style.voidTop.copy(alpha = 0.88f), style.voidBottom.copy(alpha = 0.88f)))
  }
  val stackBrush = remember(style.voidTop, style.voidBottom) {
    Brush.verticalGradient(listOf(style.voidTop.copy(alpha = 0.72f), style.voidBottom.copy(alpha = 0.72f)))
  }

  val labelPaint = remember(density) {
    monoPaint(density, 17f, 1.6f, Paint.Align.LEFT, 255)
  }
  val metaPaint = remember(density) {
    monoPaint(density, 9.5f, 1f, Paint.Align.LEFT, 140)
  }
  val percentPaint = remember(density) {
    monoPaint(density, 16f, 0.5f, Paint.Align.LEFT, 230)
  }
  val peakPaint = remember(density) {
    monoPaint(density, 7.5f, 1.1f, Paint.Align.LEFT, 107)
  }
  val ghostPaint = remember(density) {
    monoPaint(density, 20f, 0f, Paint.Align.CENTER, 46)
  }
  val finePaint = remember(density) {
    monoPaint(density, 7.5f, 0f, Paint.Align.RIGHT, 88)
  }

  val meter = usageFraction.coerceIn(0.04f, 1f)

  Box(
    modifier = modifier
      .aspectRatio(312f / 124f)
      .graphicsLayer {
        alpha = phase(reveal(), 0f, 0.22f)
      }
      .drawBehind {
        drawRect(brush = if (focused()) leaderBrush else stackBrush)
      },
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .drawWithCache {
          val ux = size.width / 312f
          val uy = size.height / 124f
          fun X(sx: Float) = sx * ux
          fun Y(sy: Float) = sy * uy
          val hair = 0.6f.dp.toPx()

          val gridBitmap = cachedHudGridBitmap(size.width.roundToInt(), size.height.roundToInt(), 124f, hair)
          val platePath = Path().apply {
            moveTo(X(260f), Y(2f))
            lineTo(size.width - 8f * ux, Y(2f))
            lineTo(size.width, Y(2f) + 8f * uy)
            lineTo(size.width, Y(122f))
            lineTo(X(260f), Y(122f))
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
          val crosses = arrayOf(
            Offset(X(9.5f), Y(16.5f)) to 0.5f,
            Offset(X(9.5f), Y(114.5f)) to 0.5f,
            Offset(X(236f), Y(20f)) to 0.38f,
          )
          val cellW = 7.4f.dp.toPx()
          val cellGap = 2.4f.dp.toPx()
          val cellH = 3f.dp.toPx()
          val cellRadius = CornerRadius(0.8f.dp.toPx(), 0.8f.dp.toPx())
          val meterX = X(22f)
          val meterY = Y(107f)
          val stripStart = X(150f)
          val stripRight = X(252f)
          val stripW = stripRight - stripStart
          val stripTop = Y(104f)
          val stripH = 2f.dp.toPx()
          val stripGap = 1.6f.dp.toPx()
          val minStripPx = 2f.dp.toPx()
          val stroke = 1f.dp.toPx()
          val keyline = size.width - stroke
          val keylineH = size.height - stroke

          val chargeLeft = X(2f)
          val chargeRight = X(310f)
          val chargeTop = Y(2f)
          val chargeSpan = Y(122f) - Y(2f)
          val headW = 18f.dp.toPx()
          val scanAccent = styleState.value.accent
          val chargeFill = Brush.horizontalGradient(
            0f to scanAccent.copy(alpha = 0.05f),
            1f to scanAccent.copy(alpha = 0.24f),
            startX = chargeLeft,
            endX = chargeRight,
          )
          val chargeHead = Brush.horizontalGradient(
            0f to Color.Transparent,
            headW to scanAccent.copy(alpha = 0.48f),
            headW * 2f to Color.Transparent,
          )

          val labelLeft = X(81f)
          val labelRight = X(229f)
          val advance = labelPaint.measureText("MM") - labelPaint.measureText("M")
          val maxChars = ((labelRight - labelLeft) / advance).toInt().coerceAtLeast(1)
          val shownLabel =
            if (label.length <= maxChars) label else label.take(maxChars - 1) + "…"
          val labelBaseline = centerBaseline(labelPaint, Y(50f))
          val metaBaseline = centerBaseline(metaPaint, Y(72f))
          val percentBaseline = centerBaseline(percentPaint, Y(50f))
          val peakBaseline = centerBaseline(peakPaint, Y(72f))
          val ghostBaseline = centerBaseline(ghostPaint, Y(32f))
          val fineBaseline = centerBaseline(finePaint, Y(117f))
          val percentText = "$peakValue%"
          val percentAdvance =
            percentPaint.measureText("MM") - percentPaint.measureText("M")
          val percentRight = labelRight + X(19f)
          val peakRight = percentRight
          val peakTextWidth = peakPaint.measureText("OF PEAK")
          val seed = app.label.hashCode()
          val rollRise = 3f.dp.toPx()

          onDrawBehind {
            val progress = reveal()
            if (progress <= 0.001f) return@onDrawBehind
            val leader = focused()
            val charge = charge().coerceIn(0f, 1f)
            val headX = chargeLeft + charge * (chargeRight - chargeLeft)

            val trace = phase(progress, 0f, 0.55f)
            val labelP = phase(progress, 0.35f, 0.72f)
            val metaP = phase(progress, 0.5f, 0.78f)
            val crossP = phase(progress, 0.42f, 0.6f)
            val ghostP = phase(progress, 0.55f, 0.72f)
            val meterP = phase(progress, 0.6f, 0.86f)
            val rollP = phase(progress, 0.62f, 0.95f)
            val stripP = phase(progress, 0.7f, 1f)
            val fineP = phase(progress, 0.82f, 1f)

            val activeStyle = styleState.value
            val leaderTint = activeStyle.graphicAccent
            fun drawPlateAndGrid() {
              val isMonet = crimsonMonetActive.value
              val plateColor = when {
                isMonet && leader -> Color(0xD90A0A0A)
                isMonet -> Color(0xB80A0A0A)
                leader -> activeStyle.voidBottom.copy(alpha = 0.88f)
                else -> activeStyle.voidBottom.copy(alpha = 0.72f)
              }
              drawPath(platePath, plateColor)
              drawPath(
                platePath,
                if (leader) leaderTint.copy(alpha = activeStyle.focusedBorderAlpha * 0.82f) else leaderTint.copy(alpha = activeStyle.idleBorderAlpha * 0.65f),
                style = plateStroke,
              )
              gridBitmap?.let { drawImage(it) }
            }

            val plateClip = phase(trace, 0f, cornerAt[1])
            if (plateClip < 0.999f) {
              clipRect(right = size.width * plateClip) { drawPlateAndGrid() }
            } else {
              drawPlateAndGrid()
            }

            if (charge > 0f) {
              clipRect(left = chargeLeft, top = chargeTop, right = headX, bottom = chargeTop + chargeSpan) {
                drawRect(
                  brush = chargeFill,
                  topLeft = Offset(chargeLeft, chargeTop),
                  size = Size(chargeRight - chargeLeft, chargeSpan),
                  blendMode = BlendMode.Plus,
                )
              }
            }

            if (crossP > 0f) {
              crosses.forEachIndexed { idx, (centre, alpha) ->
                val color = if (idx == 0 && leader) activeStyle.accent.copy(alpha * crossP) else Color.White.copy(alpha * crossP)
                drawCross(
                  centre.x, centre.y, crossArm, crossStroke,
                  color,
                )
              }
            }

            if (ghostP > 0f) {
              ghostPaint.alpha = (46 * ghostP).toInt()
              drawContext.canvas.nativeCanvas.drawText(ghost, X(285f), ghostBaseline, ghostPaint)
            }

            val litCells = (meterP * 10f * (peakValue / 100f).coerceAtLeast(0.1f)).toInt().coerceIn(1, 10)
            repeat(10) { cell ->
              val isLit = cell < litCells
              val isHighUsage = isLit && (cell >= 7) && peakValue > 75
              val cellColor = if (isHighUsage) {
                CrimsonBright.copy(alpha = meterP)
              } else if (isLit) {
                Color.White.copy(alpha = 0.88f * meterP)
              } else {
                Color.White.copy(alpha = 0.15f * meterP)
              }
              drawRoundRect(
                color = cellColor,
                topLeft = Offset(meterX + cell * (cellW + cellGap), meterY),
                size = Size(cellW, cellH),
                cornerRadius = cellRadius,
              )
            }

            if (footprint != null && stripP > 0f) {
              val total = footprint.totalBytes.coerceAtLeast(1L)
              val parts = footprintParts(footprint)
              val usable = ((stripW - stripGap * (parts.size - 1)) * stripP).coerceAtLeast(0f)
              var cursor = stripStart
              parts.forEach { part ->
                val width = (usable * part.bytes.toDouble() / total.toDouble())
                  .toFloat()
                  .coerceAtLeast(minStripPx)
                val partColor = if (part.unit == "PKG" && peakValue > 75) CrimsonBright.copy(alpha = part.alpha * stripP) else Color.White.copy(alpha = part.alpha * stripP)
                drawRect(
                  color = partColor,
                  topLeft = Offset(cursor, stripTop),
                  size = Size(width, stripH),
                )
                cursor += width + stripGap
              }
            }
            if (fineText != null && fineP > 0f) {
              finePaint.alpha = (88 * fineP).toInt()
              if (fineP < 0.999f) {
                clipRect(right = stripStart + stripW * fineP) {
                  drawContext.canvas.nativeCanvas.drawText(fineText, stripRight, fineBaseline, finePaint)
                }
              } else {
                drawContext.canvas.nativeCanvas.drawText(fineText, stripRight, fineBaseline, finePaint)
              }
            }

            if (metaP > 0f) {
              if (metaP < 0.999f) {
                clipRect(right = labelLeft + (labelRight - labelLeft) * metaP) {
                  drawContext.canvas.nativeCanvas.drawText(meta, labelLeft, metaBaseline, metaPaint)
                }
              } else {
                drawContext.canvas.nativeCanvas.drawText(meta, labelLeft, metaBaseline, metaPaint)
              }
            }
            if (labelP > 0f) {
              drawDecrypt(shownLabel, labelP, seed, labelPaint, labelLeft, labelBaseline, jitter = labelPaint.textSize * 0.06f)
            }

            if (rollP > 0f) {
              val shownPercent = if (rollP >= 1f) percentText else "${(peakValue * rollP).roundToInt()}%"
              if (peakValue > 75 && leader) {
                percentPaint.color = android.graphics.Color.argb((235 * rollP).toInt(), 255, 77, 94)
              } else {
                percentPaint.color = android.graphics.Color.argb((230 * rollP).toInt(), 255, 255, 255)
              }
              drawContext.canvas.nativeCanvas.drawText(
                shownPercent,
                percentRight - shownPercent.length * percentAdvance,
                percentBaseline + (1f - rollP) * rollRise,
                percentPaint,
              )
              peakPaint.alpha = 107
              drawContext.canvas.nativeCanvas.drawText("OF PEAK", peakRight - peakTextWidth, peakBaseline, peakPaint)
            }

            val bracketTint = Color.White.copy(if (leader) 1f else 0.5f)
            drawHudCornerBrackets(
              trace = trace,
              cornerAt = cornerAt,
              barPx = bar,
              tint = bracketTint,
              trTint = if (leader) activeStyle.accent.copy(alpha = activeStyle.focusedBorderAlpha) else bracketTint,
              blTint = if (leader) activeStyle.graphicAccent.copy(alpha = activeStyle.idleBorderAlpha) else bracketTint,
            )

            drawRect(
              color = if (app.isEssentialQuickAccess || app.isPinned) activeStyle.accent.copy(alpha = 0.88f * meterP) else Color.White.copy(alpha = 0.22f * meterP),
              topLeft = Offset(X(283.5f), Y(110f)),
              size = Size(3f * ux, 3f * uy),
            )

            drawTracedRect(
              width = keyline,
              height = keylineH,
              progress = trace,
              strokeWidth = stroke,
              color = if (leader) activeStyle.graphicAccent.copy(alpha = activeStyle.focusedBorderAlpha) else activeStyle.graphicAccent.copy(alpha = activeStyle.idleBorderAlpha * 0.72f),
              head = if (trace in 0.02f..0.98f) 1f else 0f,
            )

            if (charge > 0f) {
              withTransform({ translate(headX - headW, chargeTop) }) {
                drawRect(
                  brush = chargeHead,
                  size = Size(headW * 2f, chargeSpan),
                  blendMode = BlendMode.Plus,
                )
              }
              drawLine(
                color = activeStyle.accent.copy(alpha = 0.55f + 0.45f * charge),
                start = Offset(headX, chargeTop),
                end = Offset(headX, chargeTop + chargeSpan),
                strokeWidth = (1.5f + 1.0f * charge).dp.toPx(),
                blendMode = BlendMode.Plus,
              )
            }
          }
        },
    )

    val iconShape = remember { RoundedCornerShape(percent = 25) }
    Layout(
      modifier = Modifier.fillMaxSize(),
      content = {
        Box(
          modifier = Modifier
            .graphicsLayer {
              val settle = phase(reveal(), 0.28f, 0.6f)
              alpha = settle
              scaleX = 0.92f + 0.08f * settle
              scaleY = 0.92f + 0.08f * settle
              translationY = (1f - settle) * iconRise
            }
            .clip(iconShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.28f), iconShape),
          contentAlignment = Alignment.Center,
        ) {
          if (iconBitmap != null) {
            androidx.compose.foundation.Image(
              bitmap = iconBitmap,
              contentDescription = null,
              colorFilter = monochrome,
              modifier = Modifier.fillMaxSize(30.2f / 44.5f),
            )
          } else {
            Text(
              text = app.initial.toString(),
              color = Color.White,
              fontSize = 21.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      },
    ) { measurables, constraints ->
      val boxPx = (constraints.maxWidth * (44.5f / 312f)).roundToInt().coerceAtLeast(1)
      val leftPx = (constraints.maxWidth * (24f / 312f)).roundToInt()
      val topPx = (constraints.maxHeight * (39.75f / 124f)).roundToInt()
      val placeable = measurables[0].measure(Constraints.fixed(boxPx, boxPx))
      layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeRelative(leftPx, topPx)
      }
    }
  }
}

internal fun DrawScope.drawCross(cx: Float, cy: Float, arm: Float, stroke: Float, color: Color) {
  drawLine(color, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke, StrokeCap.Round)
  drawLine(color, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke, StrokeCap.Round)
}

private data class FootprintPart(val bytes: Long, val alpha: Float, val unit: String)

private fun footprintParts(footprint: AppFootprint): List<FootprintPart> =
  listOf(
    FootprintPart(footprint.appBytes, 0.62f, "PKG"),
    FootprintPart(footprint.dataBytes, 0.34f, "USR"),
    FootprintPart(footprint.cacheBytes, 0.18f, "CCH"),
  ).filter { it.bytes > 0L }

private fun cardMetaLine(app: AppEntry, unreadCount: Int = 0): String {
  val minutes = app.usageTimeMillis14d / 60_000L
  val hours = minutes / 60L
  val usage = if (hours > 0) "${hours}H ${minutes % 60}M" else "${minutes}M"
  val tag = when {
    app.isEssentialQuickAccess -> "QUICK ACCESS"
    app.isPinned -> "PINNED"
    else -> "14D ROTATION"
  }
  return if (unreadCount > 0) "$tag  ·  $usage  ·  $unreadCount MSG" else "$tag  ·  $usage"
}

private fun footprintLine(footprint: AppFootprint): String =
  footprintParts(footprint).joinToString("  ·  ") { "${humanBytes(it.bytes)} ${it.unit}" }

private fun humanBytes(bytes: Long): String {
  val kb = bytes / 1024.0
  return when {
    kb < 1024 -> "${kb.roundToInt()}K"
    kb < 1_048_576 -> "${(kb / 1024.0).roundToInt()}M"
    else -> "${((kb / 1_048_576.0) * 10).roundToInt() / 10f}G"
  }
}
