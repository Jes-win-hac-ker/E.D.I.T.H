package com.nexwork.edith.jarvis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.jarvis.core.ProactiveSuggestion
import com.nexwork.edith.theme.GlassSurfaceElevated
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver

/**
 * Dismissible dark-glass HUD Ambient Pill surfaced on the Home screen when [ProactiveSuggestion]
 * triggers from passive context signals (Traffic/Lost Stall Maps or Post-Dining UPI QR).
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
  var lastSuggestion by remember { mutableStateOf<ProactiveSuggestion?>(null) }
  if (suggestion != null) {
    lastSuggestion = suggestion
  }
  val visible = !hidden && suggestion != null
  LaunchedEffect(visible) {
    onMountedChange(visible)
  }

  val style = LocalLauncherStyle.current
  val accent = style.accent
  val haptic = LocalHapticFeedback.current

  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(240, easing = FastOutSlowInEasing)) +
      slideInVertically(tween(260, easing = FastOutSlowInEasing)) { -it / 3 },
    exit = fadeOut(tween(180, easing = FastOutSlowInEasing)) +
      slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -it / 3 },
    modifier = modifier,
  ) {
    val active = suggestion ?: lastSuggestion ?: return@AnimatedVisibility
    val (eyebrow, title, badgeLabel) = when (active) {
      is ProactiveSuggestion.OpenMaps -> Triple(
        "JARVIS.AMBIENT // MAPS.STALL",
        "NAVIGATE -> ${active.placeName}",
        "MAPS",
      )
      is ProactiveSuggestion.ScanUpiQr -> Triple(
        "JARVIS.AMBIENT // DWELL ${active.dwellMinutes}M · UPI",
        "SCAN QR · ${active.placeLabel}",
        "SCAN",
      )
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(GlassSurfaceElevated.copy(alpha = 0.88f))
        .border(0.9.dp, accent.copy(alpha = 0.48f), RoundedCornerShape(14.dp))
        .clickable {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          onAction(active)
        }
        .padding(start = 13.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = eyebrow,
          color = accent,
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.9.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = title,
          color = TextPureWhite,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = active.sourceSummary,
          color = TextSilver.copy(alpha = 0.82f),
          fontSize = 10.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Spacer(Modifier.width(8.dp))

      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(0.8.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
          Text(
            text = badgeLabel,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
          )
        }

        Box(
          modifier = Modifier
            .size(34.dp)
            .clickable {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onDismiss()
            },
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "×",
            color = TextPureWhite.copy(alpha = 0.72f),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
          )
        }
      }
    }
  }
}
