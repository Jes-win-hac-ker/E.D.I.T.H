package com.nexwork.edith.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Clean architectural layout grid lines covering the entire screen.
 * Draws horizontal and vertical grid lines with major/minor intervals and center guidelines.
 */
@Composable
fun GridLines(
  modifier: Modifier = Modifier,
  gridSize: Dp = 24.dp,
  majorStep: Int = 4,
  minorLineColor: Color = Color.White.copy(alpha = 0.10f),
  majorLineColor: Color = Color.White.copy(alpha = 0.25f),
  centerLineColor: Color = Color(0xFF38BDF8).copy(alpha = 0.45f),
) {
  Canvas(modifier = modifier.fillMaxSize()) {
    val stepPx = gridSize.toPx()
    val width = size.width
    val height = size.height

    if (stepPx <= 0f) return@Canvas

    val cols = (width / stepPx).toInt() + 1
    val rows = (height / stepPx).toInt() + 1

    val centerX = width / 2f
    val centerY = height / 2f

    // Vertical grid lines
    for (i in 0..cols) {
      val x = i * stepPx
      if (x > width) break
      val isMajor = (i % majorStep == 0)
      drawLine(
        color = if (isMajor) majorLineColor else minorLineColor,
        start = Offset(x, 0f),
        end = Offset(x, height),
        strokeWidth = if (isMajor) 1.2.dp.toPx() else 0.8.dp.toPx(),
      )
    }

    // Horizontal grid lines
    for (j in 0..rows) {
      val y = j * stepPx
      if (y > height) break
      val isMajor = (j % majorStep == 0)
      drawLine(
        color = if (isMajor) majorLineColor else minorLineColor,
        start = Offset(0f, y),
        end = Offset(width, y),
        strokeWidth = if (isMajor) 1.2.dp.toPx() else 0.8.dp.toPx(),
      )
    }

    // Screen Center Axis guidelines (dashed accent color)
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)

    // Center Vertical Line
    drawLine(
      color = centerLineColor,
      start = Offset(centerX, 0f),
      end = Offset(centerX, height),
      strokeWidth = 1.2.dp.toPx(),
      pathEffect = dashEffect,
    )

    // Center Horizontal Line
    drawLine(
      color = centerLineColor,
      start = Offset(0f, centerY),
      end = Offset(width, centerY),
      strokeWidth = 1.2.dp.toPx(),
      pathEffect = dashEffect,
    )
  }
}
