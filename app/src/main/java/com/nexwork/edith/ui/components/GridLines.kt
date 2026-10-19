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
 * Very fine, high-density layout grid lines with custom feature lines.
 */
@Composable
fun GridLines(
  modifier: Modifier = Modifier,
  gridSize: Dp = 8.dp,
  majorStep: Int = 4, // Major line every 32dp
  minorLineColor: Color = Color.White.copy(alpha = 0.08f),
  majorLineColor: Color = Color.White.copy(alpha = 0.22f),
  centerLineColor: Color = Color(0xFF38BDF8).copy(alpha = 0.45f),
  verticalLineFromRightIndex: Int? = 4, // 4th line from the right of the phone
  customLineWidth: Dp = 2.dp,
  customLineColor: Color = Color.White,
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
        strokeWidth = if (isMajor) 0.8.dp.toPx() else 0.5.dp.toPx(),
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
        strokeWidth = if (isMajor) 0.8.dp.toPx() else 0.5.dp.toPx(),
      )
    }

    // Screen Center Axis guidelines (dashed accent color)
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)

    // Center Vertical Line
    drawLine(
      color = centerLineColor,
      start = Offset(centerX, 0f),
      end = Offset(centerX, height),
      strokeWidth = 1.dp.toPx(),
      pathEffect = dashEffect,
    )

    // Center Horizontal Line
    drawLine(
      color = centerLineColor,
      start = Offset(0f, centerY),
      end = Offset(width, centerY),
      strokeWidth = 1.dp.toPx(),
      pathEffect = dashEffect,
    )

    // Line on the 4th line from the right of the phone
    if (verticalLineFromRightIndex != null && verticalLineFromRightIndex > 0) {
      val lastLineIndex = (width / stepPx).toInt()
      val targetIndex = lastLineIndex - (verticalLineFromRightIndex - 1)
      if (targetIndex >= 0) {
        val targetX = targetIndex * stepPx
        drawLine(
          color = customLineColor,
          start = Offset(targetX, 0f),
          end = Offset(targetX, height),
          strokeWidth = customLineWidth.toPx(),
        )
      }
    }
  }
}
