package com.nexwork.edith.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.nexwork.edith.ui.hud.CrimeSceneFocusOverlay
import com.nexwork.edith.ui.hud.HudCardDeck
import com.nexwork.edith.ui.hud.HudClock
import com.nexwork.edith.ui.hud.HudSearchBar
import com.nexwork.edith.ui.hud.InteractiveWireframeGlobe
import com.nexwork.edith.ui.hud.groupApps
import kotlin.math.abs

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
  val consoleOpen = state.isSpotlightSearchOpen || state.isAppBrowseOpen
  val dismissConsole: () -> Unit = {
    if (state.isSpotlightSearchOpen) viewModel.closeSpotlightSearch() else viewModel.closeAppBrowse()
  }

  // Back climbs one level: a category back to the categories, then the console closed.
  BackHandler(enabled = consoleOpen) { viewModel.handleBackPress() }

  var totalVerticalDrag by remember { mutableFloatStateOf(0f) }
  var totalHorizontalDrag by remember { mutableFloatStateOf(0f) }
  var dragStartY by remember { mutableFloatStateOf(0f) }
  var dragStartX by remember { mutableFloatStateOf(0f) }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .pointerInput(state.isSpotlightSearchOpen, state.isAppBrowseOpen) {
        if (!consoleOpen) {
          detectDragGestures(
            onDragStart = { offset ->
              totalVerticalDrag = 0f
              totalHorizontalDrag = 0f
              dragStartX = offset.x
              dragStartY = offset.y
            },
            onDrag = { change, dragAmount ->
              val screenWidth = size.width
              // Exclusion zones: left system back edge, right edge, extreme top
              val isExcludedZone = dragStartX < 40.dp.toPx() ||
                dragStartX > (screenWidth - 124.dp.toPx()) ||
                dragStartY < 40.dp.toPx()

              if (!isExcludedZone) {
                totalVerticalDrag += dragAmount.y
                totalHorizontalDrag += dragAmount.x

                val thresholdPx = 40.dp.toPx()
                val isStrictlyVerticalDown = totalVerticalDrag > 0f &&
                  totalVerticalDrag > abs(totalHorizontalDrag) * 1.5f

                if (isStrictlyVerticalDown && totalVerticalDrag > thresholdPx) {
                  change.consume()
                  viewModel.openSpotlightSearch()
                  totalVerticalDrag = 0f
                  totalHorizontalDrag = 0f
                }

                // The browse deck stays clear of the thin system back edges.
                val edge = 24.dp.toPx()
                val isUpExcludedZone = dragStartX < edge ||
                  dragStartX > screenWidth - edge ||
                  dragStartY < 40.dp.toPx()
                val isStrictlyVerticalUp = totalVerticalDrag < 0f &&
                  -totalVerticalDrag > abs(totalHorizontalDrag) * 1.5f

                if (!isUpExcludedZone && isStrictlyVerticalUp && -totalVerticalDrag > thresholdPx) {
                  change.consume()
                  haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  viewModel.openAppBrowse()
                  totalVerticalDrag = 0f
                  totalHorizontalDrag = 0f
                }
              }
            },
            onDragEnd = {
              totalVerticalDrag = 0f
              totalHorizontalDrag = 0f
            },
            onDragCancel = {
              totalVerticalDrag = 0f
              totalHorizontalDrag = 0f
            },
          )
        } else {
          detectDragGestures(
            onDragStart = {
              totalVerticalDrag = 0f
              totalHorizontalDrag = 0f
            },
            onDrag = { change, dragAmount ->
              totalVerticalDrag += dragAmount.y
              totalHorizontalDrag += dragAmount.x

              if (totalVerticalDrag < -28.dp.toPx() && abs(totalVerticalDrag) > abs(totalHorizontalDrag)) {
                change.consume()
                dismissConsole()
                totalVerticalDrag = 0f
                totalHorizontalDrag = 0f
              }
            },
            onDragEnd = {
              totalVerticalDrag = 0f
              totalHorizontalDrag = 0f
            },
            onDragCancel = {
              totalVerticalDrag = 0f
              totalHorizontalDrag = 0f
            },
          )
        }
      }
  ) {
    val searchOpen = state.isSpotlightSearchOpen
    val browseGroupCount = remember(state.apps, state.browseGroup) {
      val name = state.browseGroup ?: return@remember 0
      groupApps(state.apps).firstOrNull { it.name == name }?.apps?.size ?: 0
    }
    val bgZoom by animateFloatAsState(
      targetValue = if (consoleOpen) 1.08f else 1.0f,
      animationSpec = tween(500),
      label = "bgZoom",
    )

    // Wireframe Earth backdrop with rack focus dolly zoom and blur
    Box(
      Modifier
        .fillMaxSize()
        .graphicsLayer {
          scaleX = bgZoom
          scaleY = bgZoom
          if (consoleOpen && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            renderEffect = android.graphics.RenderEffect.createBlurEffect(
              12f,
              12f,
              android.graphics.Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
          }
        }
        .background(Color.Black)
    ) {
      InteractiveWireframeGlobe(
        modifier = Modifier.fillMaxSize(),
      )
    }

    // Crime scene intro camera lens depth overlay
    CrimeSceneFocusOverlay(
      isOpen = consoleOpen,
      modifier = Modifier.fillMaxSize(),
    )

    // The clock owns the top bay until the console is summoned into it.
    HudClock(
      isOpen = consoleOpen,
      modifier = Modifier.align(Alignment.TopStart),
    )

    // Tap outside the console dismisses it
    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(state.isSpotlightSearchOpen, state.isAppBrowseOpen) {
          if (consoleOpen) {
            detectTapGestures { dismissConsole() }
          }
        }
    )

    HudSearchBar(
      isOpen = consoleOpen,
      searchQuery = state.searchQuery,
      onSearchQueryChange = viewModel::onSearchQueryChange,
      onDismiss = dismissConsole,
      apps = state.apps,
      onLaunchApp = viewModel::launchApp,
      autoFocus = searchOpen,
      idleDismiss = searchOpen,
      scopeLabel = state.browseGroup,
      scopeCount = browseGroupCount,
      onExitGroup = viewModel::closeAppGroup,
      modifier = Modifier.align(Alignment.TopStart),
    )

    // Results as a cascading deck; the leader is sharp, the rest defocus. Browse mode opens on
    // category plates and drills into one category's app deck; the strip's drag walks the window
    // past the five dealt cards, and a pull past the head of a category climbs back out.
    // Once the IME retracts the leader card parks with its centre at half screen height.
    val cardHeight = (maxWidth - 36.dp) * 124f / 312f
    val deckResting = maxHeight * 0.5f - cardHeight / 2f
    val keyboardUp = consoleOpen && WindowInsets.isImeVisible
    val deckShift by animateFloatAsState(
      targetValue = if (keyboardUp) 0f else 1f,
      animationSpec = tween(260),
      label = "deckShift",
    )
    val deckTop = 236.dp + (deckResting - 236.dp) * deckShift

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
        .padding(start = 18.dp, end = 18.dp, top = deckTop, bottom = 28.dp),
    )
  }
}
