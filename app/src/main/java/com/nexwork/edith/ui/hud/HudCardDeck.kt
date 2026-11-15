package com.nexwork.edith.ui.hud

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.model.AppFootprint
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.LocalCrimsonMonetActive
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
  onOpenAppInfo: (AppEntry) -> Unit = {},
  onUninstallApp: (AppEntry) -> Unit = {},
  onActionArmedChange: (Boolean) -> Unit = {},
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
  val focusPos = remember { mutableFloatStateOf(0f) }
  var snapJob by remember { mutableStateOf<Job?>(null) }
  val chargeAnim = remember { Animatable(0f) }
  var mounted by remember { mutableStateOf(false) }
  val footprints = remember { mutableStateMapOf<String, AppFootprint?>() }
  var spot by remember { mutableIntStateOf(0) }

  val trayWidthDp = 122.dp
  val trayWidthPx = remember(density) { with(density) { trayWidthDp.toPx() } }
  val swipeAnim = remember { Animatable(0f) }
  val swipePos = remember { mutableFloatStateOf(0f) }
  var swipePkg by remember { mutableStateOf<String?>(null) }
  var armedPkg by remember { mutableStateOf<String?>(null) }
  var swipeJob by remember { mutableStateOf<Job?>(null) }

  // Which level the currently dealt cards belong to.
  var dealtLevel by remember { mutableStateOf<String?>(null) }

  val settleFwd = remember(density) { with(density) { 18.dp.toPx() } }
  val settleBack = remember(density) { with(density) { 30.dp.toPx() } }

  fun animateFocusTo(target: Float) {
    snapJob?.cancel()
    snapJob = scope.launch {
      focusAnim.snapTo(focusPos.floatValue)
      focusAnim.animateTo(target, tween(180)) {
        focusPos.floatValue = value
      }
    }
  }

  fun animateSwipeTo(target: Float, nextArmed: String?) {
    swipeJob?.cancel()
    swipeJob = scope.launch {
      swipeAnim.snapTo(swipePos.floatValue)
      swipeAnim.animateTo(target, tween(170)) {
        swipePos.floatValue = value
      }
      if (target <= 0.001f) {
        swipePkg = null
      }
      armedPkg = nextArmed
    }
  }

  fun disarmSwipeImmediate() {
    swipeJob?.cancel()
    swipePos.floatValue = 0f
    swipePkg = null
    armedPkg = null
  }

  val actionActive = armedPkg != null || swipePkg != null
  LaunchedEffect(actionActive) {
    onActionArmedChange(actionActive)
  }

  BackHandler(enabled = armedPkg != null) {
    animateSwipeTo(0f, nextArmed = null)
  }

  LaunchedEffect(pool, browse, browseGroup, activeGroup) {
    if (browse && !typing && browseGroup != null && activeGroup == null) {
      disarmSwipeImmediate()
      onExitGroup()
      return@LaunchedEffect
    }
    val maxIdx = (pool.size - 1).coerceAtLeast(0)
    if (spot > maxIdx) {
      spot = maxIdx
      focusPos.floatValue = focusPos.floatValue.coerceIn(0f, maxIdx.toFloat())
    }
    if (armedPkg != null && pool.none { (it as? DeckCard.App)?.entry?.packageName == armedPkg }) {
      disarmSwipeImmediate()
    }
  }

  LaunchedEffect(open, activeGroup?.name, browse) {
    disarmSwipeImmediate()
    if (open) {
      val levelKey = activeGroup?.name
      val redeal = levelKey != dealtLevel
      dealtLevel = levelKey
      spot = 0
      snapJob?.cancel()
      focusPos.floatValue = 0f
      focusAnim.snapTo(0f)
      mounted = true
      if (redeal) {
        revealAnim.snapTo(0f)
        revealAnim.animateTo(1f, tween(650, easing = LinearEasing))
      } else if (revealAnim.value < 0.995f) {
        val showMs = ((1f - revealAnim.value).coerceIn(0f, 1f) * 650f).roundToInt().coerceAtLeast(1)
        revealAnim.animateTo(1f, tween(showMs, easing = LinearEasing))
      }
    } else {
      chargeAnim.snapTo(0f)
      val hideMs = (revealAnim.value.coerceIn(0f, 1f) * 500f).roundToInt().coerceAtLeast(1)
      revealAnim.animateTo(0f, tween(hideMs, easing = LinearEasing))
      mounted = false
      dealtLevel = null
    }
  }

  LaunchedEffect(open, browse, pool, swipePkg, armedPkg) {
    if (open && !browse && pool.size == 1 && swipePkg == null && armedPkg == null) {
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
  }

  // Storage reads land on demand in parallel for the visible window around spot.
  // Using SnapshotStateMap avoids recomposing unaffected deck slots when one footprint resolves.
  LaunchedEffect(spot, pool) {
    val window = pool.drop((spot - 1).coerceAtLeast(0)).take(6).filterIsInstance<DeckCard.App>().map { it.entry }
    val missing = window.filter { it.packageName !in footprints }
    if (missing.isNotEmpty()) {
      val resolved = coroutineScope {
        missing.map { entry -> async { entry.packageName to footprintOf(entry) } }.awaitAll()
      }
      resolved.forEach { (pkg, fp) -> footprints[pkg] = fp }
    }
  }

  if (!mounted || pool.isEmpty()) return

  val steps = pool.size
  val last = steps - 1
  val exiting = !open

  val livePool by rememberUpdatedState(pool)
  val liveSpot by rememberUpdatedState(spot)
  val liveGrouped by rememberUpdatedState(activeGroup != null)
  val liveSwipePkg by rememberUpdatedState(swipePkg)
  val liveArmedPkg by rememberUpdatedState(armedPkg)

  BoxWithConstraints(
    modifier
      .fillMaxWidth()
      .fillMaxHeight(),
  ) {
    val cardPx = with(density) { (maxWidth * 124f / 312f).toPx() }
    val followers = (pool.size - 1 - spot).coerceIn(0, 4)
    val stackBottom = cardPx + with(density) { (followers * 18).dp.toPx() + 6.dp.toPx() }

    // Render only the visible 5-card fan plus 1 pre-warmed card below; focusedState tracks integer
    // spot boundaries so continuous drag/snap moves GPU layers without invalidating card DrawScopes.
    pool.forEachIndexed { slot, card ->
      val slotDist = slot - spot
      if (slotDist < -1 || slotDist > 5) return@forEachIndexed

      val cardKey = when (card) {
        is DeckCard.App -> "a:${card.entry.packageName}"
        is DeckCard.Group -> "g:${card.group.name}"
      }
      key(cardKey) {
        val cardReveal = remember(slot, steps, exiting) {
          val step = if (exiting) last - slot else slot
          { staggeredReveal(revealAnim.value, step, steps, Stagger) }
        }
        val cardCharge = remember(slot) { { if (slot == 0) chargeAnim.value else 0f } }
        val focusedState = remember(slot) { derivedStateOf { slot <= spot } }
        val cardFocused = remember(focusedState) { { focusedState.value } }
        val isTrayOwner = (card as? DeckCard.App)?.entry?.packageName?.let {
          it == swipePkg || it == armedPkg
        } == true

        Box(
          Modifier
            .fillMaxWidth()
            .aspectRatio(312f / 124f)
            .zIndex(-slot.toFloat())
            .graphicsLayer {
              val depth = slot - focusPos.floatValue
              val progress = cardReveal()
              if (progress <= 0.001f || depth < -1.2f || depth > 5.0f) {
                alpha = 0f
              } else {
                alpha = when {
                  depth <= 0f -> (1f + depth).coerceIn(0f, 1f)
                  depth <= 4f -> (1f - 0.17f * depth).coerceAtLeast(0.34f)
                  else -> (0.34f * (5f - depth)).coerceIn(0f, 0.34f)
                }
                translationY = if (depth > 0f) depth * settleFwd else depth * settleBack
                val scale = (if (depth > 0f) 1f - 0.06f * depth else 1f + 0.08f * -depth) *
                  (0.985f + 0.015f * progress)
                scaleX = scale
                scaleY = scale
              }
            }
            .clipToBounds(),
        ) {
          if (isTrayOwner) {
            CardSwipeActionTray(
              isSystemApp = card.entry.isSystemApp,
              revealFraction = { swipePos.floatValue },
              modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(trayWidthDp)
                .fillMaxHeight(),
            )
          }

          Box(
            Modifier
              .fillMaxSize()
              .graphicsLayer {
                if (isTrayOwner) {
                  translationX = -swipePos.floatValue * trayWidthPx
                }
              },
          ) {
            when (card) {
              is DeckCard.App -> HudAppCard(
                app = card.entry,
                usageFraction = card.usageMillis.toFloat() / peak,
                footprint = footprints[card.entry.packageName],
                reveal = cardReveal,
                charge = cardCharge,
                focused = cardFocused,
              )
              is DeckCard.Group -> HudGroupCard(
                group = card.group,
                usageFraction = card.usageMillis.toFloat() / peak,
                reveal = cardReveal,
                focused = cardFocused,
              )
            }
          }
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

          fun findHitSlot(y: Float): Int? {
            val currentFocus = focusPos.floatValue
            return livePool.indices
              .map { s -> s to (s - currentFocus) }
              .filter { (_, depth) ->
                val alpha = if (depth > 0f) (1f - 0.17f * depth).coerceAtLeast(0.34f)
                else (1f + depth).coerceIn(0f, 1f)
                alpha > 0.05f
              }
              .firstOrNull { (_, depth) ->
                val top = if (depth > 0f) depth * settleFwd else depth * settleBack
                y >= top && y <= top + cardPx
              }
              ?.first
          }

          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            snapJob?.cancel()
            val startX = down.position.x
            val startY = down.position.y
            var lastY = startY
            var axis = DeckGestureAxis.UNDECIDED
            var totalDragX = 0f
            var totalDragY = 0f
            var downwardPullAtHead = 0f

            val downSlot = findHitSlot(startY)
            val downApp = (downSlot?.let { livePool.getOrNull(it) } as? DeckCard.App)?.entry
            val curArmed = liveArmedPkg
            var swipeStartFrac = 0f
            var crossedThresholdHaptic = false

            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
              val dx = change.position.x - change.previousPosition.x
              val dy = change.position.y - lastY
              lastY = change.position.y
              totalDragX += dx
              totalDragY += dy

              if (axis == DeckGestureAxis.UNDECIDED && hypot(totalDragX, totalDragY) > slop) {
                val canSwipeHorizontal = downApp != null &&
                  downSlot == liveSpot &&
                  abs(totalDragX) > abs(totalDragY) * 1.35f &&
                  (totalDragX < 0f || curArmed == downApp.packageName)

                if (canSwipeHorizontal) {
                  axis = DeckGestureAxis.HORIZONTAL
                  swipeJob?.cancel()
                  val pkg = downApp.packageName
                  swipePkg = pkg
                  swipeStartFrac = if (curArmed == pkg) swipePos.floatValue else 0f
                  if (curArmed != null && curArmed != pkg) {
                    armedPkg = null
                  }
                  crossedThresholdHaptic = shouldArmCardSwipe(swipeStartFrac)
                } else {
                  axis = DeckGestureAxis.VERTICAL
                  if (liveArmedPkg != null || liveSwipePkg != null) {
                    animateSwipeTo(0f, nextArmed = null)
                  }
                }
              }

              when (axis) {
                DeckGestureAxis.HORIZONTAL -> {
                  change.consume()
                  val nextFrac = (swipeStartFrac - totalDragX / trayWidthPx).coerceIn(0f, 1.06f)
                  swipePos.floatValue = nextFrac
                  val armedNow = shouldArmCardSwipe(nextFrac)
                  if (!crossedThresholdHaptic && armedNow) {
                    crossedThresholdHaptic = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  } else if (crossedThresholdHaptic && nextFrac < CARD_SWIPE_ARM_FRACTION - 0.04f) {
                    crossedThresholdHaptic = false
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  }
                }
                DeckGestureAxis.VERTICAL -> {
                  change.consume()
                  if (focusPos.floatValue <= 0.15f && dy > 0f) {
                    downwardPullAtHead += dy
                  }

                  val delta = dy / pitch
                  val floor = if (liveGrouped) -0.85f else -0.55f
                  val ceiling = maxIndex.toFloat() + 0.25f
                  val next = (focusPos.floatValue - delta).coerceIn(floor, ceiling)

                  focusPos.floatValue = next
                  val newSpot = next.roundToInt().coerceIn(0, maxIndex)
                  if (newSpot != spot) {
                    spot = newSpot
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  }
                }
                DeckGestureAxis.UNDECIDED -> Unit
              }
            }

            when (axis) {
              DeckGestureAxis.HORIZONTAL -> {
                if (shouldArmCardSwipe(swipePos.floatValue)) {
                  if (!crossedThresholdHaptic) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  }
                  animateSwipeTo(1f, nextArmed = liveSwipePkg)
                } else {
                  animateSwipeTo(0f, nextArmed = null)
                }
              }
              DeckGestureAxis.VERTICAL -> {
                if (spot == 0 && (focusPos.floatValue < -0.32f || downwardPullAtHead > 48.dp.toPx())) {
                  haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  if (liveGrouped) {
                    onExitGroup()
                  } else {
                    onDismiss()
                  }
                } else {
                  val target = focusPos.floatValue.roundToInt().coerceIn(0, maxIndex)
                  spot = target
                  animateFocusTo(target.toFloat())
                }
              }
              DeckGestureAxis.UNDECIDED -> {
                val openPkg = liveArmedPkg
                if (openPkg != null) {
                  val armedSlot = livePool.indexOfFirst { (it as? DeckCard.App)?.entry?.packageName == openPkg }
                  val armedCard = armedSlot.takeIf { it >= 0 }?.let { livePool[it] as? DeckCard.App }?.entry
                  val depth = armedSlot - focusPos.floatValue
                  val top = if (depth > 0f) depth * settleFwd else depth * settleBack
                  down.consume()
                  if (armedCard != null && startY in top..(top + cardPx)) {
                    when (
                      cardTrayHitAction(
                        x = startX,
                        width = size.width.toFloat(),
                        localY = startY - top,
                        cardHeight = cardPx,
                        trayWidth = trayWidthPx,
                        isSystemApp = armedCard.isSystemApp,
                      )
                    ) {
                      CardTrayAction.APP_INFO -> {
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        animateSwipeTo(0f, nextArmed = null)
                        onOpenAppInfo(armedCard)
                      }
                      CardTrayAction.UNINSTALL -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        animateSwipeTo(0f, nextArmed = null)
                        onUninstallApp(armedCard)
                      }
                      CardTrayAction.DISMISS -> {
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        animateSwipeTo(0f, nextArmed = null)
                      }
                    }
                  } else {
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    animateSwipeTo(0f, nextArmed = null)
                  }
                } else {
                  val hit = findHitSlot(startY)
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
                      animateFocusTo(hit.toFloat())
                    }
                  } else if (startY > stackBottom + 12.dp.toPx()) {
                    down.consume()
                    if (liveGrouped) onExitGroup() else onDismiss()
                  }
                }
              }
            }
          }
        },
    )
  }
}

@Composable
private fun CardSwipeActionTray(
  isSystemApp: Boolean,
  revealFraction: () -> Float,
  modifier: Modifier = Modifier,
) {
  val trayPlateBg = if (LocalCrimsonMonetActive.current) Color(0xFF0A0A0A) else Color(0xFF0F141E)
  Column(
    modifier = modifier
      .graphicsLayer {
        alpha = phase(revealFraction(), 0.06f, 0.45f)
      }
      .background(Color(0xE8050505))
      .padding(start = 6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (isSystemApp) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .background(trayPlateBg)
          .border(0.8.dp, Color.White.copy(alpha = 0.28f))
          .drawBehind { drawTrayBrackets(Color.White.copy(alpha = 0.7f)) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = "SYSTEM APP",
          color = TextSilver.copy(alpha = 0.72f),
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          letterSpacing = 1.sp,
          textAlign = TextAlign.Center,
        )
        Text(
          text = "APP INFO",
          color = TextPureWhite,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 1.2.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    } else {
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .background(trayPlateBg)
          .border(0.8.dp, Color.White.copy(alpha = 0.28f))
          .drawBehind { drawTrayBrackets(Color.White.copy(alpha = 0.65f)) },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "APP INFO",
          color = TextPureWhite,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 1.2.sp,
          textAlign = TextAlign.Center,
        )
      }
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .background(Color(0xFF19060B))
          .border(1.dp, Crimson.copy(alpha = 0.85f))
          .drawBehind { drawTrayBrackets(Crimson) },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "✕ UNINSTALL",
          color = CrimsonBright,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrayBrackets(color: Color) {
  val b = 5.dp.toPx()
  val w = size.width
  val h = size.height
  val stroke = 1.3.dp.toPx()
  drawLine(color, Offset(w - b, 0f), Offset(w, 0f), stroke)
  drawLine(color, Offset(w, 0f), Offset(w, b), stroke)
  drawLine(color, Offset(w - b, h), Offset(w, h), stroke)
  drawLine(color, Offset(w, h - b), Offset(w, h), stroke)
}

internal enum class CardTrayAction {
  DISMISS,
  APP_INFO,
  UNINSTALL,
}

internal enum class DeckGestureAxis {
  UNDECIDED,
  VERTICAL,
  HORIZONTAL,
}

internal const val CARD_SWIPE_ARM_FRACTION = 0.48f

internal fun cardTrayHitAction(
  x: Float,
  width: Float,
  localY: Float,
  cardHeight: Float,
  trayWidth: Float,
  isSystemApp: Boolean,
): CardTrayAction {
  if (width <= 0f || cardHeight <= 0f || trayWidth <= 0f) return CardTrayAction.DISMISS
  if (x < width - trayWidth || localY < 0f || localY > cardHeight) return CardTrayAction.DISMISS
  return if (isSystemApp || localY < cardHeight * 0.5f) {
    CardTrayAction.APP_INFO
  } else {
    CardTrayAction.UNINSTALL
  }
}

internal fun shouldArmCardSwipe(fraction: Float): Boolean = fraction >= CARD_SWIPE_ARM_FRACTION
