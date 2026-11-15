package com.nexwork.edith

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.nexwork.edith.ui.main.MainScreen

val LocalHomeResetSignal = staticCompositionLocalOf { 0 }

@Composable
fun MainNavigation(homeResetSignal: Int = LocalHomeResetSignal.current) {
  val backStack = rememberNavBackStack(Main)

  LaunchedEffect(homeResetSignal) {
    if (homeResetSignal > 0 && backStack.size > 1) {
      while (backStack.size > 1) {
        backStack.removeLastOrNull()
      }
    }
  }

  NavDisplay(
    backStack = backStack,
    onBack = {
      // A Home Launcher stays on the root home screen when Back is pressed at root
      if (backStack.size > 1) {
        backStack.removeLastOrNull()
      }
    },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(
            onItemClick = { navKey -> backStack.add(navKey) },
            modifier = Modifier.fillMaxSize(),
          )
        }
      },
  )
}
