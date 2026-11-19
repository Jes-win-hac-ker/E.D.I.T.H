package com.nexwork.edith.ui.hud

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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
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

  val COMMAND_CHIPS = listOf("!jarvis", "!monet", "!ota", "?")

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
  val scope = rememberCoroutineScope()
  var symbolsLayer by remember { mutableStateOf(false) }

  val currentOnAppend by rememberUpdatedState(onAppend)
  val currentOnBackspace by rememberUpdatedState(onBackspace)
  val currentOnClear by rememberUpdatedState(onClearQuery)
  val currentOnSubmit by rememberUpdatedState(onSubmit)
  val currentOnHide by rememberUpdatedState(onHideKeyboard)

  AnimatedVisibility(
    visible = visible,
    enter = slideInVertically(
      animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
      initialOffsetY = { it },
    ) + fadeIn(animationSpec = tween(durationMillis = 140)),
    exit = slideOutVertically(
      animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
      targetOffsetY = { it },
    ) + fadeOut(animationSpec = tween(durationMillis = 120)),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(style.voidTop.copy(alpha = 0.94f))
        .drawBehind {
          // 1dp orbital hairline crowning the HUD keyboard
          drawLine(
            color = accent.copy(alpha = 0.55f),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
          )
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
          HudKeyboardLogic.COMMAND_CHIPS.forEach { chip ->
            StripChip(
              label = chip,
              accent = accent,
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
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSwitchToSystemIme()
          },
        )
        StripChip(
          label = "v",
          accent = accent,
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
        row1.forEach { ch ->
          HudKeycap(
            label = ch.uppercase(),
            accent = accent,
            onPress = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              currentOnAppend(ch)
            },
            modifier = Modifier.weight(1f),
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
        row2.forEach { ch ->
          HudKeycap(
            label = ch.uppercase(),
            accent = accent,
            onPress = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              currentOnAppend(ch)
            },
            modifier = Modifier.weight(1f),
          )
        }
      }

      // Row 3: 123/ABC + 7 keys + Backspace (tap=del, hold=repeat, swipe-left=clear)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        HudFunctionKey(
          label = if (symbolsLayer) "ABC" else "123",
          accent = accent,
          highlighted = symbolsLayer,
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            symbolsLayer = !symbolsLayer
          },
          modifier = Modifier.weight(1.45f),
        )
        row3.forEach { ch ->
          HudKeycap(
            label = ch.uppercase(),
            accent = accent,
            onPress = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              currentOnAppend(ch)
            },
            modifier = Modifier.weight(1f),
          )
        }
        BackspaceKey(
          accent = accent,
          onTapDelete = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnBackspace()
          },
          onSwipeClear = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnClear()
          },
          scope = scope,
          modifier = Modifier.weight(1.45f),
        )
      }

      // Row 4: !  ,  [ SPACE ]  .  SEARCH/EXEC/GO
      val actionLabel = HudKeyboardLogic.actionButtonLabel(query, matchCount)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        HudKeycap(
          label = "!",
          accent = accent,
          onPress = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnAppend("!")
          },
          modifier = Modifier.weight(1f),
        )
        HudKeycap(
          label = ",",
          accent = accent,
          onPress = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnAppend(",")
          },
          modifier = Modifier.weight(0.9f),
        )
        HudKeycap(
          label = "SPACE",
          accent = accent,
          isSubtle = true,
          showPopup = false,
          onPress = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnAppend(" ")
          },
          modifier = Modifier.weight(4.1f),
        )
        HudKeycap(
          label = ".",
          accent = accent,
          onPress = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnAppend(".")
          },
          modifier = Modifier.weight(0.9f),
        )
        HudFunctionKey(
          label = actionLabel,
          accent = accent,
          highlighted = true,
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnSubmit()
          },
          modifier = Modifier.weight(2.1f),
        )
      }
    }
  }
}

@Composable
private fun StripChip(
  label: String,
  accent: Color,
  onClick: () -> Unit,
) {
  val style = LocalLauncherStyle.current
  val interaction = remember { MutableInteractionSource() }
  Box(
    modifier = Modifier
      .background(style.voidBottom.copy(alpha = 0.85f))
      .border(0.7.dp, accent.copy(alpha = 0.42f))
      .clickable(interactionSource = interaction, indication = null, onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      color = accent,
      fontFamily = style.telemetryFontFamily,
      fontSize = (9.5f * style.fontScale).sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 0.8.sp,
      maxLines = 1,
    )
  }
}

@Composable
private fun HudKeycap(
  label: String,
  accent: Color,
  onPress: () -> Unit,
  modifier: Modifier = Modifier,
  isSubtle: Boolean = false,
  showPopup: Boolean = true,
) {
  val style = LocalLauncherStyle.current
  val latestPress by rememberUpdatedState(onPress)
  var pressed by remember { mutableStateOf(false) }

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
    Text(
      text = label,
      color = if (isSubtle) TextSilver.copy(alpha = 0.65f) else TextPureWhite,
      fontFamily = style.fontFamily,
      fontSize = if (isSubtle) (10f * style.fontScale).sp else (15.5f * style.fontScale).sp,
      fontWeight = FontWeight.Medium,
      letterSpacing = if (isSubtle) 1.4.sp else 0.4.sp,
      maxLines = 1,
    )

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
) {
  val style = LocalLauncherStyle.current
  val latestClick by rememberUpdatedState(onClick)
  var pressed by remember { mutableStateOf(false) }

  Box(
    modifier = modifier
      .height(44.dp)
      .background(
        when {
          pressed -> accent.copy(alpha = 0.30f)
          highlighted -> accent.copy(alpha = 0.16f)
          else -> style.voidBottom.copy(alpha = 0.88f)
        },
      )
      .border(
        width = if (highlighted || pressed) 1.dp else 0.7.dp,
        color = if (highlighted || pressed) accent.copy(alpha = 0.85f) else TextSilver.copy(alpha = 0.30f),
      )
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
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      color = if (highlighted) accent else TextPureWhite,
      fontFamily = style.telemetryFontFamily,
      fontSize = (10.5f * style.fontScale).sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 1.sp,
      maxLines = 1,
    )
  }
}

@Composable
private fun BackspaceKey(
  accent: Color,
  onTapDelete: () -> Unit,
  onSwipeClear: () -> Unit,
  scope: kotlinx.coroutines.CoroutineScope,
  modifier: Modifier = Modifier,
) {
  val style = LocalLauncherStyle.current
  val latestDelete by rememberUpdatedState(onTapDelete)
  val latestClear by rememberUpdatedState(onSwipeClear)
  var pressed by remember { mutableStateOf(false) }

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
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "DEL",
      color = if (pressed) TextPureWhite else TextSilver,
      fontFamily = style.telemetryFontFamily,
      fontSize = (10.5f * style.fontScale).sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 0.9.sp,
    )
  }
}
