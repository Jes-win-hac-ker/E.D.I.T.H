package com.nexwork.edith.ui.hud

import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
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
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.CrimsonDim
import kotlin.math.roundToInt

/**
 * Category card on the same 312 x 124 sheet as `HudAppCard`, so a group deck and an app deck
 * stack on identical chrome. Where an app card carries one icon, this carries the group's four
 * leading apps as a 2 x 2 cluster; where an app card reads `% OF PEAK`, this reads the number of
 * apps filed here. The bottom strip ticks once per member, and the cell meter shows the group's
 * share of the busiest group — a group card is the aggregate of its apps, not a placeholder.
 */
@Composable
fun HudGroupCard(
  group: AppGroup,
  modifier: Modifier = Modifier,
  usageFraction: Float = 0.24f,
  reveal: () -> Float = { 1f },
  focused: () -> Boolean = { true },
) {
  val density = LocalDensity.current
  val monochrome = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
  val label = remember(group.name) { group.name.uppercase() }
  val count = group.apps.size
  val countText = count.toString()
  val captionText = if (count == 1) "APP" else "APPS"
  val meta = remember(group) { "$count $captionText  ·  ${duration(group.usageMillis)}" }
  val ghost = remember(group.name) { group.name.filter { it.isLetter() }.take(2).uppercase() }
  val share = usageFraction.coerceIn(0.04f, 1f)
  val shareValue = (share * 100f).roundToInt()
  val hasEssential = remember(group) { group.apps.any { it.isEssentialQuickAccess } }
  val members = remember(group) { group.apps.take(4) }
  val iconRise = remember(density) { with(density) { 2.dp.toPx() } }
  val leaderBrush = remember { Brush.verticalGradient(listOf(Color(0xD9050505), Color(0xD9111111))) }
  val stackBrush = remember { Brush.verticalGradient(listOf(Color(0xB8050505), Color(0xB8111111))) }

  val labelPaint = remember(density) { monoPaint(density, 17f, 1.6f, Paint.Align.LEFT, 255) }
  val metaPaint = remember(density) { monoPaint(density, 9.5f, 1f, Paint.Align.LEFT, 140) }
  val countPaint = remember(density) { monoPaint(density, 16f, 0.5f, Paint.Align.LEFT, 230) }
  val captionPaint = remember(density) { monoPaint(density, 7.5f, 1.1f, Paint.Align.LEFT, 107) }
  val ghostPaint = remember(density) { monoPaint(density, 20f, 0f, Paint.Align.CENTER, 46) }
  val finePaint = remember(density) { monoPaint(density, 7.5f, 0f, Paint.Align.RIGHT, 88) }

  Box(
    modifier = modifier
      .aspectRatio(312f / 124f)
      .graphicsLayer { alpha = phase(reveal(), 0f, 0.22f) }
      .drawBehind { drawRect(brush = if (focused()) leaderBrush else stackBrush) },
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
          val tickStart = X(150f)
          val tickRight = X(252f)
          val tickTop = Y(104f)
          val tickH = 2f.dp.toPx()
          val stroke = 1f.dp.toPx()
          val keyline = size.width - stroke
          val keylineH = size.height - stroke

          val labelLeft = X(81f)
          val labelRight = X(229f)
          val advance = labelPaint.measureText("MM") - labelPaint.measureText("M")
          val maxChars = ((labelRight - labelLeft) / advance).toInt().coerceAtLeast(1)
          val shownLabel = if (label.length <= maxChars) label else label.take(maxChars - 1) + "…"
          val labelBaseline = centerBaseline(labelPaint, Y(50f))
          val metaBaseline = centerBaseline(metaPaint, Y(72f))
          val countBaseline = centerBaseline(countPaint, Y(50f))
          val captionBaseline = centerBaseline(captionPaint, Y(72f))
          val ghostBaseline = centerBaseline(ghostPaint, Y(32f))
          val fineBaseline = centerBaseline(finePaint, Y(117f))
          val countAdvance = countPaint.measureText("MM") - countPaint.measureText("M")
          val countRight = labelRight + X(19f)
          val captionWidth = captionPaint.measureText(captionText)
          val seed = group.name.hashCode()
          val rollRise = 3f.dp.toPx()
          val tickCount = count.coerceAtMost(48)

          onDrawBehind {
            val progress = reveal()
            if (progress <= 0.001f) return@onDrawBehind
            val leader = focused()

            val trace = phase(progress, 0f, 0.55f)
            val labelP = phase(progress, 0.35f, 0.72f)
            val metaP = phase(progress, 0.5f, 0.78f)
            val crossP = phase(progress, 0.42f, 0.6f)
            val ghostP = phase(progress, 0.55f, 0.72f)
            val meterP = phase(progress, 0.6f, 0.86f)
            val rollP = phase(progress, 0.62f, 0.95f)
            val tickP = phase(progress, 0.7f, 1f)
            val fineP = phase(progress, 0.82f, 1f)

            fun drawPlateAndGrid() {
              drawPath(platePath, if (leader) Color(0xD90F141E) else Color(0xB80F141E))
              drawPath(
                platePath,
                if (leader) CrimsonBright.copy(alpha = 0.5f) else CrimsonDim,
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

            if (crossP > 0f) {
              crosses.forEachIndexed { idx, (centre, alpha) ->
                val color = if (idx == 0 && leader) CrimsonBright.copy(alpha * crossP) else Color.White.copy(alpha * crossP)
                drawCross(centre.x, centre.y, crossArm, crossStroke, color)
              }
            }

            if (ghostP > 0f) {
              ghostPaint.alpha = (46 * ghostP).toInt()
              drawContext.canvas.nativeCanvas.drawText(ghost, X(285f), ghostBaseline, ghostPaint)
            }

            val litCells = (meterP * 10f * share).toInt().coerceIn(1, 10)
            repeat(10) { cell ->
              val isLit = cell < litCells
              val isHighUsage = isLit && (cell >= 7) && shareValue > 75
              val cellColor = when {
                isHighUsage -> CrimsonBright.copy(alpha = meterP)
                isLit -> Color.White.copy(alpha = 0.88f * meterP)
                else -> Color.White.copy(alpha = 0.15f * meterP)
              }
              drawRoundRect(
                color = cellColor,
                topLeft = Offset(meterX + cell * (cellW + cellGap), meterY),
                size = Size(cellW, cellH),
                cornerRadius = cellRadius,
              )
            }

            if (tickCount > 0 && tickP > 0f) {
              val pitch = (tickRight - tickStart) / tickCount
              val width = (pitch * 0.55f).coerceAtLeast(1f)
              repeat(tickCount) { i ->
                drawRect(
                  color = Color.White.copy(alpha = 0.34f * tickP),
                  topLeft = Offset(tickStart + i * pitch, tickTop),
                  size = Size(width, tickH),
                )
              }
            }

            if (fineP > 0f) {
              finePaint.alpha = (88 * fineP).toInt()
              if (fineP < 0.999f) {
                clipRect(right = tickStart + (tickRight - tickStart) * fineP) {
                  drawContext.canvas.nativeCanvas.drawText("TAP  ·  OPEN", tickRight, fineBaseline, finePaint)
                }
              } else {
                drawContext.canvas.nativeCanvas.drawText("TAP  ·  OPEN", tickRight, fineBaseline, finePaint)
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
              val shownCount = if (rollP >= 1f) countText else (count * rollP).roundToInt().toString()
              countPaint.color = if (leader) {
                android.graphics.Color.argb((235 * rollP).toInt(), 255, 255, 255)
              } else {
                android.graphics.Color.argb((200 * rollP).toInt(), 255, 255, 255)
              }
              drawContext.canvas.nativeCanvas.drawText(
                shownCount,
                countRight - shownCount.length * countAdvance,
                countBaseline + (1f - rollP) * rollRise,
                countPaint,
              )
              captionPaint.alpha = (107 * rollP).toInt()
              drawContext.canvas.nativeCanvas.drawText(captionText, countRight - captionWidth, captionBaseline, captionPaint)
            }

            val bracketTint = Color.White.copy(if (leader) 1f else 0.5f)
            drawHudCornerBrackets(
              trace = trace,
              cornerAt = cornerAt,
              barPx = bar,
              tint = bracketTint,
              trTint = if (leader) CrimsonBright.copy(alpha = 0.85f) else bracketTint,
              blTint = if (leader) CrimsonDim else bracketTint,
            )

            drawRect(
              color = if (hasEssential) CrimsonBright.copy(alpha = 0.82f * meterP) else Color.White.copy(alpha = 0.22f * meterP),
              topLeft = Offset(X(283.5f), Y(110f)),
              size = Size(3f * ux, 3f * uy),
            )

            drawTracedRect(
              width = keyline,
              height = keylineH,
              progress = trace,
              strokeWidth = stroke,
              color = Color.White.copy(if (leader) 0.85f else 0.3f),
              head = if (trace in 0.02f..0.98f) 1f else 0f,
            )
          }
        },
    )

    val cellShape = remember { RoundedCornerShape(percent = 24) }
    Layout(
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          val settle = phase(reveal(), 0.28f, 0.6f)
          alpha = settle
          scaleX = 0.94f + 0.06f * settle
          scaleY = 0.94f + 0.06f * settle
          translationY = (1f - settle) * iconRise
        },
      content = {
        members.forEach { member ->
          MiniAppIcon(member, cellShape, monochrome)
        }
      },
    ) { measurables, constraints ->
      val originX = (constraints.maxWidth * (24f / 312f)).roundToInt()
      val originY = (constraints.maxHeight * (39.75f / 124f)).roundToInt()
      val cellPx = (constraints.maxWidth * (19.5f / 312f)).roundToInt().coerceAtLeast(1)
      val gapPx = (constraints.maxWidth * (3.5f / 312f)).roundToInt()
      val cellConstraints = Constraints.fixed(cellPx, cellPx)
      val placeables = measurables.map { it.measure(cellConstraints) }
      layout(constraints.maxWidth, constraints.maxHeight) {
        placeables.forEachIndexed { index, placeable ->
          val col = index % 2
          val row = index / 2
          val x = originX + if (col == 1) cellPx + gapPx else 0
          val y = originY + if (row == 1) cellPx + gapPx else 0
          placeable.placeRelative(x, y)
        }
      }
    }
  }
}

@Composable
private fun MiniAppIcon(entry: AppEntry, shape: RoundedCornerShape, filter: ColorFilter) {
  val bitmap = remember(entry.packageName, entry.icon) { entry.cachedIconBitmap() }
  Box(
    Modifier
      .fillMaxSize()
      .clip(shape)
      .background(Color.White.copy(alpha = 0.06f))
      .border(0.8.dp, Color.White.copy(alpha = 0.24f), shape),
    contentAlignment = Alignment.Center,
  ) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = null,
        colorFilter = filter,
        modifier = Modifier.fillMaxSize(0.6f),
      )
    } else {
      Text(
        text = entry.initial.toString(),
        color = Color.White.copy(alpha = 0.75f),
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

private fun duration(millis: Long): String {
  val minutes = millis / 60_000L
  val hours = minutes / 60L
  return if (hours > 0) "${hours}H ${minutes % 60}M" else "${minutes}M"
}
