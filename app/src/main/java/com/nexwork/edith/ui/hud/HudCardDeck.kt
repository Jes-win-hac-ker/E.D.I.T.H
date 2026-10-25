package com.nexwork.edith.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.model.AppFootprint
import com.nexwork.edith.ui.components.filterMatchingApps
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val Stagger = 0.13f

/**
 * Search results as a cascading deck. One master sweep drives everything, and it is read only
 * from draw lambdas — dealing and un-dealing re-run no composition. Cards step in staggered,
 * each tracing its own keyline as it lands, and retract in reverse order on dismiss. The
 * spotlighted card sits flat and sharp; the ones behind it step down, shrink and dim, and
 * because every plate is smoked they read through the leader as a stack rather than a wall.
 * Storage details are fetched once, after the deck has landed.
 *
 * The gesture column is deliberately only the negative space *below* the stack: dragging there
 * moves the spotlight and tapping it launches whichever card leads. It claims its presses ahead
 * of the home screen, so an upward drag shuffles instead of dismissing the search. Presses on the
 * cards are attributed geometrically, by one resolver on top of the stack — the five plates
 * overlap, so a per-card handler would race and an arbitrary one would win. The leader launches
 * on touch-down, which is the earliest frame a press can be known; a sliver promotes instead.
 * Either launch path dismisses the deck, which clears the query.
 *
 * A single hit auto-launches from here: once the leader has finished initialising it charges
 * left to right over 1500ms and fires the instant the head reaches the right keyline.
 * Any query change cancels the effect mid-charge and re-arms it from empty.
 */
@Composable
fun HudCardDeck(
  isOpen: Boolean,
  searchQuery: String,
  onDismiss: () -> Unit,
  apps: List<AppEntry>,
  onLaunchApp: (AppEntry) -> Unit,
  modifier: Modifier = Modifier,
  footprintOf: suspend (AppEntry) -> AppFootprint? = { null },
) {
  val matching = remember(searchQuery, apps) { filterMatchingApps(searchQuery, apps) }
  val open = isOpen && searchQuery.trim().isNotEmpty() && matching.isNotEmpty()
  val scope = rememberCoroutineScope()
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current
  val revealAnim = remember { Animatable(0f) }
  val focusAnim = remember { Animatable(0f) }
  val chargeAnim = remember { Animatable(0f) }
  var slots by remember { mutableStateOf(listOf<AppEntry>()) }
  var mounted by remember { mutableStateOf(false) }
  var footprints by remember { mutableStateOf(mapOf<String, AppFootprint?>()) }
  var spot by remember { mutableIntStateOf(0) }

  val settleFwd = remember(density) { with(density) { 18.dp.toPx() } }
  val settleBack = remember(density) { with(density) { 30.dp.toPx() } }

  LaunchedEffect(open, matching) {
    if (open) {
      slots = matching.take(5)
      mounted = true
      if (revealAnim.value < 0.995f) {
        revealAnim.animateTo(1f, tween(1000, easing = LinearEasing))
      }
      footprints = slots.associate { it.packageName to footprintOf(it) }
      if (matching.size == 1) {
        val app = matching.first()
        chargeAnim.snapTo(0f)
        chargeAnim.animateTo(1f, tween(1500, easing = LinearEasing))
        onLaunchApp(app)
        onDismiss()
      } else {
        chargeAnim.snapTo(0f)
      }
    } else {
      chargeAnim.snapTo(0f)
      revealAnim.animateTo(0f, tween(1000, easing = LinearEasing))
      mounted = false
      slots = emptyList()
    }
  }
  LaunchedEffect(searchQuery) {
    focusAnim.snapTo(0f)
    spot = 0
  }

  if (!mounted || slots.isEmpty()) return

  val steps = slots.size
  val last = steps - 1
  val peak = slots.maxOfOrNull { it.usageTimeMillis14d }?.coerceAtLeast(1L) ?: 1L
  val exiting = !open

  // Read through these so a tap can launch whoever leads right now without the gesture
  // detectors being rebuilt mid-drag every time the spotlight moves.
  val liveSlots by rememberUpdatedState(slots)
  val liveSpot by rememberUpdatedState(spot)

  BoxWithConstraints(
    modifier
      .fillMaxWidth()
      .fillMaxHeight(),
  ) {
    // The stack's own footprint: leader plate + the stepped slivers under it, plus a hair.
    val stackBottom = maxWidth * 124f / 312f + (last * 18).dp + 6.dp
    val cardPx = with(density) { (maxWidth * 124f / 312f).toPx() }

    // The shuffle column: everything under the stack. It owns the press outright — the down is
    // consumed here, before the home screen's dismiss drag can see it, so a vertical drag moves
    // the spotlight instead of closing the search, and a tap launches whichever plate leads.
    Box(
      Modifier
        .fillMaxWidth()
        .padding(top = stackBottom)
        .fillMaxHeight()
        .pointerInput(steps) {
          val pitch = 40.dp.toPx()
          val slop = viewConfiguration.touchSlop
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            var dragged = false
            var travel = 0f
            var lastY = down.position.y
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
              val dy = change.position.y - lastY
              lastY = change.position.y
              travel += dy
              if (!dragged && abs(travel) > slop) dragged = true
              if (dragged) {
                change.consume()
                val next = (focusAnim.value - dy / pitch).coerceIn(0f, last.toFloat())
                scope.launch { focusAnim.snapTo(next) }
                val landed = next.roundToInt()
                if (landed != spot) {
                  spot = landed
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
              }
            }
            if (dragged) {
              val target = focusAnim.value.roundToInt().coerceIn(0, last).toFloat()
              scope.launch { focusAnim.animateTo(target, tween(160)) }
            } else {
              liveSlots.getOrNull(liveSpot)?.let { app ->
                onLaunchApp(app)
                onDismiss()
              }
            }
          }
        },
    ) {}

    slots.forEachIndexed { slot, app ->
      val step = if (exiting) last - slot else slot
      val cardReveal = { staggeredReveal(revealAnim.value, step, steps, Stagger) }
      Box(
        Modifier
          .fillMaxWidth()
          .zIndex(-slot.toFloat())
          .graphicsLayer {
            val progress = cardReveal()
            if (progress <= 0.001f) {
              alpha = 0f
            } else {
              val depth = slot - focusAnim.value
              val absDepth = kotlin.math.abs(depth)
              alpha = if (depth > 0f) {
                (1f - 0.17f * depth).coerceAtLeast(0.34f)
              } else {
                (1f + depth).coerceIn(0f, 1f)
              }
              translationY = if (depth > 0f) depth * settleFwd else depth * settleBack
              val scale = (if (depth > 0f) 1f - 0.06f * depth else 1f + 0.08f * -depth) *
                (0.985f + 0.015f * progress)
              scaleX = scale
              scaleY = scale

              if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Enough defocus to read as a different focal plane, not enough to smear the
                // keyline and label into a milky bar. Cleared explicitly: a stale effect
                // survives the card sliding back into the leader slot.
                val blurPx = (absDepth * 2.2f).coerceAtMost(6f)
                renderEffect = if (blurPx > 0.4f) {
                  android.graphics.RenderEffect.createBlurEffect(
                    blurPx,
                    blurPx,
                    android.graphics.Shader.TileMode.CLAMP
                  ).asComposeRenderEffect()
                } else {
                  null
                }
              }
            }
          },
      ) {
        HudAppCard(
          app = app,
          usageFraction = app.usageTimeMillis14d.toFloat() / peak,
          footprint = footprints[app.packageName],
          reveal = cardReveal,
          charge = { if (slot == 0) chargeAnim.value else 0f },
          focused = { focusAnim.value - slot > -0.5f },
        )
      }
    }

    // One press, resolved once, against the same offsets the plates are drawn at. It covers the
    // stack and nothing else: Compose stops hit-testing at the first node that claims the point,
    // so stretching this to the full deck would swallow the shuffle column underneath.
    Box(
      Modifier
        .fillMaxWidth()
        .height(stackBottom)
        .pointerInput(steps) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val focus = focusAnim.value
            val y = down.position.y
            val hit = liveSlots.indices
              .map { s -> s to (s - focus) }
              // A plate only exists to the finger while it is drawn: cards that have crossed
              // behind the spotlight fade to nothing and must not eat the press.
              .filter { (_, depth) ->
                val alpha = if (depth > 0f) (1f - 0.17f * depth).coerceAtLeast(0.34f)
                else (1f + depth).coerceIn(0f, 1f)
                alpha > 0.02f
              }
              .firstOrNull { (_, depth) ->
                val top = if (depth > 0f) depth * settleFwd else depth * settleBack
                y >= top && y <= top + cardPx
              }
              ?.first
              ?: return@awaitEachGesture
            down.consume()
            haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            if (hit == liveSpot) {
              liveSlots.getOrNull(hit)?.let { app ->
                onLaunchApp(app)
                onDismiss()
              }
            } else {
              spot = hit
              scope.launch { focusAnim.animateTo(hit.toFloat(), tween(160)) }
            }
          }
        },
    ) {}
  }
}
