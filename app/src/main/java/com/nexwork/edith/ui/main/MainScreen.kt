package com.nexwork.edith.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.nexwork.edith.ui.components.GridLines
import com.nexwork.edith.ui.components.RightAppRail

/**
 * Screen with interactive reactive grid lines and custom-icon right app rail:
 * WhatsApp, Camera, Phone, GPay, Google (at the bottom).
 */
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0B0D12))
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
