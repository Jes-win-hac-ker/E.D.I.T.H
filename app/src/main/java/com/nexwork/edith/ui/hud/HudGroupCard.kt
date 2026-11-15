package com.nexwork.edith.ui.hud

import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.nexwork.edith.theme.LocalCrimsonMonetActive
import kotlin.math.roundToInt

/**
 * Option 4 Radial Target Node representing an app category bucket anchored beside the corner Earth.
 */
@Composable
fun HudGroupCard(
  group: AppGroup,
  modifier: Modifier = Modifier,
  usageFraction: Float = 0.24f,
  reveal: () -> Float = { 1f },
  focused: () -> Boolean = { true },
  slotIndex: Int = 0,
) {
  val density = LocalDensity.current
  val crimsonMonetActive = rememberUpdatedState(LocalCrimsonMonetActive.current)
  val monochrome = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
  val indexPrefix = remember(slotIndex) { "%02d".format(slotIndex + 1) }
  val label = remember(group.name, indexPrefix) { "$indexPrefix ${group.name.uppercase()}" }
  val count = group.apps.size
  val meta = remember(group) {
    val caption = if (count == 1) "APP" else "APPS"
    "$count $caption  ·  ${duration(group.usageMillis)}"
  }
  val members = remember(group) { group.apps.take(4) }
  val share = usageFraction.coerceIn(0.05f, 1f)
  val iconRise = remember(density) { with(density) { 2.dp.toPx() } }

  val titlePaint = remember(density) { monoPaint(density, 13.5f, 1.0f, Paint.Align.LEFT, 250) }
  val metaPaint = remember(density) { monoPaint(density, 8.8f, 0.8f, Paint.Align.LEFT, 150) }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(RadialNodeHeight)
      .graphicsLayer { alpha = phase(reveal(), 0f, 0.2f) }
      .drawWithCache {
        val chamfer = 9.dp.toPx()
        val nodePath = radialNodePath(size.width, size.height, chamfer)
        val chargeFill = Brush.horizontalGradient(
          0f to CrimsonBright.copy(alpha = 0.06f),
          1f to CrimsonBright.copy(alpha = 0.26f),
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
        val seed = group.name.hashCode()

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
            usageFraction = share,
            charge = 0f,
            chargeFill = chargeFill,
          )

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
            canvas.drawText(meta, textLeft, metaBaseline, metaPaint)
          }
        }
      },
  ) {
    val cellShape = remember { RoundedCornerShape(3.dp) }
    Layout(
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          val settle = phase(reveal(), 0.22f, 0.62f)
          alpha = settle
          scaleX = 0.90f + 0.10f * settle
          scaleY = 0.90f + 0.10f * settle
          translationY = (1f - settle) * iconRise
        },
      content = {
        members.forEach { member ->
          MiniAppIcon(member, cellShape, monochrome)
        }
      },
    ) { measurables, constraints ->
      val cellPx = 11.dp.roundToPx().coerceAtLeast(1)
      val gapPx = 2.dp.roundToPx()
      val clusterPx = cellPx * 2 + gapPx
      val originX = (28.dp.toPx() - clusterPx / 2f).roundToInt()
      val originY = ((constraints.maxHeight - clusterPx) / 2f).roundToInt()
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
      .background(Color.White.copy(alpha = 0.05f))
      .border(0.6.dp, Color.White.copy(alpha = 0.22f), shape),
    contentAlignment = Alignment.Center,
  ) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = null,
        colorFilter = filter,
        modifier = Modifier.fillMaxSize(0.72f),
      )
    } else {
      Text(
        text = entry.initial.toString(),
        color = Color.White.copy(alpha = 0.78f),
        fontSize = 6.sp,
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
