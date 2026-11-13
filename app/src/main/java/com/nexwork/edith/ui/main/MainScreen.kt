package com.nexwork.edith.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.delay
import com.nexwork.edith.ui.hud.CrimeSceneFocusOverlay
import com.nexwork.edith.ui.hud.HudCardDeck
import com.nexwork.edith.ui.hud.HudClock
import com.nexwork.edith.ui.hud.HudOtaPill
import com.nexwork.edith.ui.hud.HudSearchBar
import com.nexwork.edith.ui.hud.GlobeController
import com.nexwork.edith.ui.hud.InteractiveWireframeGlobe
import com.nexwork.edith.ui.hud.groupApps

/**
 * Home screen — Stark HUD void with the flat search line.
 * Swipe down opens search; swipe up opens the same console over the full app deck, so browsing
 * and searching are one surface. Back, a tap outside, or an upward swipe dismisses it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit = {},
  viewModel: MainScreenViewModel = viewModel(),
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  LaunchedEffect(Unit) {
    viewModel.initContext(context)
  }

  val state by viewModel.launcherState.collectAsStateWithLifecycle()
  val haptic = LocalHapticFeedback.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  var isResumed by remember { mutableStateOf(true) }
  var lastScreenActivityMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    isResumed = true
    lastScreenActivityMs = System.currentTimeMillis()
    viewModel.wakeFromAmbient()
    viewModel.checkForUpdates(force = false)
  }
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
    isResumed = false
  }

  val consoleOpen = state.isSpotlightSearchOpen || state.isAppBrowseOpen
  val searchOpen = state.isSpotlightSearchOpen
  val dismissConsole: () -> Unit = {
    lastScreenActivityMs = System.currentTimeMillis()
    if (state.isSpotlightSearchOpen) viewModel.closeSpotlightSearch() else viewModel.closeAppBrowse()
  }
  val globeController = remember { GlobeController() }

  // Sync search and idle-ambient center state to 3D globe
  LaunchedEffect(consoleOpen) {
    globeController.setSearchActive(consoleOpen)
  }
  LaunchedEffect(state.isAmbientCentered) {
    globeController.setAmbientCentered(state.isAmbientCentered)
  }
  LaunchedEffect(state.isSpotlightSearchOpen, state.isAppBrowseOpen, state.searchQuery, state.browseGroup) {
    if (state.isSpotlightSearchOpen || state.isAppBrowseOpen) {
      lastScreenActivityMs = System.currentTimeMillis()
    }
  }
  LaunchedEffect(
    isResumed,
    consoleOpen,
    searchOpen,
    state.searchQuery,
    state.browseGroup,
    state.isAmbientCentered,
    lastScreenActivityMs,
  ) {
    if (!isResumed) return@LaunchedEffect
    if (consoleOpen) {
      val timeoutMs = if (searchOpen && state.searchQuery.isEmpty()) 4_000L else 10_000L
      val remainingMs = (timeoutMs - (System.currentTimeMillis() - lastScreenActivityMs)).coerceAtLeast(0L)
      delay(remainingMs)
      keyboardController?.hide()
      focusManager.clearFocus()
      viewModel.autoDeactivateConsole()
    } else if (!state.isAmbientCentered) {
      val remainingMs = (12_000L - (System.currentTimeMillis() - lastScreenActivityMs)).coerceAtLeast(0L)
      delay(remainingMs)
      viewModel.enterAmbientCenter()
    }
  }

  // Back climbs one level: a category back to the categories, then the console closed.
  BackHandler(enabled = consoleOpen) {
    lastScreenActivityMs = System.currentTimeMillis()
    viewModel.handleBackPress()
  }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val anyDown = event.changes.any { it.changedToDownIgnoreConsumed() }
            val anyPressed = event.changes.any { it.pressed }
            if (anyPressed) {
              viewModel.wakeFromAmbient()
              val now = System.currentTimeMillis()
              if (anyDown || now - lastScreenActivityMs >= 250L) {
                lastScreenActivityMs = now
              }
            }
          }
        }
      }
      .pointerInput(consoleOpen) {
        if (!consoleOpen) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startX = down.position.x
            val startY = down.position.y
            var totalDx = 0f
            var totalDy = 0f
            var isDrag = false
            var isPinch = false
            var prevSpan = 0f

            while (true) {
              val event = awaitPointerEvent()
              val activeChanges = event.changes.filter { it.pressed }
              if (activeChanges.isEmpty()) {
                if (!isDrag && !isPinch) {
                  val normX = (startX / size.width.toFloat()).coerceIn(0f, 1f)
                  val normY = (startY / size.height.toFloat()).coerceIn(0f, 1f)
                  globeController.tap(normX, normY)
                }
                break
              }

              // Multi-touch two-finger pinch-to-zoom
              if (activeChanges.size >= 2) {
                isPinch = true
                val p1 = activeChanges[0].position
                val p2 = activeChanges[1].position
                val span = kotlin.math.hypot(p1.x - p2.x, p1.y - p2.y)
                if (prevSpan > 0f) {
                  val factor = span / prevSpan
                  globeController.zoom(factor)
                }
                prevSpan = span
                activeChanges.forEach { it.consume() }
                continue
              }

              val change = activeChanges.firstOrNull { it.id == down.id } ?: activeChanges.first()
              val dx = change.position.x - change.previousPosition.x
              val dy = change.position.y - change.previousPosition.y
              totalDx += dx
              totalDy += dy

              val dragDistance = kotlin.math.hypot(totalDx, totalDy)
              if (dragDistance > 6.dp.toPx()) {
                isDrag = true
              }

              if (isDrag) {
                val screenWidth = size.width
                val isExcludedZone = startX < 40.dp.toPx() ||
                  startX > (screenWidth - 124.dp.toPx()) ||
                  startY < 40.dp.toPx()

                val thresholdPx = 110.dp.toPx()
                val isStrictlyVerticalDown = totalDy > 0f && totalDy > kotlin.math.abs(totalDx) * 1.8f
                val isStrictlyVerticalUp = totalDy < 0f && -totalDy > kotlin.math.abs(totalDx) * 1.8f

                if (!isExcludedZone && isStrictlyVerticalDown && totalDy > thresholdPx) {
                  change.consume()
                  viewModel.openSpotlightSearch()
                  break
                } else if (!isExcludedZone && isStrictlyVerticalUp && -totalDy > thresholdPx) {
                  change.consume()
                  haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  viewModel.openAppBrowse()
                  break
                } else {
                  change.consume()
                  globeController.rotate(dx, dy)
                }
              }
            }
          }
        }
      }
  ) {
    val browseGroupCount = remember(state.apps, state.browseGroup) {
      val name = state.browseGroup ?: return@remember 0
      groupApps(state.apps).firstOrNull { it.name == name }?.apps?.size ?: 0
    }
    val bgZoom = animateFloatAsState(
      targetValue = if (consoleOpen) 1.08f else 1.0f,
      animationSpec = tween(500),
      label = "bgZoom",
    )
    val consoleBlurEffect = remember {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        android.graphics.RenderEffect.createBlurEffect(
          12f,
          12f,
          android.graphics.Shader.TileMode.CLAMP,
        ).asComposeRenderEffect()
      } else {
        null
      }
    }

    // Wireframe Earth backdrop with rack focus dolly zoom and blur
    Box(
      Modifier
        .fillMaxSize()
        .graphicsLayer {
          val zoom = bgZoom.value
          scaleX = zoom
          scaleY = zoom
          renderEffect = if (consoleOpen) consoleBlurEffect else null
        }
        .background(Color.Black)
    ) {
      InteractiveWireframeGlobe(
        controller = globeController,
        modifier = Modifier.fillMaxSize(),
      )
    }

    // Crime scene intro camera lens depth overlay
    CrimeSceneFocusOverlay(
      isOpen = consoleOpen,
      modifier = Modifier.fillMaxSize(),
    )

    // The clock owns the top bay until the console is summoned into it, dimming softly while idle.
    HudClock(
      isOpen = consoleOpen,
      dimmed = state.isAmbientCentered,
      modifier = Modifier.align(Alignment.TopStart),
    )

    if (!consoleOpen) {
      val idleHudAlpha by animateFloatAsState(
        targetValue = if (state.isAmbientCentered) 0.40f else 1f,
        animationSpec = tween(550),
        label = "idleOtaAlpha",
      )
      HudOtaPill(
        otaState = state.otaState,
        onClick = viewModel::onOtaPillClick,
        onDismiss = viewModel::dismissOtaPill,
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(start = 18.dp, end = 18.dp, top = 196.dp)
          .graphicsLayer { alpha = idleHudAlpha },
      )
    }

    // Tap outside the console dismisses it when open
    if (consoleOpen) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectTapGestures { dismissConsole() }
          }
      )
    }

    HudSearchBar(
      isOpen = consoleOpen,
      searchQuery = state.searchQuery,
      onSearchQueryChange = viewModel::onSearchQueryChange,
      onDismiss = dismissConsole,
      apps = state.apps,
      onLaunchApp = viewModel::launchApp,
      autoFocus = searchOpen,
      idleDismiss = false,
      scopeLabel = state.browseGroup,
      scopeCount = browseGroupCount,
      onExitGroup = viewModel::closeAppGroup,
      modifier = Modifier.align(Alignment.TopStart),
    )

    // Results as a cascading deck; the leader is sharp, the rest defocus. Browse mode opens on
    // category plates and drills into one category's app deck; the strip's drag walks the window
    // past the five dealt cards, and a pull past the head of a category climbs back out.
    // Once the IME retracts the leader card parks with its centre at half screen height.
    val density = LocalDensity.current
    val cardHeight = (maxWidth - 36.dp) * 124f / 312f
    val deckResting = maxHeight * 0.5f - cardHeight / 2f
    val keyboardUp = consoleOpen && WindowInsets.isImeVisible
    val deckShift = animateFloatAsState(
      targetValue = if (keyboardUp) 0f else 1f,
      animationSpec = tween(260),
      label = "deckShift",
    )

    HudCardDeck(
      isOpen = consoleOpen,
      searchQuery = state.searchQuery,
      onDismiss = dismissConsole,
      apps = state.apps,
      onLaunchApp = viewModel::launchApp,
      browse = state.isAppBrowseOpen,
      browseGroup = state.browseGroup,
      onEnterGroup = viewModel::openAppGroup,
      onExitGroup = viewModel::closeAppGroup,
      footprintOf = viewModel::footprintOf,
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(start = 18.dp, end = 18.dp, top = 236.dp, bottom = 28.dp)
        .graphicsLayer {
          translationY = with(density) { (deckResting - 236.dp).toPx() } * deckShift.value
        },
    )
  }
}
