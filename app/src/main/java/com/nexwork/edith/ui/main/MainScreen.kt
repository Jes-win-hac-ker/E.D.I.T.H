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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.nexwork.edith.ui.components.GridLines
import com.nexwork.edith.ui.components.RightAppRail
import com.nexwork.edith.ui.components.RotaryClockDial
import com.nexwork.edith.ui.components.VerticalEnergyRail
import com.nexwork.edith.ui.hud.CrimeSceneFocusOverlay
import com.nexwork.edith.ui.hud.HudCardDeck
import com.nexwork.edith.ui.hud.HudClock
import com.nexwork.edith.ui.hud.HudSearchBar
import kotlin.math.abs

/**
 * Home screen — Stark HUD void with the flat search line.
 * Swipe down anywhere (outside system gesture edges) opens the search; swipe up or
 * tap outside dismisses it. Auto-launch logic lives in HudSearchBar.
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

  BackHandler(enabled = state.isSpotlightSearchOpen) {
    viewModel.closeSpotlightSearch()
  }

  var totalVerticalDrag by remember { mutableFloatStateOf(0f) }
  var totalHorizontalDrag by remember { mutableFloatStateOf(0f) }
  var dragStartY by remember { mutableFloatStateOf(0f) }
  var dragStartX by remember { mutableFloatStateOf(0f) }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .pointerInput(state.isSpotlightSearchOpen) {
        if (!state.isSpotlightSearchOpen) {
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
                viewModel.closeSpotlightSearch()
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
    val bgZoom by animateFloatAsState(
      targetValue = if (searchOpen) 1.08f else 1.0f,
      animationSpec = tween(500),
      label = "bgZoom",
    )

    // Cleared: pure black void backdrop with rack focus dolly zoom
    Box(
      Modifier
        .fillMaxSize()
        .graphicsLayer {
          scaleX = bgZoom
          scaleY = bgZoom
          if (searchOpen && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            renderEffect = android.graphics.RenderEffect.createBlurEffect(
              12f,
              12f,
              android.graphics.Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
          }
        }
        .background(Color.Black)
    )

    // Crime scene intro camera lens depth overlay
    CrimeSceneFocusOverlay(
      isOpen = searchOpen,
      modifier = Modifier.fillMaxSize(),
    )

    // The clock owns the top bay until the console is summoned into it.
    HudClock(
      isOpen = state.isSpotlightSearchOpen,
      modifier = Modifier.align(Alignment.TopStart),
    )

    // Tap outside the search line dismisses it
    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(state.isSpotlightSearchOpen) {
          if (state.isSpotlightSearchOpen) {
            detectTapGestures {
              viewModel.closeSpotlightSearch()
            }
          }
        }
    )

    HudSearchBar(
      isOpen = state.isSpotlightSearchOpen,
      searchQuery = state.searchQuery,
      onSearchQueryChange = viewModel::onSearchQueryChange,
      onDismiss = viewModel::closeSpotlightSearch,
      apps = state.apps,
      onLaunchApp = viewModel::launchApp,
      modifier = Modifier.align(Alignment.TopStart),
    )

    // Results as a cascading deck; the leader is sharp, the rest defocus.
    // Once the IME retracts the leader card parks with its centre at half screen height,
    // and the deck's gesture column runs from just under the stack to the bottom pad.
    val cardHeight = (maxWidth - 36.dp) * 124f / 312f
    val deckResting = maxHeight * 0.5f - cardHeight / 2f
    val keyboardUp = state.isSpotlightSearchOpen && WindowInsets.isImeVisible
    val deckShift by animateFloatAsState(
      targetValue = if (keyboardUp) 0f else 1f,
      animationSpec = tween(260),
      label = "deckShift",
    )
    val deckTop = 236.dp + (deckResting - 236.dp) * deckShift

    HudCardDeck(
      isOpen = state.isSpotlightSearchOpen,
      searchQuery = state.searchQuery,
      onDismiss = viewModel::closeSpotlightSearch,
      apps = state.apps,
      onLaunchApp = viewModel::launchApp,
      footprintOf = viewModel::footprintOf,
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(start = 18.dp, end = 18.dp, top = deckTop, bottom = 28.dp),
    )
  }
}

@Composable
internal fun MainScreen(data: List<String>, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0B0D12))
  ) {
    GridLines()

    VerticalEnergyRail(
      batteryPct = 84,
      isCharging = false,
      modifier = Modifier.fillMaxSize(),
    )

    Box(
      modifier = Modifier
        .align(Alignment.CenterStart)
    ) {
      RotaryClockDial(
        use24Hour = false,
      )
    }

    Box(
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .width(124.dp)
    ) {
      RightAppRail()
    }
  }
}
