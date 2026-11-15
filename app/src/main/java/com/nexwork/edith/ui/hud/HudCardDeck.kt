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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
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

  val rowPitchPx = remember(density) { with(density) { 78.dp.toPx() } }
  val curvePx = remember(density) { with(density) { 10.dp.toPx() } }
  val elbowDx = remember(density) { with(density) { 14.dp.toPx() } }

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

  val monetCrimson = LocalCrimsonMonetActive.current

  BoxWithConstraints(
    modifier
      .fillMaxWidth()
      .fillMaxHeight(),
  ) {
    val nodeWidthDp = (maxWidth * 0.65f).coerceIn(190.dp, 238.dp)
    val nodeWidthPx = with(density) { nodeWidthDp.toPx() }
    val nodeHeightPx = with(density) { RadialNodeHeight.toPx() }
    val visibleRows = (pool.size - spot).coerceIn(1, 4)
    val stackBottom = (visibleRows - 1) * rowPitchPx + nodeHeightPx + with(density) { 8.dp.toPx() }
    val leadColor = if (monetCrimson) Crimson else CrimsonBright

    // Radial elbow tethers drawn behind the node plates, pinned to the corner-parked globe.
    Box(
      Modifier
        .fillMaxSize()
        .drawBehind {
          val globeCx = size.width * 0.89f
          val globeCy = (nodeHeightPx * 3.1f).coerceIn(size.height * 0.48f, size.height * 0.72f)
          val pinRadius = size.width * 0.20f
          val focus = focusPos.floatValue
          val leaderColor = leadColor
          val trailColor = RadialNodeSlate

          pool.forEachIndexed { slot, card ->
            val depth = slot - focus
            if (depth < -0.6f || depth > 3.9f) return@forEachIndexed
            val step = if (exiting) last - slot else slot
            val progress = staggeredReveal(revealAnim.value, step, steps, Stagger)
            val nodeAlpha = radialNodeAlpha(depth) * progress
            if (nodeAlpha <= 0.03f) return@forEachIndexed

            val left = radialNodeLeftPx(depth, curvePx)
            val top = depth * rowPitchPx
            val cardPkg = (card as? DeckCard.App)?.entry?.packageName
            val swipeShift = if (cardPkg != null && (cardPkg == swipePkg || cardPkg == armedPkg)) {
              swipePos.floatValue * trayWidthPx
            } else {
              0f
            }
            val startX = (left + nodeWidthPx - swipeShift).coerceAtLeast(left + nodeHeightPx)
            val startY = top + nodeHeightPx * 0.5f
            val elbowX = startX + elbowDx

            val angleDeg = 215f - depth.coerceIn(-0.4f, 3.6f) * 19f
            val angleRad = angleDeg * PI / 180.0
            val pinX = globeCx + (cos(angleRad) * pinRadius).toFloat()
            val pinY = globeCy + (sin(angleRad) * pinRadius).toFloat()

            val isFocused = slot == spot
            val strokePx = (if (isFocused) 1.25.dp else 0.85.dp).toPx()
            val wireColor = (if (isFocused) leaderColor else trailColor)
              .copy(alpha = (if (isFocused) 0.92f else 0.52f) * nodeAlpha)

            val elbowPt = Offset(
              x = startX + (elbowX - startX) * progress,
              y = startY,
            )
            val endPt = Offset(
              x = elbowX + (pinX - elbowX) * progress,
              y = startY + (pinY - startY) * progress,
            )
            drawLine(wireColor, Offset(startX, startY), elbowPt, strokeWidth = strokePx)
            if (progress > 0.22f) {
              drawLine(wireColor, elbowPt, endPt, strokeWidth = strokePx)
              val dotR = (if (isFocused) 2.2.dp else 1.7.dp).toPx()
              drawCircle(wireColor, radius = dotR, center = endPt)
              if (isFocused) {
                drawCircle(
                  leaderColor.copy(alpha = 0.72f * nodeAlpha),
                  radius = 4.6.dp.toPx(),
                  center = endPt,
                  style = Stroke(width = 1.dp.toPx()),
                )
              }
            }
          }
        },
    )

    // Render only the visible 4-node radial arc plus 1 pre-warmed slot above/below; focusedState
    // tracks integer spot boundaries so continuous drag/snap moves GPU layers without recomposing.
    pool.forEachIndexed { slot, card ->
      val slotDist = slot - spot
      if (slotDist < -1 || slotDist > 4) return@forEachIndexed

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
        val focusedState = remember(slot) { derivedStateOf { slot == spot } }
        val cardFocused = remember(focusedState) { { focusedState.value } }
        val isTrayOwner = (card as? DeckCard.App)?.entry?.packageName?.let {
          it == swipePkg || it == armedPkg
        } == true

        Box(
          Modifier
            .width(nodeWidthDp)
            .height(RadialNodeHeight)
            .zIndex(if (slot == spot) 2f else -slot.toFloat())
            .graphicsLayer {
              val depth = slot - focusPos.floatValue
              val progress = cardReveal()
              val targetAlpha = radialNodeAlpha(depth)
              if (progress <= 0.001f || targetAlpha <= 0.001f) {
                alpha = 0f
              } else {
                alpha = targetAlpha * progress
                translationX = radialNodeLeftPx(depth, curvePx) +
                  if (slot == 0 || !exiting) 0f else (1f - progress) * -12f
                translationY = depth * rowPitchPx
                val scale = if (depth <= 0.05f && depth >= -0.35f) 1f else (1f - 0.025f * abs(depth)).coerceAtLeast(0.92f)
                scaleX = scale * (0.985f + 0.015f * progress)
                scaleY = scale * (0.985f + 0.015f * progress)
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
                slotIndex = slot,
              )
              is DeckCard.Group -> HudGroupCard(
                group = card.group,
                usageFraction = card.usageMillis.toFloat() / peak,
                reveal = cardReveal,
                focused = cardFocused,
                slotIndex = slot,
              )
            }
          }
        }
      }
    }

    // Unified gesture layer across the entire deck surface
    val maxIndex = (pool.size - 1).coerceAtLeast(0)
    val hitPadPx = with(density) { 8.dp.toPx() }
    Box(
      Modifier
        .fillMaxSize()
        .pointerInput(pool.size, activeGroup?.name, nodeWidthPx) {
          val pitch = rowPitchPx
          val slop = viewConfiguration.touchSlop

          fun findHitSlot(x: Float, y: Float): Int? {
            val currentFocus = focusPos.floatValue
            return livePool.indices
              .map { s -> s to (s - currentFocus) }
              .filter { (_, depth) -> radialNodeAlpha(depth) > 0.05f }
              .firstOrNull { (_, depth) ->
                val left = radialNodeLeftPx(depth, curvePx)
                val top = depth * rowPitchPx
                x in (left - hitPadPx)..(left + nodeWidthPx + hitPadPx) &&
                  y in top..(top + nodeHeightPx)
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

            val downSlot = findHitSlot(startX, startY)
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
                  val left = radialNodeLeftPx(depth, curvePx)
                  val top = depth * rowPitchPx
                  down.consume()
                  if (
                    armedCard != null &&
                    startX in left..(left + nodeWidthPx) &&
                    startY in top..(top + nodeHeightPx)
                  ) {
                    when (
                      cardTrayHitAction(
                        x = startX - left,
                        width = nodeWidthPx,
                        localY = startY - top,
                        cardHeight = nodeHeightPx,
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
                  val hit = findHitSlot(startX, startY)
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
                  } else if (startY > stackBottom + 12.dp.toPx() || startX > nodeWidthPx + curvePx + 18.dp.toPx()) {
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

internal fun radialNodeLeftPx(depth: Float, curvePx: Float): Float {
  val u = ((depth - 1.6f) / 1.8f).coerceIn(-1.2f, 1.2f)
  return ((u * u) * curvePx).coerceIn(0f, curvePx * 1.44f)
}

internal fun radialNodeAlpha(depth: Float): Float = when {
  depth < -0.7f || depth > 3.9f -> 0f
  depth < 0f -> (1f + depth / 0.7f).coerceIn(0f, 1f)
  depth <= 3f -> (1f - 0.14f * depth).coerceAtLeast(0.54f)
  else -> (0.54f * ((3.9f - depth) / 0.9f)).coerceIn(0f, 0.54f)
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
