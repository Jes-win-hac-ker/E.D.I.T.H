package com.nexwork.edith.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlin.math.roundToInt
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
import androidx.compose.runtime.DisposableEffect
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
import com.nexwork.edith.theme.EDITHTheme
import com.nexwork.edith.ui.hud.CrimeSceneFocusOverlay
import com.nexwork.edith.ui.hud.HudCardDeck
import com.nexwork.edith.ui.hud.HudClock
import com.nexwork.edith.ui.hud.HudMonetCard
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
  var isCardActionArmed by remember { mutableStateOf(false) }
  var lastScreenActivityMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    isResumed = true
    lastScreenActivityMs = System.currentTimeMillis()
    viewModel.wakeFromAmbient()
    viewModel.refreshLauncherData()
    viewModel.checkForUpdates(force = false)
    viewModel.ensureMonetPersisted()
  }
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
    isResumed = false
  }
  DisposableEffect(context) {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        viewModel.ensureMonetPersisted()
      }
    }
    @Suppress("DEPRECATION")
    val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
    context.registerReceiver(receiver, filter)
    onDispose {
      runCatching { context.unregisterReceiver(receiver) }
    }
  }

  val consoleOpen = state.isSpotlightSearchOpen || state.isAppBrowseOpen
  val searchOpen = state.isSpotlightSearchOpen
  val isMonetCommand = state.searchQuery.trim().let { q ->
    q.startsWith("!monet", ignoreCase = true) ||
      q.startsWith("!theme", ignoreCase = true) ||
      q.equals("!reset", ignoreCase = true)
  }
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
    isCardActionArmed,
    lastScreenActivityMs,
  ) {
    if (!isResumed) return@LaunchedEffect
    if (consoleOpen) {
      if (isCardActionArmed || isMonetCommand) return@LaunchedEffect
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

  EDITHTheme(crimsonMonetActive = state.monetUiState.isEnabledPersisted) {
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
    val backdropProgress = remember { Animatable(0f) }
    LaunchedEffect(consoleOpen) {
      if (consoleOpen) {
        val showMs = ((1f - backdropProgress.value).coerceIn(0f, 1f) * 650f).roundToInt().coerceAtLeast(1)
        backdropProgress.animateTo(1f, tween(showMs, easing = FastOutSlowInEasing))
      } else {
        val hideMs = (backdropProgress.value.coerceIn(0f, 1f) * 500f).roundToInt().coerceAtLeast(1)
        backdropProgress.animateTo(0f, tween(hideMs, easing = FastOutSlowInEasing))
      }
    }
    val consoleBlurSteps = remember {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        List(12) { idx ->
          val radius = (idx + 1).toFloat()
          android.graphics.RenderEffect.createBlurEffect(
            radius,
            radius,
            android.graphics.Shader.TileMode.CLAMP,
          ).asComposeRenderEffect()
        }
      } else {
        emptyList()
      }
    }

    // Wireframe Earth backdrop with synchronized rack-focus dolly zoom and stepped blur
    Box(
      Modifier
        .fillMaxSize()
        .graphicsLayer {
          val p = backdropProgress.value
          val zoom = 1f + 0.08f * p
          scaleX = zoom
          scaleY = zoom
          val blurStep = (p * consoleBlurSteps.size).roundToInt()
          renderEffect = if (blurStep > 0 && consoleBlurSteps.isNotEmpty()) {
            consoleBlurSteps[(blurStep - 1).coerceIn(0, consoleBlurSteps.lastIndex)]
          } else {
            null
          }
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

    // The clock owns the top bay when awake on home, unwinding completely once idle or summoned.
    val topHudHidden = consoleOpen || state.isAmbientCentered
    HudClock(
      isOpen = topHudHidden,
      modifier = Modifier.align(Alignment.TopStart),
    )

    HudOtaPill(
      otaState = state.otaState,
      onClick = viewModel::onOtaPillClick,
      onDismiss = viewModel::dismissOtaPill,
      hidden = topHudHidden,
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(start = 18.dp, end = 18.dp, top = 196.dp),
    )

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

    if (consoleOpen && isMonetCommand) {
      HudMonetCard(
        monetUiState = state.monetUiState,
        onApply = {
          lastScreenActivityMs = System.currentTimeMillis()
          keyboardController?.hide()
          focusManager.clearFocus()
          viewModel.applyCrimsonMonet()
        },
        onReset = {
          lastScreenActivityMs = System.currentTimeMillis()
          keyboardController?.hide()
          focusManager.clearFocus()
          viewModel.resetMonetToDefault()
        },
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(top = 152.dp),
      )
    }

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
      isOpen = consoleOpen && !isMonetCommand,
      searchQuery = state.searchQuery,
      onDismiss = dismissConsole,
      apps = state.apps,
      onLaunchApp = viewModel::launchApp,
      browse = state.isAppBrowseOpen,
      browseGroup = state.browseGroup,
      onEnterGroup = viewModel::openAppGroup,
      onExitGroup = viewModel::closeAppGroup,
      footprintOf = viewModel::footprintOf,
      onOpenAppInfo = viewModel::openAppInfo,
      onUninstallApp = viewModel::requestUninstall,
      onActionArmedChange = { armed ->
        isCardActionArmed = armed
        if (armed) {
          keyboardController?.hide()
          focusManager.clearFocus()
        }
        lastScreenActivityMs = System.currentTimeMillis()
      },
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(start = 18.dp, end = 18.dp, top = 236.dp, bottom = 28.dp)
        .graphicsLayer {
          translationY = with(density) { (deckResting - 236.dp).toPx() } * deckShift.value
        },
    )
  }
  }
}
