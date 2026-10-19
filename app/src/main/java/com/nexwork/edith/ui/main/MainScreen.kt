package com.nexwork.edith.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.nexwork.edith.ui.components.TopSearchBar

/**
 * Main Home Launcher screen:
 * - Reactive micro-grid lines with touch glow bloom.
 * - Solid line along the 4th major square line from the right.
 * - Custom-icon right app rail (Google, GPay, Phone, Camera, WhatsApp, Gemini, Qwen Code).
 * - Swipe Down on home screen reveals the top search bar directly over the grid without overlay.
 */
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

  // Intercept back gestures: dismiss spotlight search if open; consume at root so launcher never exits/refreshes
  BackHandler(enabled = true) {
    if (state.isSpotlightSearchOpen) {
      viewModel.closeSpotlightSearch()
    }
  }

  var totalVerticalDrag by remember { mutableFloatStateOf(0f) }
  var totalHorizontalDrag by remember { mutableFloatStateOf(0f) }
  var dragStartY by remember { mutableFloatStateOf(0f) }
  var dragStartX by remember { mutableFloatStateOf(0f) }

  val railAlpha by animateFloatAsState(
    targetValue = if (state.isSpotlightSearchOpen) 0.22f else 1f,
    label = "RightRailAlpha",
  )

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0B0D12))
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
              // Exclusion zones:
              // 1. Left system back gesture edge (0..40dp)
              // 2. Right app rail and right back edge (screenWidth - 124dp .. screenWidth)
              // 3. Extreme top margin (0..40dp)
              val isExcludedZone = dragStartX < 40.dp.toPx() ||
                dragStartX > (screenWidth - 124.dp.toPx()) ||
                dragStartY < 40.dp.toPx()

              if (!isExcludedZone) {
                totalVerticalDrag += dragAmount.y
                totalHorizontalDrag += dragAmount.x

                // Only activate search if gesture is strictly vertical downward (not horizontal or diagonal)
                // and displacement exceeds threshold
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

              // Dismiss search on upward swipe
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
    // 1. Reactive Grid Lines (tapping outside search bar dismisses search)
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
    ) {
      GridLines()
    }

    // 2. Sleek Dark Glass Rotary Clock & Weather Dial (anchored on the left)
    Box(
      modifier = Modifier
        .align(Alignment.CenterStart)
        .graphicsLayer { alpha = railAlpha }
    ) {
      RotaryClockDial(
        use24Hour = state.use24Hour,
      )
    }

    // 3. Right-side app rail placed in the 4-square right column (dimmed when search is open)
    Box(
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .width(124.dp)
        .graphicsLayer { alpha = railAlpha }
    ) {
      RightAppRail()
    }

    // 4. Floating top search bar with matching apps liquid glass horizontal tab
    TopSearchBar(
      isOpen = state.isSpotlightSearchOpen,
      searchQuery = state.searchQuery,
      apps = state.apps,
      onSearchQueryChange = viewModel::onSearchQueryChange,
      onLaunchApp = viewModel::launchApp,
      onDismiss = viewModel::closeSpotlightSearch,
      onSearch = { query ->
        val q = query.trim()
        if (q.isNotEmpty()) {
          val match = state.apps.firstOrNull {
            it.label.equals(q, ignoreCase = true)
          } ?: state.apps.firstOrNull {
            it.label.contains(q, ignoreCase = true)
          }
          if (match != null) {
            viewModel.launchApp(match)
            viewModel.closeSpotlightSearch()
          }
        }
      },
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
