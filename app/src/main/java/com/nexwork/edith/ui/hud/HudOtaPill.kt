package com.nexwork.edith.ui.hud

import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.data.OtaState
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.TextPureWhite
import kotlin.math.roundToInt

private val OtaOrbitalSlate = Color(0xFF64748B)

private data class OtaHudCopy(
  val eyebrow: String,
  val label: String,
  val badge: String?,
  val dismissible: Boolean,
  val isError: Boolean = false,
  val isScanning: Boolean = false,
)

private fun OtaState.toHudCopy(): OtaHudCopy = when (this) {
  OtaState.Idle -> OtaHudCopy("", "", null, dismissible = false)
  OtaState.Checking -> OtaHudCopy(
    eyebrow = "OTA.LINK // TELEMETRY",
    label = "SCANNING RELEASE",
    badge = "SCAN",
    dismissible = false,
    isScanning = true,
  )
  is OtaState.Available -> OtaHudCopy(
    eyebrow = "OTA.LINK // v${release.versionName} (${release.versionCode})",
    label = "UPDATE AVAILABLE — TAP",
    badge = "TAP",
    dismissible = true,
  )
  is OtaState.Downloading -> OtaHudCopy(
    eyebrow = "OTA.LINK // v${release.versionName}",
    label = "SYNCING PACKAGE",
    badge = "${progress}%",
    dismissible = true,
  )
  is OtaState.ReadyToInstall -> OtaHudCopy(
    eyebrow = "OTA.LINK // v${release.versionName}",
    label = "READY TO INSTALL — TAP",
    badge = "TAP",
    dismissible = true,
  )
  is OtaState.Installing -> OtaHudCopy(
    eyebrow = "OTA.LINK // v${release.versionName}",
    label = "APPLYING UPDATE...",
    badge = "APPLY",
    dismissible = false,
    isScanning = true,
  )
  is OtaState.Installed -> OtaHudCopy(
    eyebrow = "OTA.LINK // v${release.versionName}",
    label = "UPDATE INSTALLED",
    badge = "OK",
    dismissible = false,
  )
  OtaState.UpToDate -> OtaHudCopy(
    eyebrow = "OTA.LINK // SYSTEM",
    label = "SYSTEM CURRENT",
    badge = "SYNC",
    dismissible = true,
  )
  is OtaState.Error -> OtaHudCopy(
    eyebrow = "OTA.LINK // FAULT",
    label = message.uppercase(),
    badge = "ERR",
    dismissible = true,
    isError = true,
  )
}

/**
 * Frameless orbital OTA telemetry rail anchored directly above [HudClock].
 * Matches the current horizon clock language: `OrbitalSlate` / `Crimson` laser-traced
 * baseline, center zenith pip, `drawDecrypt` monospace decode, and left-to-right
 * crimson laser progress without legacy box borders or corner brackets.
 */
@Composable
fun HudOtaPill(
  otaState: OtaState,
  onClick: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  hidden: Boolean = false,
  onMountedChange: (Boolean) -> Unit = {},
) {
  val shouldShow = !hidden && otaState !is OtaState.Idle
  var lastNonIdleState by remember { mutableStateOf<OtaState>(OtaState.Idle) }
  if (otaState !is OtaState.Idle) {
    lastNonIdleState = otaState
  }

  val revealAnim = remember { Animatable(0f) }
  var mounted by remember { mutableStateOf(shouldShow) }
  var isDismissing by remember { mutableStateOf(false) }
  val currentOnMountedChange = rememberUpdatedState(onMountedChange)

  LaunchedEffect(mounted) {
    currentOnMountedChange.value(mounted)
  }

  LaunchedEffect(shouldShow) {
    if (shouldShow) {
      isDismissing = false
      mounted = true
      val showMs = ((1f - revealAnim.value).coerceIn(0f, 1f) * 420f).roundToInt().coerceAtLeast(1)
      revealAnim.animateTo(1f, tween(showMs, easing = LinearEasing))
    } else if (mounted) {
      isDismissing = true
      val hideMs = (revealAnim.value.coerceIn(0f, 1f) * 300f).roundToInt().coerceAtLeast(1)
      revealAnim.animateTo(0f, tween(hideMs, easing = LinearEasing))
      mounted = false
      isDismissing = false
    }
  }

  if (!mounted) return

  val renderState = if (otaState !is OtaState.Idle) otaState else lastNonIdleState
  if (renderState is OtaState.Idle) return
  val copy = renderState.toHudCopy()

  val density = LocalDensity.current
  val haptic = LocalHapticFeedback.current
  val launcherStyle = LocalLauncherStyle.current
  val accentColor = rememberUpdatedState(launcherStyle.accent)

  val rawProgress = if (renderState is OtaState.Downloading) renderState.progress / 100f else 0f
  val animatedProgress by animateFloatAsState(
    targetValue = rawProgress.coerceIn(0f, 1f),
    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
    label = "otaProgress",
  )

  val sweepTransition = rememberInfiniteTransition(label = "otaSweep")
  val sweepPhase by sweepTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1150, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "otaSweepPhase",
  )

  val eyebrowPaint = remember(density) { monoPaint(density, 7.5f, 1.1f, Paint.Align.LEFT, 180) }
  val labelPaint = remember(density) {
    TextPaint(monoPaint(density, 10.5f, 0.9f, Paint.Align.LEFT, 255))
  }
  val rowInteraction = remember { MutableInteractionSource() }
  val dismissInteraction = remember { MutableInteractionSource() }

  val reveal = revealAnim.value
  val contentAlpha = if (isDismissing) {
    val d = (1f - reveal).coerceIn(0f, 1f)
    1f - hudEaseInCubic(phase(d, 0.00f, 0.48f))
  } else {
    hudEaseOutCubic(phase(reveal, 0.28f, 0.72f))
  }
  val rightSlidePx = with(density) {
    if (isDismissing) {
      (1f - contentAlpha) * 4.dp.toPx()
    } else {
      (1f - hudEaseOutCubic(phase(reveal, 0.34f, 0.96f))) * 6.dp.toPx()
    }
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(44.dp)
      .graphicsLayer { alpha = if (reveal <= 0.001f) 0f else 1f }
      .clickable(
        interactionSource = rowInteraction,
        indication = null,
        enabled = !isDismissing && (renderState is OtaState.Available || renderState is OtaState.ReadyToInstall),
      ) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
      }
      .drawBehind {
        if (reveal <= 0.001f) return@drawBehind
        val cx = size.width * 0.5f
        val baselineY = size.height - 4.dp.toPx()
        val halfSpan = cx
        val strokeBase = 1.dp.toPx()
        val strokeFill = 1.6.dp.toPx()

        val trace: Float
        val lineAlpha: Float
        val sparkAlpha: Float
        val decryptP: Float
        val textAlpha: Float
        val textSlideY: Float

        if (isDismissing) {
          val d = (1f - reveal).coerceIn(0f, 1f)
          textAlpha = 1f - hudEaseInCubic(phase(d, 0.00f, 0.48f))
          decryptP = 1f
          textSlideY = (1f - textAlpha) * 3.dp.toPx()
          trace = 1f - hudEaseInCubic(phase(d, 0.36f, 0.94f))
          lineAlpha = (trace * 4.5f).coerceIn(0f, 1f)
          sparkAlpha = if (d >= 0.86f) 1f - phase(d, 0.88f, 1.00f) else 0f
        } else {
          trace = hudEaseOutCubic(phase(reveal, 0.00f, 0.52f))
          lineAlpha = 1f
          sparkAlpha = if (reveal < 0.22f) 1f - phase(reveal, 0.08f, 0.22f) else 0f
          decryptP = phase(reveal, 0.28f, 0.92f)
          textAlpha = hudEaseOutCubic(phase(reveal, 0.28f, 0.72f))
          textSlideY = (1f - textAlpha) * 4.dp.toPx()
        }

        // 1. Center-out traced orbital baseline + laser tips / zenith spark
        val activeAccent = accentColor.value
        val activeDim = activeAccent.copy(alpha = 0.42f)
        if (trace > 0.01f) {
          val leftX = cx - halfSpan * trace
          val rightX = cx + halfSpan * trace
          val baseColor = if (copy.isError) {
            Crimson.copy(alpha = 0.62f * lineAlpha)
          } else {
            activeAccent.copy(alpha = 0.45f * lineAlpha)
          }
          drawLine(
            color = baseColor,
            start = Offset(leftX, baselineY),
            end = Offset(rightX, baselineY),
            strokeWidth = strokeBase,
            cap = StrokeCap.Round,
          )

          if (trace in 0.02f..0.985f) {
            drawCircle(activeAccent.copy(alpha = 0.35f * lineAlpha), 4.0f.dp.toPx(), Offset(leftX, baselineY))
            drawCircle(Color.White.copy(alpha = 0.92f * lineAlpha), 1.4f.dp.toPx(), Offset(leftX, baselineY))
            drawCircle(activeAccent.copy(alpha = 0.35f * lineAlpha), 4.0f.dp.toPx(), Offset(rightX, baselineY))
            drawCircle(Color.White.copy(alpha = 0.92f * lineAlpha), 1.4f.dp.toPx(), Offset(rightX, baselineY))
          } else if (renderState is OtaState.Downloading && trace >= 0.985f) {
            // Left-to-right laser progress bar
            val fillX = (size.width * animatedProgress).coerceAtLeast(2.dp.toPx())
            drawLine(
              color = activeAccent.copy(alpha = lineAlpha),
              start = Offset(0f, baselineY),
              end = Offset(fillX, baselineY),
              strokeWidth = strokeFill,
              cap = StrokeCap.Round,
            )
            drawCircle(activeAccent.copy(alpha = 0.38f * lineAlpha), 4.2f.dp.toPx(), Offset(fillX, baselineY))
            drawCircle(TextPureWhite.copy(alpha = 0.95f * lineAlpha), 1.5f.dp.toPx(), Offset(fillX, baselineY))
          } else if (copy.isScanning && trace >= 0.985f) {
            // Sweeping laser pulse along the baseline during scan/apply
            val pulseCenter = size.width * sweepPhase
            val pulseHalf = size.width * 0.14f
            val pStart = (pulseCenter - pulseHalf).coerceIn(0f, size.width)
            val pEnd = (pulseCenter + pulseHalf).coerceIn(0f, size.width)
            drawLine(
              color = activeAccent.copy(alpha = 0.9f * lineAlpha),
              start = Offset(pStart, baselineY),
              end = Offset(pEnd, baselineY),
              strokeWidth = strokeFill,
              cap = StrokeCap.Round,
            )
            drawCircle(activeAccent.copy(alpha = 0.35f * lineAlpha), 4.0f.dp.toPx(), Offset(pulseCenter, baselineY))
            drawCircle(TextPureWhite.copy(alpha = 0.92f * lineAlpha), 1.4f.dp.toPx(), Offset(pulseCenter, baselineY))
          } else if (trace >= 0.985f) {
            // Center zenith telemetry pip aligned with HudClock axis
            val pipAlpha = textAlpha * 0.92f
            drawCircle(activeDim.copy(alpha = 0.45f * pipAlpha), 4.0f.dp.toPx(), Offset(cx, baselineY))
            drawCircle(
              color = activeAccent.copy(alpha = pipAlpha),
              radius = 1.8f.dp.toPx(),
              center = Offset(cx, baselineY),
            )
          }
        }

        if (sparkAlpha > 0.01f) {
          drawCircle(activeAccent.copy(alpha = 0.38f * sparkAlpha), 4.6f.dp.toPx(), Offset(cx, baselineY))
          drawCircle(Color.White.copy(alpha = 0.95f * sparkAlpha), 1.6f.dp.toPx(), Offset(cx, baselineY))
        }

        // 2. Left-aligned micro-eyebrow and primary readout matching HudClock monospace style
        if (textAlpha > 0.005f) {
          val rightReserve = when {
            copy.badge != null && copy.dismissible -> 96.dp.toPx()
            copy.badge != null -> 56.dp.toPx()
            copy.dismissible -> 40.dp.toPx()
            else -> 0f
          }
          val availWidth = (size.width - rightReserve).coerceAtLeast(48.dp.toPx())

          eyebrowPaint.color = android.graphics.Color.argb(
            (180f * textAlpha).roundToInt().coerceIn(0, 180),
            203,
            213,
            225,
          )
          val eyebrowBaseline = centerBaseline(eyebrowPaint, 8.dp.toPx()) + textSlideY
          if (isDismissing) {
            drawContext.canvas.nativeCanvas.drawText(copy.eyebrow, 0f, eyebrowBaseline, eyebrowPaint)
          } else {
            drawDecrypt(copy.eyebrow, decryptP, 31, eyebrowPaint, 0f, eyebrowBaseline)
          }

          val fittedLabel = TextUtils.ellipsize(
            copy.label,
            labelPaint,
            availWidth,
            TextUtils.TruncateAt.END,
          ).toString()
          val alphaInt = (255f * textAlpha).roundToInt().coerceIn(0, 255)
          labelPaint.color = if (copy.isError) {
            android.graphics.Color.argb(alphaInt, 255, 77, 94)
          } else {
            android.graphics.Color.argb(alphaInt, 248, 250, 252)
          }
          val labelBaseline = centerBaseline(labelPaint, 24.dp.toPx()) + textSlideY
          if (isDismissing) {
            drawContext.canvas.nativeCanvas.drawText(fittedLabel, 0f, labelBaseline, labelPaint)
          } else {
            drawDecrypt(fittedLabel, decryptP, 47, labelPaint, 0f, labelBaseline)
          }
        }
      },
  ) {
    Row(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(bottom = 11.dp)
        .graphicsLayer {
          alpha = contentAlpha
          translationX = rightSlidePx
        },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (copy.badge != null) {
        Text(
          text = copy.badge,
          color = if (copy.isError) CrimsonBright else launcherStyle.accent,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
        )
      }
      if (copy.dismissible) {
        Spacer(Modifier.size(4.dp))
        Box(
          modifier = Modifier
            .size(36.dp)
            .clickable(
              interactionSource = dismissInteraction,
              indication = null,
              enabled = !isDismissing,
            ) {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onDismiss()
            },
          contentAlignment = Alignment.CenterEnd,
        ) {
          Text(
            text = "×",
            color = TextPureWhite.copy(alpha = 0.65f),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
          )
        }
      }
    }
  }
}
