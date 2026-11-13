package com.nexwork.edith.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.data.OtaState
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.CrimsonDim
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver

/**
 * Stark HUD OTA telemetry strip rendered below [HudClock] using the launcher's monochrome void,
 * `Crimson` corner brackets, and `CrimsonBright` active readouts.
 */
@Composable
fun HudOtaPill(
  otaState: OtaState,
  onClick: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  hidden: Boolean = false,
) {
  val visible = !hidden && otaState !is OtaState.Idle

  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(180)),
    exit = fadeOut(tween(220)),
    modifier = modifier,
  ) {
    val label = when (otaState) {
      OtaState.Idle -> ""
      OtaState.Checking -> "OTA // SCANNING RELEASE"
      is OtaState.Available -> "OTA // v${otaState.release.versionName} (${otaState.release.versionCode}) — TAP TO UPDATE"
      is OtaState.Downloading -> "OTA // SYNCING v${otaState.release.versionName}"
      is OtaState.ReadyToInstall -> "OTA // v${otaState.release.versionName} READY — TAP TO INSTALL"
      is OtaState.Installing -> "OTA // APPLYING v${otaState.release.versionName}..."
      is OtaState.Installed -> "OTA // v${otaState.release.versionName} INSTALLED"
      OtaState.UpToDate -> "OTA // SYSTEM CURRENT"
      is OtaState.Error -> "OTA // ${otaState.message.uppercase()}"
    }

    val badge = when (otaState) {
      is OtaState.Downloading -> "${otaState.progress}%"
      is OtaState.Installed -> "OK"
      OtaState.UpToDate -> "SYNC"
      else -> null
    }

    val rawProgress = if (otaState is OtaState.Downloading) otaState.progress / 100f else 0f
    val animatedProgress by animateFloatAsState(
      targetValue = rawProgress.coerceIn(0f, 1f),
      animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
      label = "otaProgress",
    )

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xE0000000))
        .drawBehind {
          val w = size.width
          val h = size.height
          val stroke = 0.8.dp.toPx()
          val bracket = 6.dp.toPx()
          val bracketStroke = 1.4.dp.toPx()
          val hairline = Color.White.copy(alpha = 0.16f)

          // Outer hairline keyline
          drawRect(
            color = hairline,
            topLeft = Offset(0f, 0f),
            size = Size(w, h),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
          )

          // Stark Crimson corner brackets
          val bracketColor = if (otaState is OtaState.Error) CrimsonBright else Crimson
          // Top-left
          drawLine(bracketColor, Offset(0f, 0f), Offset(bracket, 0f), bracketStroke)
          drawLine(bracketColor, Offset(0f, 0f), Offset(0f, bracket), bracketStroke)
          // Top-right
          drawLine(bracketColor, Offset(w - bracket, 0f), Offset(w, 0f), bracketStroke)
          drawLine(bracketColor, Offset(w, 0f), Offset(w, bracket), bracketStroke)
          // Bottom-left
          drawLine(bracketColor, Offset(0f, h), Offset(bracket, h), bracketStroke)
          drawLine(bracketColor, Offset(0f, h - bracket), Offset(0f, h), bracketStroke)
          // Bottom-right
          drawLine(bracketColor, Offset(w - bracket, h), Offset(w, h), bracketStroke)
          drawLine(bracketColor, Offset(w, h - bracket), Offset(w, h), bracketStroke)
        }
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = label,
          color = if (otaState is OtaState.Error) CrimsonBright else TextPureWhite,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium,
          letterSpacing = 1.2.sp,
          modifier = Modifier.weight(1f),
        )
        if (badge != null) {
          Text(
            text = badge,
            color = CrimsonBright,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp),
          )
        }
        if (otaState !is OtaState.Installing && otaState !is OtaState.Installed && otaState !is OtaState.Checking) {
          Text(
            text = "✕",
            color = TextSilver.copy(alpha = 0.65f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
              .clickable(onClick = onDismiss)
              .padding(start = 10.dp),
          )
        }
      }

      if (otaState is OtaState.Downloading) {
        Box(
          modifier = Modifier
            .padding(top = 7.dp)
            .fillMaxWidth()
            .height(2.dp)
            .drawBehind {
              val fillW = (size.width * animatedProgress).coerceAtLeast(2.dp.toPx())
              drawRect(color = CrimsonDim, size = size)
              drawRect(color = Crimson, size = Size(fillW, size.height))
              // Bright tracing head matching HudSearchBar/HudClock
              val headW = 3.dp.toPx().coerceAtMost(fillW)
              drawRect(
                color = TextPureWhite,
                topLeft = Offset((fillW - headW).coerceAtLeast(0f), 0f),
                size = Size(headW, size.height),
              )
            },
        )
      }
    }
  }
}
