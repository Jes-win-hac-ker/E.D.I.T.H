package com.nexwork.edith.jarvis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.jarvis.core.ProactiveSuggestion
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.ui.hud.HudTelemetryRail
import com.nexwork.edith.ui.hud.starkHudPlate

/**
 * Native Stark-HUD orbital horizon rail surfacing proactive [ProactiveSuggestion] actions on the
 * E.D.I.T.H launcher home screen.
 */
@Composable
fun JarvisAmbientPill(
  suggestion: ProactiveSuggestion?,
  onAction: (ProactiveSuggestion) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  hidden: Boolean = false,
  onMountedChange: (Boolean) -> Unit = {},
) {
  var lastSuggestion by remember { mutableStateOf(suggestion) }
  LaunchedEffect(suggestion) {
    if (suggestion != null) {
      lastSuggestion = suggestion
    }
  }
  val active = suggestion ?: lastSuggestion

  val eyebrow = when (active) {
    is ProactiveSuggestion.OpenMaps -> "JARVIS.AMBIENT // MAPS.STALL"
    is ProactiveSuggestion.ScanUpiQr -> "JARVIS.AMBIENT // DWELL ${active.dwellMinutes}M · UPI"
    null -> ""
  }
  val label = when (active) {
    is ProactiveSuggestion.OpenMaps -> "NAVIGATE -> ${active.placeName.uppercase()}"
    is ProactiveSuggestion.ScanUpiQr -> "SCAN QR · ${active.placeLabel.uppercase()}"
    null -> ""
  }
  val badge = when (active) {
    is ProactiveSuggestion.OpenMaps -> "MAPS"
    is ProactiveSuggestion.ScanUpiQr -> "SCAN"
    null -> null
  }

  HudTelemetryRail(
    visible = !hidden && suggestion != null && active != null,
    eyebrow = eyebrow,
    label = label,
    badge = badge,
    dismissible = true,
    onClick = { suggestion?.let(onAction) },
    onDismiss = onDismiss,
    clickEnabled = suggestion != null,
    onMountedChange = onMountedChange,
    modifier = modifier,
  )
}

/**
 * Compact sharp-cornered Stark-HUD emergency abort plate anchored on the home screen whenever
 * Jarvis playbooks, LLM planning, or message triage are running.
 */
@Composable
fun JarvisEmergencyStopHudPill(
  visible: Boolean,
  label: String,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    val style = LocalLauncherStyle.current
    Row(
      modifier = Modifier
        .starkHudPlate(
          accent = Crimson,
          focused = true,
          showBrackets = true,
          bracketScale = 0.48f,
          plateAlpha = 0.92f,
          style = style,
        )
        .clickable(onClick = onStop)
        .padding(horizontal = 10.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Box(
        modifier = Modifier
          .size(5.dp)
          .background(Crimson),
      )
      Text(
        text = "STOP",
        color = CrimsonBright,
        fontFamily = style.fontFamily,
        fontSize = (10f * style.fontScale).sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
      )
      Text(
        text = label,
        color = TextPureWhite,
        fontFamily = style.telemetryFontFamily,
        fontSize = (9.5f * style.fontScale).sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
