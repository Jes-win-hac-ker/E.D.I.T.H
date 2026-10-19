package com.nexwork.edith.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation3.runtime.NavKey
import com.nexwork.edith.ui.components.GridLines

/**
 * Clean slate with layout grid lines.
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
    GridLines()
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
  }
}
