package com.nexwork.edith.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.HudFill
import com.nexwork.edith.theme.HudLine
import com.nexwork.edith.theme.HudLineDim

/** Octagonal cut: top-left and bottom-right corners chamfered — classic HUD panel silhouette. */
class AngledCutShape(private val cut: Dp = 14.dp) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val c = cut.value * density.density
    val path =
      Path().apply {
        moveTo(c, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, (size.height - c).coerceAtLeast(0f))
        lineTo((size.width - c).coerceAtLeast(0f), size.height)
        lineTo(0f, size.height)
        lineTo(0f, c)
        close()
      }
    return Outline.Generic(path)
  }
}

/**
 * Stark HUD panel primitive:
 * - Angled-cut wireframe silhouette with dim monochrome fill.
 * - Double-stroke glow border (wide crimson halo + crisp silver line).
 * - Crimson corner brackets drawn outside the frame.
 * - Optional animated scanline sweep (Full Hollywood mode).
 */
@Composable
fun HudPanel(
  modifier: Modifier = Modifier,
  title: String? = null,
  accent: Color = Crimson,
  cut: Dp = 14.dp,
  brackets: Boolean = true,
  content: @Composable ColumnScope.() -> Unit,
) {
  val shape = remember(cut) { AngledCutShape(cut) }

  Column(
    modifier =
      modifier
        .drawBehind {
          val c = cut.toPx()
          val bracketLen = 10.dp.toPx()
          val bracketW = 2.dp.toPx()

          val panelPath =
            Path().apply {
              moveTo(c, 0f)
              lineTo(size.width, 0f)
              lineTo(size.width, (size.height - c).coerceAtLeast(0f))
              lineTo((size.width - c).coerceAtLeast(0f), size.height)
              lineTo(0f, size.height)
              lineTo(0f, c)
              close()
            }

          // Crimson halo under the border
          drawPath(
            path = panelPath,
            color = accent.copy(alpha = 0.30f),
            style = Stroke(width = 4.dp.toPx()),
          )
          // Crisp monochrome wireframe line
          drawPath(
            path = panelPath,
            color = HudLine.copy(alpha = 0.85f),
            style = Stroke(width = 1.2.dp.toPx()),
          )

          if (brackets) {
            val w = size.width
            val h = size.height
            // Top-right bracket
            drawLine(accent, Offset(w - bracketLen, 0f), Offset(w, 0f), bracketW)
            drawLine(accent, Offset(w, 0f), Offset(w, bracketLen), bracketW)
            // Bottom-left bracket
            drawLine(accent, Offset(0f, h), Offset(bracketLen, h), bracketW)
            drawLine(accent, Offset(0f, h), Offset(0f, h - bracketLen), bracketW)
            // Cut-corner ticks (top-left / bottom-right chamfers)
            drawLine(accent.copy(alpha = 0.7f), Offset(0f, c + bracketLen), Offset(0f, c), bracketW)
            drawLine(accent.copy(alpha = 0.7f), Offset(w - c, h), Offset(w - c - bracketLen, h), bracketW)
          }
        }
        .clip(shape)
        .background(HudFill),
  ) {
    if (title != null) {
      Text(
        text = "[ $title ]",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = HudLineDim,
        fontSize = 10.sp,
        letterSpacing = 1.5.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
      )
      Spacer(modifier = Modifier.height(6.dp))
    }
    content()
  }
}
