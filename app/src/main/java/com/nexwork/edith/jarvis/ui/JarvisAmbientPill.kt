package com.nexwork.edith.jarvis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexwork.edith.jarvis.core.ClashSeverity
import com.nexwork.edith.jarvis.core.ProactiveSuggestion
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.ui.hud.HudTelemetryRail

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
    is ProactiveSuggestion.EventClashAlert -> "JARVIS.PLANNER // SCHEDULE CLASH"
    null -> ""
  }
  val label = when (active) {
    is ProactiveSuggestion.OpenMaps -> "NAVIGATE -> ${active.placeName.uppercase()}"
    is ProactiveSuggestion.ScanUpiQr -> "SCAN QR · ${active.placeLabel.uppercase()}"
    is ProactiveSuggestion.EventClashAlert ->
      "${active.clash.existingEvent.title.uppercase()} × ${active.clash.incomingEvent.title.uppercase()}"
    null -> ""
  }
  val badge = when (active) {
    is ProactiveSuggestion.OpenMaps -> "MAPS"
    is ProactiveSuggestion.ScanUpiQr -> "SCAN"
    is ProactiveSuggestion.EventClashAlert ->
      if (active.clash.severity == ClashSeverity.HARD_OVERLAP) {
        "${active.clash.overlapMinutes}M"
      } else {
        "${active.clash.gapMinutes}M GAP"
      }
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
 * Subtle pulsing crimson dot anchored on the home screen whenever Jarvis triage or biometric
 * approval is active.
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
    val pulseTransition = rememberInfiniteTransition(label = "jarvisStopDotPulse")
    val dotAlpha by pulseTransition.animateFloat(
      initialValue = 0.42f,
      targetValue = 1.0f,
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 750),
        repeatMode = RepeatMode.Reverse,
      ),
      label = "jarvisStopDotAlpha",
    )
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(32.dp)
        .semantics { contentDescription = "Stop $label" }
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          role = Role.Button,
          onClick = onStop,
        ),
    ) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .alpha(dotAlpha)
          .clip(CircleShape)
          .background(CrimsonBright),
      )
    }
  }
}
