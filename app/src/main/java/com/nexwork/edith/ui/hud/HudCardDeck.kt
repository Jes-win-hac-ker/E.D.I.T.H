package com.nexwork.edith.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val Stagger = 0.13f

/** One plate in the deck: either an app to launch or a category to open. */
private sealed interface DeckCard {
  val usageMillis: Long

  data class App(val entry: AppEntry) : DeckCard {
    override val usageMillis: Long get() = entry.usageTimeMillis14d
  }

  data class Group(val group: AppGroup) : DeckCard {
    override val usageMillis: Long get() = group.usageMillis
  }
}

/**
 * Search results as a cascading deck, and the browse index as a two-level version of it.
 * One master sweep drives everything, and it is read only from draw lambdas — dealing and
 * un-dealing re-run no composition. Cards step in staggered, each tracing its own keyline as
 * it lands, and retract in reverse order on dismiss. The spotlighted card sits flat and sharp;
 * the ones behind it step down, shrink and dim, and because every plate is smoked they read
 * through the leader as a stack rather than a wall.
 *
 * Browse mode opens on category plates — one per bucket in [groupApps], ranked by aggregate
 * 14-day usage. Tapping the leader opens that category's app deck; drawing down past the head
 * of a category (or Back, which the host routes here) returns to the categories. Typing a query
 * always searches the whole index flat, whichever level is underneath, so grouping never hides
 * an app from search.
 */
@Composable
fun HudCardDeck(
  isOpen: Boolean,
  searchQuery: String,
  onDismiss: () -> Unit,
  apps: List<AppEntry>,
  onLaunchApp: (AppEntry) -> Unit,
  modifier: Modifier = Modifier,
  browse: Boolean = false,
  browseGroup: String? = null,
  onEnterGroup: (String) -> Unit = {},
  onExitGroup: () -> Unit = {},
  footprintOf: suspend (AppEntry) -> AppFootprint? = { null },
) {
  val groups = remember(apps) { groupApps(apps) }
  val typing = searchQuery.trim().isNotEmpty()
  val activeGroup = remember(groups, browseGroup, browse, typing) {
    if (browse && !typing) groups.firstOrNull { it.name == browseGroup } else null
  }
  val pool: List<DeckCard> = remember(apps, groups, activeGroup, typing, searchQuery, browse) {
    when {
      !browse || typing -> filterMatchingApps(searchQuery, apps).map { DeckCard.App(it) }
      activeGroup != null -> activeGroup.apps.map { DeckCard.App(it) }
      else -> groups.map { DeckCard.Group(it) }
    }
  }
  val peak = remember(pool) { pool.maxOfOrNull { it.usageMillis }?.coerceAtLeast(1L) ?: 1L }
  val open = isOpen && pool.isNotEmpty() && (browse || typing)

  val scope = rememberCoroutineScope()
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current
  val revealAnim = remember { Animatable(0f) }
  val focusAnim = remember { Animatable(0f) }
  val chargeAnim = remember { Animatable(0f) }
  var mounted by remember { mutableStateOf(false) }
  var footprints by remember { mutableStateOf(mapOf<String, AppFootprint?>()) }
  var spot by remember { mutableIntStateOf(0) }

  // Which level the currently dealt cards belong to.
  var dealtLevel by remember { mutableStateOf<String?>(null) }

  val settleFwd = remember(density) { with(density) { 18.dp.toPx() } }
  val settleBack = remember(density) { with(density) { 30.dp.toPx() } }

  LaunchedEffect(open, activeGroup?.name, browse) {
    if (open) {
      val levelKey = activeGroup?.name
      val redeal = levelKey != dealtLevel
      dealtLevel = levelKey
      spot = 0
      focusAnim.snapTo(0f)
      mounted = true
      if (redeal) {
        revealAnim.snapTo(0f)
        revealAnim.animateTo(1f, tween(480, easing = LinearEasing))
      } else if (revealAnim.value < 0.995f) {
        revealAnim.animateTo(1f, tween(800, easing = LinearEasing))
      }
      if (!browse && pool.size == 1) {
        val only = pool.first() as? DeckCard.App
        if (only != null) {
          chargeAnim.snapTo(0f)
          chargeAnim.animateTo(1f, tween(1500, easing = LinearEasing))
          onLaunchApp(only.entry)
          onDismiss()
        }
      } else {
        chargeAnim.snapTo(0f)
      }
    } else {
      chargeAnim.snapTo(0f)
      revealAnim.animateTo(0f, tween(800, easing = LinearEasing))
      mounted = false
      dealtLevel = null
    }
  }

  // Storage reads land on demand for the visible window around spot
  LaunchedEffect(spot, pool) {
    val window = pool.drop((spot - 1).coerceAtLeast(0)).take(6).filterIsInstance<DeckCard.App>().map { it.entry }
    val missing = window.filter { it.packageName !in footprints }
    if (missing.isNotEmpty()) {
      footprints = footprints + missing.associate { it.packageName to footprintOf(it) }
    }
  }

  if (!mounted || pool.isEmpty()) return

  val steps = pool.size
  val last = steps - 1
  val exiting = !open

  val livePool by rememberUpdatedState(pool)
  val liveSpot by rememberUpdatedState(spot)
  val liveGrouped by rememberUpdatedState(activeGroup != null)

  BoxWithConstraints(
    modifier
      .fillMaxWidth()
      .fillMaxHeight(),
  ) {
    val cardPx = with(density) { (maxWidth * 124f / 312f).toPx() }
    val followers = (pool.size - 1 - spot).coerceIn(0, 4)
    val stackBottom = cardPx + with(density) { (followers * 18).dp.toPx() + 6.dp.toPx() }

    // Render cards from the persistent pool
    pool.forEachIndexed { slot, card ->
      val depth = slot - focusAnim.value
      // Virtualization: omit cards scrolled far past or buried under 6+ followers
      if (depth < -1.5f || depth > 6.5f) return@forEachIndexed

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
              val absDepth = abs(depth)
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
        when (card) {
          is DeckCard.App -> HudAppCard(
            app = card.entry,
            usageFraction = card.usageMillis.toFloat() / peak,
            footprint = footprints[card.entry.packageName],
            reveal = cardReveal,
            charge = { if (slot == 0) chargeAnim.value else 0f },
            focused = { focusAnim.value - slot > -0.5f },
          )
          is DeckCard.Group -> HudGroupCard(
            group = card.group,
            usageFraction = card.usageMillis.toFloat() / peak,
            reveal = cardReveal,
            focused = { focusAnim.value - slot > -0.5f },
          )
        }
      }
    }

    // Unified gesture layer across the entire deck surface
    val maxIndex = (pool.size - 1).coerceAtLeast(0)
    Box(
      Modifier
        .fillMaxSize()
        .pointerInput(pool.size, activeGroup?.name) {
          val pitch = 44.dp.toPx()
          val slop = viewConfiguration.touchSlop

          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startY = down.position.y
            var lastY = startY
            var dragged = false
            var totalDragY = 0f
            var downwardPullAtHead = 0f

            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
              val dy = change.position.y - lastY
              lastY = change.position.y
              totalDragY += dy

              if (!dragged && abs(totalDragY) > slop) {
                dragged = true
              }

              if (dragged) {
                change.consume()

                // If dragging downward at the top of the stack, accumulate pull for exiting
                if (focusAnim.value <= 0.15f && dy > 0f) {
                  downwardPullAtHead += dy
                }

                val delta = dy / pitch
                val floor = if (liveGrouped) -0.85f else -0.55f
                val ceiling = maxIndex.toFloat() + 0.25f
                val next = (focusAnim.value - delta).coerceIn(floor, ceiling)

                scope.launch { focusAnim.snapTo(next) }
                val newSpot = next.roundToInt().coerceIn(0, maxIndex)
                if (newSpot != spot) {
                  spot = newSpot
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
              }
            }

            if (dragged) {
              // Drag finished: check if pulled down past the head to climb out or dismiss
              if (spot == 0 && (focusAnim.value < -0.32f || downwardPullAtHead > 48.dp.toPx())) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (liveGrouped) {
                  onExitGroup()
                } else {
                  onDismiss()
                }
              } else {
                // Smooth snap to closest card
                val target = focusAnim.value.roundToInt().coerceIn(0, maxIndex)
                spot = target
                scope.launch {
                  focusAnim.animateTo(target.toFloat(), tween(180))
                }
              }
            } else {
              // Pure tap event: hit test active plates first
              val currentFocus = focusAnim.value
              val hit = livePool.indices
                .map { s -> s to (s - currentFocus) }
                .filter { (_, depth) ->
                  val alpha = if (depth > 0f) (1f - 0.17f * depth).coerceAtLeast(0.34f)
                  else (1f + depth).coerceIn(0f, 1f)
                  alpha > 0.05f
                }
                .firstOrNull { (_, depth) ->
                  val top = if (depth > 0f) depth * settleFwd else depth * settleBack
                  startY >= top && startY <= top + cardPx
                }
                ?.first

              if (hit != null) {
                down.consume()
                haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                if (hit == liveSpot) {
                  when (val card = livePool.getOrNull(hit)) {
                    is DeckCard.App -> {
                      onLaunchApp(card.entry)
                      onDismiss()
                    }
                    is DeckCard.Group -> onEnterGroup(card.group.name)
                    null -> Unit
                  }
                } else {
                  spot = hit
                  scope.launch { focusAnim.animateTo(hit.toFloat(), tween(180)) }
                }
              } else {
                // Tapped empty space below the cards: cleanly dismiss or exit
                if (startY > stackBottom + 12.dp.toPx()) {
                  down.consume()
                  if (liveGrouped) onExitGroup() else onDismiss()
                }
              }
            }
          }
        },
    )
  }
}
