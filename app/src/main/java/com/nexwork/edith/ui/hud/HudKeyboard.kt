package com.nexwork.edith.ui.hud

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pure-Kotlin query-editing & label helpers for [HudKeyboard] (JVM unit-testable). */
object HudKeyboardLogic {

  val QWERTY_ROW_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
  val QWERTY_ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
  val QWERTY_ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m")

  val SYMBOLS_ROW_1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
  val SYMBOLS_ROW_2 = listOf("+", "-", "*", "/", "(", ")", "=", "%", ":")
  val SYMBOLS_ROW_3 = listOf("!", "?", "@", "#", "_", "&", "\"")

  val COMMAND_CHIPS = listOf("!monet", "!ota", "?")

  fun appendToken(current: String, token: String): String = current + token

  fun backspace(current: String): String = if (current.isEmpty()) "" else current.dropLast(1)

  fun applyCommandChip(current: String, chip: String): String {
    val trimmed = current.trim()
    val prefix = if (chip == "?") "? " else "$chip "
    return if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("?")) {
      prefix
    } else {
      "$prefix$trimmed"
    }
  }

  fun actionButtonLabel(query: String, matchCount: Int): String {
    val trimmed = query.trim()
    val parsed = parseHudCommand(trimmed)
    return when {
      parsed is HudCommand.MathResult -> "CALC"
      parsed != null || trimmed.startsWith("!") || trimmed.startsWith("?") -> "EXEC"
      trimmed.isNotEmpty() && matchCount == 1 -> "GO"
      else -> "SEARCH"
    }
  }
}

/**
 * In-app Stark HUD keyboard rendered directly inside [com.nexwork.edith.ui.main.MainScreen]
 * for the launcher search console. Avoids Android's `InputMethod` window completely so Gboard's
 * `OPTOUT_EDGE_TO_EDGE` white strip never appears, keeps `statusBars` hidden 100% of the time,
 * and opens/closes in sync with the 120Hz deck animation.
 */
@Composable
fun HudKeyboard(
  visible: Boolean,
  query: String,
  matchCount: Int,
  onAppend: (String) -> Unit,
  onBackspace: () -> Unit,
  onClearQuery: () -> Unit,
  onCommandChip: (String) -> Unit,
  onSubmit: () -> Unit,
  onHideKeyboard: () -> Unit,
  onSwitchToSystemIme: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val style = LocalLauncherStyle.current
  val accent = style.accent
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  var symbolsLayer by remember { mutableStateOf(false) }

  val currentOnAppend by rememberUpdatedState(onAppend)
  val currentOnBackspace by rememberUpdatedState(onBackspace)
  val currentOnClear by rememberUpdatedState(onClearQuery)
  val currentOnSubmit by rememberUpdatedState(onSubmit)
  val currentOnHide by rememberUpdatedState(onHideKeyboard)

  val revealAnim = remember { Animatable(0f) }
  var mounted by remember { mutableStateOf(visible) }
  var isDismissing by remember { mutableStateOf(false) }

  LaunchedEffect(visible) {
    if (visible) {
      isDismissing = false
      mounted = true
      val showMs = ((1f - revealAnim.value).coerceIn(0f, 1f) * 1000f).roundToInt().coerceAtLeast(1)
      revealAnim.animateTo(1f, tween(showMs, easing = LinearEasing))
      haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    } else if (mounted) {
      isDismissing = true
      val hideMs = (revealAnim.value.coerceIn(0f, 1f) * 200f).roundToInt().coerceAtLeast(1)
      revealAnim.animateTo(0f, tween(hideMs, easing = LinearEasing))
      mounted = false
      isDismissing = false
    }
  }

  if (!mounted) return

  val r = revealAnim.value
  val deckP = if (isDismissing) {
    1f - hudEaseInCubic(phase(1f - r, 0.20f, 1.00f))
  } else {
    hudEaseOutCubic(phase(r, 0.00f, 0.42f))
  }
  val crownTrace = if (isDismissing) {
    1f - hudEaseInCubic(phase(1f - r, 0.25f, 0.95f))
  } else {
    hudEaseOutCubic(phase(r, 0.00f, 0.42f))
  }
  val stripP = if (isDismissing) {
    1f - hudEaseInCubic(phase(1f - r, 0.00f, 0.50f))
  } else {
    hudEaseOutCubic(phase(r, 0.30f, 0.76f))
  }
  val actionLockFlare = if (!isDismissing && r in 0.62f..0.98f) {
    val p = phase(r, 0.62f, 0.98f)
    if (p < 0.5f) p * 2f else (1f - p) * 2f
  } else {
    0f
  }
  val dropPx = with(density) { 18.dp.toPx() }
  val keyShiftPx = with(density) { 5.dp.toPx() }

  // Read revealAnim.value ONLY inside graphicsLayer / drawBehind lambdas -> 0 recompositions/frame.
  val deckProgress: () -> Float = {
    val r = revealAnim.value
    if (isDismissing) 1f - hudEaseInCubic(phase(1f - r, 0.20f, 1.00f))
    else hudEaseOutCubic(phase(r, 0.00f, 0.42f))
  }
  val crownProgress: () -> Float = {
    val r = revealAnim.value
    if (isDismissing) 1f - hudEaseInCubic(phase(1f - r, 0.25f, 0.95f))
    else hudEaseOutCubic(phase(r, 0.00f, 0.42f))
  }
  val stripProgress: () -> Float = {
    val r = revealAnim.value
    if (isDismissing) 1f - hudEaseInCubic(phase(1f - r, 0.00f, 0.50f))
    else hudEaseOutCubic(phase(r, 0.30f, 0.76f))
  }
  val flareProgress: () -> Float = {
    val r = revealAnim.value
    if (!isDismissing && r in 0.62f..0.98f) {
      val p = phase(r, 0.62f, 0.98f)
      if (p < 0.5f) p * 2f else (1f - p) * 2f
    } else {
      0f
    }
  }

  fun keyRevealFn(colFraction: Float): () -> Float {
    val dist = abs(colFraction - 0.5f) * 2f
    return {
      val r = revealAnim.value
      if (isDismissing) {
        val d = 1f - r
        val start = (1f - dist) * 0.14f
        1f - hudEaseInCubic(phase(d, start, start + 0.48f))
      } else {
        val start = 0.20f + dist * 0.24f
        hudEaseOutCubic(phase(r, start, start + 0.32f))
      }
    }
  }

  fun keyDecryptFn(colFraction: Float): () -> Float {
    val dist = abs(colFraction - 0.5f) * 2f
    val start = 0.18f + dist * 0.22f
    val end = (start + 0.48f).coerceAtMost(0.98f)
    return {
      val r = revealAnim.value
      if (isDismissing || r >= 0.995f) 1f else phase(r, start, end)
    }
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .graphicsLayer {
        val p = deckProgress()
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * dropPx
      },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(style.voidTop.copy(alpha = 0.94f))
        .drawBehind {
          val crownTrace = crownProgress()
          val halfW = size.width * 0.5f
          val span = halfW * crownTrace
          if (span > 1f) {
            drawLine(
              color = accent.copy(alpha = 0.65f * crownTrace),
              start = Offset(halfW - span, 0f),
              end = Offset(halfW + span, 0f),
              strokeWidth = 1.dp.toPx(),
            )
            if (crownTrace in 0.03f..0.97f) {
              val sparkA = (1f - crownTrace) * 0.9f
              drawCircle(accent.copy(alpha = 0.35f * sparkA), 4.dp.toPx(), Offset(halfW - span, 0f))
              drawCircle(TextPureWhite.copy(alpha = sparkA), 1.4.dp.toPx(), Offset(halfW - span, 0f))
              drawCircle(accent.copy(alpha = 0.35f * sparkA), 4.dp.toPx(), Offset(halfW + span, 0f))
              drawCircle(TextPureWhite.copy(alpha = sparkA), 1.4.dp.toPx(), Offset(halfW + span, 0f))
            }
          }
        }
        .navigationBarsPadding()
        .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      // Command / Telemetry strip + swipe-down collapse + SYS IME fallback
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(30.dp)
          .graphicsLayer {
            val sp = stripProgress()
            alpha = sp
            translationY = (1f - sp) * keyShiftPx
          }
          .pointerInput(Unit) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              var dy = 0f
              while (true) {
                val event = awaitPointerEvent()
                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                dy += ch.position.y - ch.previousPosition.y
                if (dy > 36.dp.toPx()) {
                  ch.consume()
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  currentOnHide()
                  break
                }
              }
            }
          },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Row(
          modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          HudKeyboardLogic.COMMAND_CHIPS.forEachIndexed { idx, chip ->
            val chipDecrypt: () -> Float = {
              val r = revealAnim.value
              if (isDismissing || r >= 0.995f) 1f else phase(r, 0.28f + idx * 0.05f, 0.85f)
            }
            StripChip(
              label = chip,
              accent = accent,
              decryptProgress = chipDecrypt,
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCommandChip(chip)
              },
            )
          }
        }
        StripChip(
          label = "SYS IME",
          accent = TextSilver,
          decryptProgress = {
            val r = revealAnim.value
            if (isDismissing || r >= 0.995f) 1f else phase(r, 0.45f, 0.92f)
          },
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSwitchToSystemIme()
          },
        )
        StripChip(
          label = "v",
          accent = accent,
          decryptProgress = { 1f },
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnHide()
          },
        )
      }

      val row1 = if (symbolsLayer) HudKeyboardLogic.SYMBOLS_ROW_1 else HudKeyboardLogic.QWERTY_ROW_1
      val row2 = if (symbolsLayer) HudKeyboardLogic.SYMBOLS_ROW_2 else HudKeyboardLogic.QWERTY_ROW_2
      val row3 = if (symbolsLayer) HudKeyboardLogic.SYMBOLS_ROW_3 else HudKeyboardLogic.QWERTY_ROW_3

      // Row 1: 10 keys
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        row1.forEachIndexed { idx, ch ->
          val col = (idx + 0.5f) / row1.size
          val kpFn = remember(col) { keyRevealFn(col) }
          val kdFn = remember(col) { keyDecryptFn(col) }
          HudKeycap(
            label = ch.uppercase(),
            accent = accent,
            decryptProgress = kdFn,
            onPress = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              currentOnAppend(ch)
            },
            modifier = Modifier
              .weight(1f)
              .graphicsLayer {
                val kp = kpFn()
                alpha = kp
                translationY = (1f - kp) * keyShiftPx
              },
          )
        }
      }

      // Row 2: 9 keys, slightly inset on edges like QWERTY
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        row2.forEachIndexed { idx, ch ->
          val col = (idx + 0.5f) / row2.size
          val kpFn = remember(col) { keyRevealFn(col) }
          val kdFn = remember(col) { keyDecryptFn(col) }
          HudKeycap(
            label = ch.uppercase(),
            accent = accent,
            decryptProgress = kdFn,
            onPress = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              currentOnAppend(ch)
            },
            modifier = Modifier
              .weight(1f)
              .graphicsLayer {
                val kp = kpFn()
                alpha = kp
                translationY = (1f - kp) * keyShiftPx
              },
          )
        }
      }

      // Row 3: 123/ABC + 7 keys + Backspace (tap=del, hold=repeat, swipe-left=clear)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val symP = remember { keyRevealFn(0.05f) }
        val symD = remember { keyDecryptFn(0.05f) }
        HudFunctionKey(
          label = if (symbolsLayer) "ABC" else "123",
          accent = accent,
          highlighted = symbolsLayer,
          decryptProgress = symD,
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            symbolsLayer = !symbolsLayer
          },
          modifier = Modifier
            .weight(1.45f)
            .graphicsLayer {
              val kp = symP()
              alpha = kp
              translationY = (1f - kp) * keyShiftPx
            },
        )
        row3.forEachIndexed { idx, ch ->
          val col = (idx + 1.5f) / 9f
          val kpFn = remember(col) { keyRevealFn(col) }
          val kdFn = remember(col) { keyDecryptFn(col) }
          HudKeycap(
            label = ch.uppercase(),
            accent = accent,
            decryptProgress = kdFn,
            onPress = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              currentOnAppend(ch)
            },
            modifier = Modifier
              .weight(1f)
              .graphicsLayer {
                val kp = kpFn()
                alpha = kp
                translationY = (1f - kp) * keyShiftPx
              },
          )
        }
        val delP = remember { keyRevealFn(0.95f) }
        val delD = remember { keyDecryptFn(0.95f) }
        BackspaceKey(
          accent = accent,
          decryptProgress = delD,
          onTapDelete = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnBackspace()
          },
          onSwipeClear = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnClear()
          },
          scope = scope,
          modifier = Modifier
            .weight(1.45f)
            .graphicsLayer {
              val kp = delP()
              alpha = kp
              translationY = (1f - kp) * keyShiftPx
            },
        )
      }

      // Row 4: !  ,  [ SPACE ]  .  SEARCH/EXEC/GO
      val actionLabel = HudKeyboardLogic.actionButtonLabel(query, matchCount)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val pBang = remember { keyRevealFn(0.05f) }
        val dBang = remember { keyDecryptFn(0.05f) }
        HudKeycap(
          label = "!",
          accent = accent,
          decryptProgress = dBang,
          onPress = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnAppend("!")
          },
          modifier = Modifier
            .weight(1f)
            .graphicsLayer { val kp = pBang(); alpha = kp; translationY = (1f - kp) * keyShiftPx },
        )
        val pComma = remember { keyRevealFn(0.18f) }
        val dComma = remember { keyDecryptFn(0.18f) }
        HudKeycap(
          label = ",",
          accent = accent,
          decryptProgress = dComma,
          onPress = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnAppend(",")
          },
          modifier = Modifier
            .weight(0.9f)
            .graphicsLayer { val kp = pComma(); alpha = kp; translationY = (1f - kp) * keyShiftPx },
        )
        val pSpace = remember { keyRevealFn(0.50f) }
        val dSpace = remember { keyDecryptFn(0.50f) }
        HudKeycap(
          label = "SPACE",
          accent = accent,
          isSubtle = true,
          showPopup = false,
          decryptProgress = dSpace,
          onPress = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnAppend(" ")
          },
          modifier = Modifier
            .weight(4.1f)
            .graphicsLayer { val kp = pSpace(); alpha = kp; translationY = (1f - kp) * keyShiftPx },
        )
        val pDot = remember { keyRevealFn(0.78f) }
        val dDot = remember { keyDecryptFn(0.78f) }
        HudKeycap(
          label = ".",
          accent = accent,
          decryptProgress = dDot,
          onPress = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnAppend(".")
          },
          modifier = Modifier
            .weight(0.9f)
            .graphicsLayer { val kp = pDot(); alpha = kp; translationY = (1f - kp) * keyShiftPx },
        )
        val pAction = remember { keyRevealFn(0.95f) }
        val dAction = remember { keyDecryptFn(0.95f) }
        HudFunctionKey(
          label = actionLabel,
          accent = accent,
          highlighted = true,
          lockFlare = flareProgress,
          decryptProgress = dAction,
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnSubmit()
          },
          modifier = Modifier
            .weight(2.1f)
            .graphicsLayer { val kp = pAction(); alpha = kp; translationY = (1f - kp) * keyShiftPx },
        )
      }
    }
  }
}

private fun DrawScope.drawCenteredDecrypt(
  text: String,
  progress: Float,
  seed: Int,
  paint: Paint,
  settledColor: Color,
  scramblingColor: Color,
) {
  if (text.isEmpty()) return
  val fm = paint.fontMetrics
  val baseline = size.height * 0.5f - (fm.ascent + fm.descent) * 0.5f
  val cx = size.width * 0.5f
  val canvas = drawContext.canvas.nativeCanvas
  if (progress >= 0.995f) {
    paint.color = settledColor.toArgb()
    canvas.drawText(text, cx, baseline, paint)
    return
  }
  val resolved = (progress * (text.length + 1)).toInt().coerceAtMost(text.length)
  val salt = (progress * 18f).toInt()
  val chars = CharArray(text.length) { i ->
    when {
      i < resolved -> text[i]
      text[i] == ' ' -> ' '
      else -> decryptGlyph(seed, i, salt)
    }
  }
  paint.color = (if (resolved >= text.length) settledColor else scramblingColor).toArgb()
  canvas.drawText(chars, 0, chars.size, cx, baseline, paint)
}

@Composable
private fun rememberKeyPaint(
  sizeSp: Float,
  typeface: Typeface?,
  fontScale: Float,
  bold: Boolean,
  letterSpacingEm: Float = 0.03f,
): Paint {
  val density = LocalDensity.current
  val baseTf = typeface ?: Typeface.MONOSPACE
  return remember(density, sizeSp, baseTf, fontScale, bold, letterSpacingEm) {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textAlign = Paint.Align.CENTER
      textSize = with(density) { (sizeSp * fontScale).sp.toPx() }
      this.typeface = if (bold) Typeface.create(baseTf, Typeface.BOLD) else baseTf
      letterSpacing = letterSpacingEm
    }
  }
}

@Composable
private fun StripChip(
  label: String,
  accent: Color,
  onClick: () -> Unit,
  decryptProgress: () -> Float = { 1f },
) {
  val style = LocalLauncherStyle.current
  val interaction = remember { MutableInteractionSource() }
  val paint = rememberKeyPaint(9.5f, style.telemetryTypeface, style.fontScale, bold = true, letterSpacingEm = 0.08f)
  val settledWidth = remember(label, paint) { paint.measureText(label) }
  val padH = with(LocalDensity.current) { 16.dp }
  Box(
    modifier = Modifier
      .height(24.dp)
      .width(with(LocalDensity.current) { settledWidth.toDp() } + padH)
      .background(style.voidBottom.copy(alpha = 0.85f))
      .border(0.7.dp, accent.copy(alpha = 0.42f))
      .clickable(interactionSource = interaction, indication = null, onClick = onClick)
      .drawBehind {
        drawCenteredDecrypt(
          text = label,
          progress = decryptProgress(),
          seed = label.hashCode(),
          paint = paint,
          settledColor = accent,
          scramblingColor = accent.copy(alpha = 0.72f),
        )
      },
  )
}

@Composable
private fun HudKeycap(
  label: String,
  accent: Color,
  onPress: () -> Unit,
  modifier: Modifier = Modifier,
  isSubtle: Boolean = false,
  showPopup: Boolean = true,
  decryptProgress: () -> Float = { 1f },
) {
  val style = LocalLauncherStyle.current
  val latestPress by rememberUpdatedState(onPress)
  var pressed by remember { mutableStateOf(false) }
  val paint = rememberKeyPaint(
    sizeSp = if (isSubtle) 10f else 15.5f,
    typeface = style.telemetryTypeface,
    fontScale = style.fontScale,
    bold = false,
    letterSpacingEm = if (isSubtle) 0.14f else 0.03f,
  )
  val settledColor = if (isSubtle) TextSilver.copy(alpha = 0.65f) else TextPureWhite
  val scramblingColor = accent.copy(alpha = 0.72f)

  Box(
    modifier = modifier
      .height(44.dp)
      .background(
        if (pressed) accent.copy(alpha = 0.22f) else style.voidBottom.copy(alpha = 0.90f),
      )
      .border(
        width = if (pressed) 1.dp else 0.7.dp,
        color = if (pressed) accent.copy(alpha = 0.92f) else accent.copy(alpha = 0.24f),
      )
      .drawBehind {
        drawCenteredDecrypt(
          text = label,
          progress = decryptProgress(),
          seed = label.hashCode(),
          paint = paint,
          settledColor = settledColor,
          scramblingColor = scramblingColor,
        )
      }
      .pointerInput(label) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          down.consume()
          pressed = true
          latestPress()
          while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
          }
          pressed = false
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    if (pressed && showPopup) {
      Box(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .offset { IntOffset(0, -46.dp.roundToPx()) }
          .width(40.dp)
          .height(42.dp)
          .background(style.voidTop.copy(alpha = 0.98f))
          .border(1.dp, accent),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label,
          color = accent,
          fontFamily = style.fontFamily,
          fontSize = (18f * style.fontScale).sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

@Composable
private fun HudFunctionKey(
  label: String,
  accent: Color,
  highlighted: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  lockFlare: () -> Float = { 0f },
  decryptProgress: () -> Float = { 1f },
) {
  val style = LocalLauncherStyle.current
  val latestClick by rememberUpdatedState(onClick)
  var pressed by remember { mutableStateOf(false) }
  val paint = rememberKeyPaint(10.5f, style.telemetryTypeface, style.fontScale, bold = true, letterSpacingEm = 0.09f)
  val settledColor = if (highlighted) accent else TextPureWhite
  val scramblingColor = accent.copy(alpha = 0.72f)

  Box(
    modifier = modifier
      .height(44.dp)
      .drawBehind {
        val flare = lockFlare()
        val bgAlpha = when {
          pressed -> 0.30f
          highlighted -> 0.16f + flare * 0.20f
          else -> 0.88f
        }
        drawRect(if (pressed || highlighted) accent.copy(alpha = bgAlpha) else style.voidBottom.copy(alpha = bgAlpha))
        val borderColor = if (highlighted || pressed) {
          accent.copy(alpha = (0.85f + flare * 0.15f).coerceAtMost(1f))
        } else {
          TextSilver.copy(alpha = 0.30f)
        }
        drawRect(
          color = borderColor,
          style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = (if (highlighted || pressed) 1.dp else 0.7.dp).toPx(),
          ),
        )
        drawCenteredDecrypt(
          text = label,
          progress = decryptProgress(),
          seed = label.hashCode(),
          paint = paint,
          settledColor = settledColor,
          scramblingColor = scramblingColor,
        )
      }
      .pointerInput(label) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          down.consume()
          pressed = true
          latestClick()
          while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
          }
          pressed = false
        }
      },
  )
}

@Composable
private fun BackspaceKey(
  accent: Color,
  onTapDelete: () -> Unit,
  onSwipeClear: () -> Unit,
  scope: kotlinx.coroutines.CoroutineScope,
  modifier: Modifier = Modifier,
  decryptProgress: () -> Float = { 1f },
) {
  val style = LocalLauncherStyle.current
  val latestDelete by rememberUpdatedState(onTapDelete)
  val latestClear by rememberUpdatedState(onSwipeClear)
  var pressed by remember { mutableStateOf(false) }
  val paint = rememberKeyPaint(10.5f, style.telemetryTypeface, style.fontScale, bold = true, letterSpacingEm = 0.09f)

  Box(
    modifier = modifier
      .height(44.dp)
      .background(
        if (pressed) Crimson.copy(alpha = 0.24f) else style.voidBottom.copy(alpha = 0.88f),
      )
      .border(
        width = if (pressed) 1.dp else 0.7.dp,
        color = if (pressed) Crimson else TextSilver.copy(alpha = 0.30f),
      )
      .drawBehind {
        drawCenteredDecrypt(
          text = "DEL",
          progress = decryptProgress(),
          seed = "DEL".hashCode(),
          paint = paint,
          settledColor = if (pressed) TextPureWhite else TextSilver,
          scramblingColor = accent.copy(alpha = 0.72f),
        )
      }
      .pointerInput(Unit) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          down.consume()
          pressed = true
          latestDelete()
          var clearedBySwipe = false
          val repeatJob = scope.launch {
            delay(360L)
            while (!clearedBySwipe) {
              latestDelete()
              delay(55L)
            }
          }
          var totalDx = 0f
          try {
            while (true) {
              val event = awaitPointerEvent()
              val ch = event.changes.firstOrNull { it.id == down.id }
              if (ch == null || !ch.pressed) break
              totalDx += ch.position.x - ch.previousPosition.x
              if (!clearedBySwipe && totalDx < -38.dp.toPx()) {
                clearedBySwipe = true
                repeatJob.cancel()
                latestClear()
              }
            }
          } finally {
            repeatJob.cancel()
            pressed = false
          }
        }
      },
  )
}
