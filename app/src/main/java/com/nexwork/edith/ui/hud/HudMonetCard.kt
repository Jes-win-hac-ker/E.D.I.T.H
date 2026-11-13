package com.nexwork.edith.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.data.MonetStatus
import com.nexwork.edith.data.MonetUiState
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.ObsidianBgTop

@Composable
fun HudMonetCard(
  monetUiState: MonetUiState,
  onApply: () -> Unit,
  onReset: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current
  val isBusy = monetUiState.status == MonetStatus.APPLYING || monetUiState.status == MonetStatus.RESETTING

  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp)
      .background(ObsidianBgTop.copy(alpha = 0.94f))
      .clickable(enabled = true, onClick = {})
      .drawBehind {
        val w = size.width
        val h = size.height
        val cut = 12.dp.toPx()
        val borderPath = Path().apply {
          moveTo(cut, 0f)
          lineTo(w, 0f)
          lineTo(w, h - cut)
          lineTo(w - cut, h)
          lineTo(0f, h)
          lineTo(0f, cut)
          close()
        }
        drawPath(
          path = borderPath,
          color = Crimson.copy(alpha = 0.78f),
          style = Stroke(width = 1.2.dp.toPx()),
        )
        val bracket = 10.dp.toPx()
        drawLine(CrimsonBright, Offset(0f, cut), Offset(cut, 0f), strokeWidth = 2.dp.toPx())
        drawLine(CrimsonBright, Offset(cut, 0f), Offset(cut + bracket, 0f), strokeWidth = 2.dp.toPx())
        drawLine(CrimsonBright, Offset(w - cut - bracket, h), Offset(w - cut, h), strokeWidth = 2.dp.toPx())
        drawLine(CrimsonBright, Offset(w - cut, h), Offset(w, h - cut), strokeWidth = 2.dp.toPx())
      }
      .padding(16.dp)
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "MONET // FABRICATED OVERLAY",
          color = Color.White,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.5.sp,
        )
        Text(
          text = if (monetUiState.isEnabledPersisted) "GUARD: ON" else "GUARD: STANDBY",
          color = if (monetUiState.isEnabledPersisted) CrimsonBright else Color.White.copy(alpha = 0.55f),
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          letterSpacing = 1.2.sp,
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      Text(
        text = monetUiState.statusMessage,
        color = when (monetUiState.status) {
          MonetStatus.ACTIVE -> CrimsonBright
          MonetStatus.ERROR, MonetStatus.SHIZUKU_UNAVAILABLE -> Color(0xFFFF6B6B)
          else -> Color.White.copy(alpha = 0.78f)
        },
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.2.sp,
      )

      Spacer(modifier = Modifier.height(12.dp))

      // Monochrome 0% Chroma + Crimson #DC143C Tonal Ramp Swatch Strip
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val swatches = listOf(
          0xFF000000.toInt(),
          0xFF1B1B1B.toInt(),
          0xFF474747.toInt(),
          0xFF919191.toInt(),
          0xFFFFFFFF.toInt(),
          0xFF40000A.toInt(),
          0xFF920022.toInt(),
          0xFFDC143C.toInt(),
          0xFFFF5261.toInt(),
          0xFFFFB3B3.toInt(),
        )
        for (hex in swatches) {
          Canvas(
            modifier = Modifier
              .weight(1f)
              .height(14.dp)
              .border(0.8.dp, Color.White.copy(alpha = 0.24f))
          ) {
            drawRect(color = Color(hex))
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier = Modifier
            .weight(1f)
            .background( Crimson.copy(alpha = if (isBusy) 0.12f else 0.28f))
            .border(1.dp, CrimsonBright.copy(alpha = if (isBusy) 0.4f else 0.9f))
            .clickable(enabled = !isBusy) {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onApply()
            }
            .padding(vertical = 10.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = if (monetUiState.status == MonetStatus.APPLYING) "APPLYING..." else "APPLY CRIMSON MONET",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
          )
        }

        Box(
          modifier = Modifier
            .weight(1f)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = if (isBusy) 0.2f else 0.45f))
            .clickable(enabled = !isBusy) {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onReset()
            }
            .padding(vertical = 10.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = if (monetUiState.status == MonetStatus.RESETTING) "RESETTING..." else "RESET TO DEFAULT",
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
          )
        }
      }
    }
  }
}
