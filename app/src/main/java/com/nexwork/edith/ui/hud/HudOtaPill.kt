package com.nexwork.edith.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.data.OtaState
import com.nexwork.edith.theme.CrimsonBright

/**
 * Minimal Stark monochrome OTA status pill rendered below [HudClock].
 * Stays hidden during normal idle operation so the 3D globe remains uncluttered.
 */
@Composable
fun HudOtaPill(
  otaState: OtaState,
  onClick: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val visible = otaState !is OtaState.Idle

  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    val label = when (otaState) {
      OtaState.Idle -> ""
      OtaState.Checking -> "OTA // CHECKING RELEASE"
      is OtaState.Available -> "OTA // v${otaState.release.versionName} (${otaState.release.versionCode}) — TAP TO UPDATE"
      is OtaState.Downloading -> "OTA // DOWNLOADING ${otaState.progress}%"
      is OtaState.ReadyToInstall -> "OTA // READY — TAP TO INSTALL"
      OtaState.UpToDate -> "OTA // UP TO DATE"
      is OtaState.Error -> "OTA // ${otaState.message.uppercase()}"
    }

    val borderColor = when (otaState) {
      is OtaState.Available, is OtaState.ReadyToInstall -> Color.White.copy(alpha = 0.55f)
      is OtaState.Error -> CrimsonBright.copy(alpha = 0.7f)
      else -> Color.White.copy(alpha = 0.24f)
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xD6000000))
        .border(1.dp, borderColor)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = label,
          color = if (otaState is OtaState.Error) CrimsonBright else Color.White,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium,
          letterSpacing = 1.2.sp,
          modifier = Modifier.weight(1f),
        )
        if (otaState is OtaState.Error || otaState is OtaState.UpToDate || otaState is OtaState.Available) {
          Text(
            text = "✕",
            color = Color.White.copy(alpha = 0.55f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
              .clickable(onClick = onDismiss)
              .padding(start = 12.dp),
          )
        }
      }

      if (otaState is OtaState.Downloading) {
        Box(
          modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(2.dp)
            .background(Color.White.copy(alpha = 0.14f)),
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth((otaState.progress / 100f).coerceIn(0.02f, 1f))
              .height(2.dp)
              .background(Color.White),
          )
        }
      }
    }
  }
}
