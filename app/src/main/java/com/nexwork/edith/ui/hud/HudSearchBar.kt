package com.nexwork.edith.ui.hud

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.LocalCrimsonMonetActive
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

private val SearchOrbitalSlate = Color(0xFF64748B)

/**
 * Top inverted orbital arc search console (Option B) aligned with [HudClock] and [HudOtaPill]:
 * zero box fill, bitmap grid, or corner brackets. A shallow 1dp orbital arc crowns the prompt
 * with 7 apex micro-ticks that flare on single-match or `!command` lock, paired with eased
 * center-out laser trace on open and clean cipher-free collapse on close.
 */
@Composable
fun HudSearchBar(
  isOpen: Boolean,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onDismiss: () -> Unit,
  apps: List<AppEntry>,
  onLaunchApp: (AppEntry) -> Unit,
  modifier: Modifier = Modifier,
  autoFocus: Boolean = true,
  idleDismiss: Boolean = true,
  scopeLabel: String? = null,
  scopeCount: Int = 0,
  onExitGroup: () -> Unit = {},
) {
  val density = LocalDensity.current
  val haptic = LocalHapticFeedback.current
  val crimsonMonetActive = rememberUpdatedState(LocalCrimsonMonetActive.current)
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  var textFieldValue by remember {
    mutableStateOf(TextFieldValue(text = searchQuery, selection = TextRange(searchQuery.length)))
  }
  LaunchedEffect(searchQuery) {
    if (searchQuery != textFieldValue.text) {
      textFieldValue = TextFieldValue(text = searchQuery, selection = TextRange(searchQuery.length))
    }
  }

  var lastInteractionTimestamp by remember { mutableLongStateOf(0L) }

  // Hold the last typed query until dismiss finishes unwinding so readouts don't flash back to STANDBY.
  var frozen by remember { mutableStateOf(TextFieldValue()) }
  LaunchedEffect(isOpen, textFieldValue) {
    if (isOpen) frozen = textFieldValue
  }
  val shownQuery = if (isOpen) searchQuery else frozen.text
  val shownText = if (isOpen) textFieldValue.text else frozen.text

  val matchingApps = remember(shownQuery, apps) {
    filterMatchingApps(shownQuery, apps)
  }

  val trimmedQuery = shownQuery.trim()
  val typing = trimmedQuery.isNotEmpty()
  val isCommand = trimmedQuery.startsWith("!")
  val isLaunchLocked = isCommand || (typing && matchingApps.size == 1)
  val scope = scopeLabel?.takeIf { !typing }

  val eyebrowText = when {
    isCommand -> "CMD // EXECUTE"
    typing -> "SYS.QUERY // ACTIVE"
    scope != null -> "◀ $scope"
    else -> "SYS.QUERY // STANDBY"
  }
  val countLabel = when {
    isCommand -> "CMD"
    typing -> "${matchingApps.size} ${if (matchingApps.size == 1) "HIT" else "HITS"}"
    scope != null -> "$scopeCount APPS"
    else -> "${apps.size} INDEX"
  }

  val eyebrowPaint = remember(density) { monoPaint(density, 7.5f, 1.15f, Paint.Align.LEFT, 165) }

  val revealAnim = remember { Animatable(0f) }
  var mounted by remember { mutableStateOf(isOpen) }
  var isDismissing by remember { mutableStateOf(false) }
  LaunchedEffect(isOpen) {
    if (isOpen) {
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

  LaunchedEffect(isOpen) {
    if (isOpen && autoFocus) {
      delay(160L)
      focusRequester.requestFocus()
      keyboardController?.show()
      lastInteractionTimestamp = System.currentTimeMillis()
    }
  }

  // Empty query 4s -> dismiss; multi-match idle 1s -> hide keyboard
  LaunchedEffect(isOpen, textFieldValue, lastInteractionTimestamp, matchingApps.size) {
    if (!isOpen) return@LaunchedEffect
    if (textFieldValue.composition != null) return@LaunchedEffect
    if (textFieldValue.text.isEmpty()) {
      if (!idleDismiss) return@LaunchedEffect
      delay(4000L)
      if (isOpen && textFieldValue.text.isEmpty() && textFieldValue.composition == null) {
        keyboardController?.hide()
        focusManager.clearFocus()
        onDismiss()
      }
    } else if (matchingApps.size > 1) {
      delay(1000L)
      if (isOpen && textFieldValue.text.isNotEmpty() && textFieldValue.composition == null) {
        keyboardController?.hide()
        focusManager.clearFocus()
      }
    }
  }

  if (!mounted) return

  val scanTransition = rememberInfiniteTransition(label = "searchArcScan")
  val scanSweep by scanTransition.animateFloat(
    initialValue = -1f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 3200, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "searchArcSweep",
  )

  val reveal = revealAnim.value
  val contentAlpha = if (isDismissing) {
    val d = (1f - reveal).coerceIn(0f, 1f)
    1f - hudEaseInCubic(phase(d, 0.00f, 0.46f))
  } else {
    hudEaseOutCubic(phase(reveal, 0.36f, 0.92f))
  }
  val contentShiftPx = with(density) {
    if (isDismissing) (1f - contentAlpha) * 4.dp.toPx() else (1f - contentAlpha) * 5.dp.toPx()
  }

  val scopeExitInteraction = remember { MutableInteractionSource() }
  val clearInteraction = remember { MutableInteractionSource() }

  Box(
    modifier
      .fillMaxWidth()
      .padding(start = 18.dp, end = 18.dp, top = 70.dp)
      .height(78.dp)
      .graphicsLayer { alpha = if (reveal <= 0.001f) 0f else 1f }
      // Tapping the console area summons focus/keyboard even when opened in browse mode.
      .pointerInput(Unit) {
        detectTapGestures {
          focusRequester.requestFocus()
          keyboardController?.show()
        }
      },
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .drawWithCache {
          val cx = size.width * 0.5f
          val arcApexY = 22.dp.toPx()
          // Shallow arc radius (~1.95x width -> ~21dp sagitta) keeps chord text completely clear.
          val arcRadius = (size.width * 1.95f).coerceAtLeast(180f)
          val arcCenterY = arcApexY + arcRadius
          val halfSpan = size.width * 0.5f
          val maxTheta = asin((halfSpan / arcRadius).coerceIn(-1f, 1f))
          val arcStroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
          val arcPath = Path()

          val tickCount = 7
          val tickStepDeg = 2.15f
          val tickTheta = FloatArray(tickCount) { idx ->
            Math.toRadians(((idx - 3) * tickStepDeg).toDouble()).toFloat()
          }
          val eyebrowY = 8.dp.toPx()
          val eyebrowBaseline = centerBaseline(eyebrowPaint, eyebrowY)

          onDrawBehind {
            val r = revealAnim.value
            if (r <= 0.001f) return@onDrawBehind

            val trace: Float
            val arcAlpha: Float
            val sparkAlpha: Float
            val ticksP: Float
            val eyebrowP: Float
            val eyebrowDecrypt: Float
            val eyebrowOffsetY: Float

            if (isDismissing) {
              val d = (1f - r).coerceIn(0f, 1f)
              eyebrowP = 1f - hudEaseInCubic(phase(d, 0.00f, 0.44f))
              eyebrowDecrypt = 1f
              eyebrowOffsetY = (1f - eyebrowP) * 3.dp.toPx()
              ticksP = 1f - hudEaseInCubic(phase(d, 0.22f, 0.62f))
              trace = 1f - hudEaseInCubic(phase(d, 0.36f, 0.94f))
              arcAlpha = (trace * 4.5f).coerceIn(0f, 1f)
              sparkAlpha = if (d >= 0.86f) 1f - phase(d, 0.88f, 1.00f) else 0f
            } else {
              trace = hudEaseOutCubic(phase(r, 0.00f, 0.52f))
              arcAlpha = 1f
              sparkAlpha = if (r < 0.20f) 1f - phase(r, 0.06f, 0.20f) else 0f
              ticksP = hudEaseOutCubic(phase(r, 0.24f, 0.72f))
              eyebrowDecrypt = phase(r, 0.26f, 0.78f)
              eyebrowP = hudEaseOutCubic(phase(eyebrowDecrypt, 0f, 0.30f))
              eyebrowOffsetY = (1f - hudEaseOutCubic(phase(r, 0.26f, 0.74f))) * 3.dp.toPx()
            }

            val isMonet = crimsonMonetActive.value

            // 1. Shallow inverted orbital horizon arc + twin laser heads / idle orbital pulse
            if (trace > 0.01f) {
              arcPath.reset()
              val segments = 44
              for (i in 0..segments) {
                val frac = i / segments.toFloat()
                val u = -trace + 2f * trace * frac
                val theta = u * maxTheta
                val px = cx + arcRadius * sin(theta)
                val py = arcCenterY - arcRadius * cos(theta)
                if (i == 0) arcPath.moveTo(px, py) else arcPath.lineTo(px, py)
              }
              val arcColor = when {
                isLaunchLocked -> Crimson.copy(alpha = 0.85f * arcAlpha)
                typing || isMonet -> Crimson.copy(alpha = 0.65f * arcAlpha)
                else -> SearchOrbitalSlate.copy(alpha = 0.52f * arcAlpha)
              }
              drawPath(path = arcPath, color = arcColor, style = arcStroke)

              if (trace in 0.02f..0.985f) {
                val tipTheta = trace * maxTheta
                val lx = cx - arcRadius * sin(tipTheta)
                val rx = cx + arcRadius * sin(tipTheta)
                val ty = arcCenterY - arcRadius * cos(tipTheta)
                drawCircle(CrimsonBright.copy(alpha = 0.35f * arcAlpha), 4.0f.dp.toPx(), Offset(lx, ty))
                drawCircle(Color.White.copy(alpha = 0.92f * arcAlpha), 1.4f.dp.toPx(), Offset(lx, ty))
                drawCircle(CrimsonBright.copy(alpha = 0.35f * arcAlpha), 4.0f.dp.toPx(), Offset(rx, ty))
                drawCircle(Color.White.copy(alpha = 0.92f * arcAlpha), 1.4f.dp.toPx(), Offset(rx, ty))
              } else if (trace >= 0.985f && !isDismissing) {
                // Travelling telemetry dot gliding smoothly along the inverted orbital arc
                val sweepTheta = scanSweep * maxTheta * 0.88f
                val sx = cx + arcRadius * sin(sweepTheta)
                val sy = arcCenterY - arcRadius * cos(sweepTheta)
                val dotAlpha = if (typing) 0.88f else 0.45f
                drawCircle(CrimsonBright.copy(alpha = 0.28f * dotAlpha), 3.6f.dp.toPx(), Offset(sx, sy))
                drawCircle(TextPureWhite.copy(alpha = 0.9f * dotAlpha), 1.2f.dp.toPx(), Offset(sx, sy))
              }
            }

            if (sparkAlpha > 0.01f) {
              drawCircle(CrimsonBright.copy(alpha = 0.38f * sparkAlpha), 4.8f.dp.toPx(), Offset(cx, arcApexY))
              drawCircle(Color.White.copy(alpha = 0.95f * sparkAlpha), 1.6f.dp.toPx(), Offset(cx, arcApexY))
            }

            // 2. 7 apex micro-ticks rising above the inverted arc
            if (ticksP > 0.01f) {
              for (idx in 0 until tickCount) {
                val distIdx = kotlin.math.abs(idx - 3)
                val localPop = hudEaseOutCubic(phase(ticksP, distIdx * 0.14f, distIdx * 0.14f + 0.48f))
                if (localPop <= 0.01f) continue

                val theta = tickTheta[idx]
                val nx = sin(theta)
                val ny = -cos(theta)
                val bx = cx + arcRadius * sin(theta)
                val by = arcCenterY - arcRadius * cos(theta)
                val isCenter = idx == 3
                val len = (if (isCenter) 6.5.dp.toPx() else 3.8.dp.toPx()) * localPop
                val strokeW = if (isCenter) 1.5.dp.toPx() else 1.dp.toPx()
                val lit = isCenter || isLaunchLocked || (typing && distIdx <= 1)
                val tickColor = if (lit) {
                  CrimsonBright.copy(alpha = 0.92f * localPop)
                } else {
                  SearchOrbitalSlate.copy(alpha = 0.55f * localPop)
                }
                drawLine(
                  color = tickColor,
                  start = Offset(bx, by),
                  end = Offset(bx + nx * len, by + ny * len),
                  strokeWidth = strokeW,
                  cap = StrokeCap.Round,
                )
              }
            }

            // 3. Centered micro-eyebrow above the apex ticks
            if (eyebrowP > 0.005f) {
              val w = eyebrowPaint.measureText(eyebrowText)
              val ex = cx - w * 0.5f
              val eb = eyebrowBaseline + eyebrowOffsetY
              val a = (if (isLaunchLocked) 230f else 175f) * eyebrowP
              eyebrowPaint.color = if (isCommand) {
                android.graphics.Color.argb(a.roundToInt().coerceIn(0, 255), 255, 77, 94)
              } else {
                android.graphics.Color.argb(a.roundToInt().coerceIn(0, 255), 203, 213, 225)
              }
              if (isDismissing) {
                drawContext.canvas.nativeCanvas.drawText(eyebrowText, ex, eb, eyebrowPaint)
              } else {
                drawDecrypt(eyebrowText, eyebrowDecrypt, eyebrowText.hashCode(), eyebrowPaint, ex, eb)
              }
            }
          }
        },
    )

    // Clickable eyebrow target when drilled into a category (`◀ <GROUP>`)
    if (scope != null) {
      Box(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .width(180.dp)
          .height(26.dp)
          .clickable(
            interactionSource = scopeExitInteraction,
            indication = null,
            enabled = !isDismissing,
          ) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onExitGroup()
          },
      )
    }

    // Input chord cleanly below the inverted arc shoulders (46dp..78dp)
    Row(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .height(34.dp)
        .graphicsLayer {
          alpha = contentAlpha
          translationY = contentShiftPx
        },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = ">",
        color = if (typing) CrimsonBright else TextSilver.copy(alpha = 0.65f),
        fontSize = 18.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
      )
      Spacer(Modifier.width(8.dp))
      Box(modifier = Modifier.weight(1f)) {
        BasicTextField(
          value = if (isOpen) textFieldValue else frozen,
          onValueChange = { newVal ->
            lastInteractionTimestamp = System.currentTimeMillis()
            textFieldValue = newVal
            if (newVal.text != searchQuery) onSearchQueryChange(newVal.text)
          },
          singleLine = true,
          textStyle = TextStyle(
            color = TextPureWhite,
            fontSize = 19.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.6.sp,
          ),
          cursorBrush = SolidColor(CrimsonBright),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(
            onSearch = {
              keyboardController?.hide()
              focusManager.clearFocus()
              val top = matchingApps.firstOrNull()
              if (top != null) {
                onLaunchApp(top)
                onDismiss()
              }
            },
          ),
          modifier = Modifier
            .focusRequester(focusRequester)
            .fillMaxWidth(),
          decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
              if (shownText.isEmpty()) {
                Text(
                  text = "SEARCH TELEMETRY",
                  color = TextPureWhite.copy(alpha = 0.28f),
                  fontSize = 19.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Medium,
                  letterSpacing = 1.6.sp,
                )
              }
              innerTextField()
            }
          },
        )
      }

      Spacer(Modifier.width(8.dp))
      Text(
        text = countLabel,
        color = if (typing) CrimsonBright else TextSilver.copy(alpha = 0.75f),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
      )

      if (shownText.isNotEmpty()) {
        Box(
          modifier = Modifier
            .size(34.dp)
            .clickable(
              interactionSource = clearInteraction,
              indication = null,
              enabled = isOpen && !isDismissing,
            ) {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              lastInteractionTimestamp = System.currentTimeMillis()
              textFieldValue = TextFieldValue("")
              onSearchQueryChange("")
              focusRequester.requestFocus()
              keyboardController?.show()
            },
          contentAlignment = Alignment.CenterEnd,
        ) {
          Text(
            text = "×",
            color = TextPureWhite.copy(alpha = 0.65f),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
          )
        }
      }
    }
  }
}
