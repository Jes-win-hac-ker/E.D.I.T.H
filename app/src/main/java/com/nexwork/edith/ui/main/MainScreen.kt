package com.nexwork.edith.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.nexwork.edith.ui.components.GridLines
import com.nexwork.edith.ui.components.RightAppRail
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
  var totalVerticalDrag by remember { mutableFloatStateOf(0f) }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0B0D12))
      .pointerInput(state.isSpotlightSearchOpen) {
        if (!state.isSpotlightSearchOpen) {
          detectVerticalDragGestures(
            onDragStart = { totalVerticalDrag = 0f },
            onVerticalDrag = { change, dragAmount ->
              totalVerticalDrag += dragAmount
              if (totalVerticalDrag > 28f) {
                change.consume()
                viewModel.openSpotlightSearch()
                totalVerticalDrag = 0f
              }
            },
            onDragEnd = { totalVerticalDrag = 0f },
            onDragCancel = { totalVerticalDrag = 0f },
          )
        }
      }
  ) {
    // 1. Reactive Grid Lines covering the screen + solid vertical line
    GridLines()

    // 2. Right-side app rail placed in the 4-square right column
    Box(
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .width(124.dp)
    ) {
      RightAppRail()
    }

    // 3. Floating top search bar (revealed by swipe down, no overlay or blur)
    TopSearchBar(
      isOpen = state.isSpotlightSearchOpen,
      searchQuery = state.searchQuery,
      onSearchQueryChange = viewModel::onSearchQueryChange,
      onDismiss = viewModel::closeSpotlightSearch,
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
        .align(Alignment.CenterEnd)
        .width(124.dp)
    ) {
      RightAppRail()
    }
  }
}
