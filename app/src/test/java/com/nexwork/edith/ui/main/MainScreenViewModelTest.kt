package com.nexwork.edith.ui.main

import com.nexwork.edith.data.DataRepository
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun uiState_initiallyLoading() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertEquals(viewModel.uiState.first(), MainScreenUiState.Loading)
  }

  @Test
  fun uiState_onItemSaved_isDisplayed() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertEquals(viewModel.uiState.first(), MainScreenUiState.Loading)
  }

  @Test
  fun spotlightSearch_openAndClose() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertFalse(viewModel.launcherState.value.isSpotlightSearchOpen)

    viewModel.openSpotlightSearch()
    assertTrue(viewModel.launcherState.value.isSpotlightSearchOpen)
    assertEquals("", viewModel.launcherState.value.searchQuery)

    viewModel.onSearchQueryChange("WhatsApp")
    assertEquals("WhatsApp", viewModel.launcherState.value.searchQuery)

    viewModel.closeSpotlightSearch()
    assertFalse(viewModel.launcherState.value.isSpotlightSearchOpen)
    assertEquals("", viewModel.launcherState.value.searchQuery)
  }

  @Test
  fun handleBackPress_closesSpotlightSearch() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    viewModel.openSpotlightSearch()
    assertTrue(viewModel.launcherState.value.isSpotlightSearchOpen)

    val handled = viewModel.handleBackPress()
    assertTrue(handled)
    assertFalse(viewModel.launcherState.value.isSpotlightSearchOpen)

    // Second back press on root home screen returns false
    val handledRoot = viewModel.handleBackPress()
    assertFalse(handledRoot)
  }

  @Test
  fun filterMatchingApps_prioritizesMostUsedAppForAutoLaunch() {
    val appLeastUsed = com.nexwork.edith.model.AppEntry(
      label = "Calculator",
      packageName = "com.google.android.calculator",
      activityName = "",
      usageTimeMillis14d = 1_000L,
    )
    val appMostUsed = com.nexwork.edith.model.AppEntry(
      label = "Camera",
      packageName = "com.motorola.camera3",
      activityName = "",
      usageTimeMillis14d = 50_000L,
    )
    val appMediumUsed = com.nexwork.edith.model.AppEntry(
      label = "Calendar",
      packageName = "com.google.android.calendar",
      activityName = "",
      usageTimeMillis14d = 10_000L,
    )

    val apps = listOf(appLeastUsed, appMostUsed, appMediumUsed)

    val matched = com.nexwork.edith.ui.components.filterMatchingApps("cal", apps)
    assertEquals(2, matched.size)
    // Between Calendar (10_000L) and Calculator (1_000L), Calendar has higher usage so it must be first
    assertEquals("Calendar", matched.first().label)

    val matchedC = com.nexwork.edith.ui.components.filterMatchingApps("c", apps)
    assertEquals(3, matchedC.size)
    // Camera has highest usage (50_000L), so it is at index 0 for 3-second auto-launch
    assertEquals("Camera", matchedC.first().label)
  }

  @Test
  fun filterMatchingApps_emptyQueryReturnsEmpty() {
    val apps = listOf(
      com.nexwork.edith.model.AppEntry(
        label = "Phone",
        packageName = "com.google.android.dialer",
        activityName = "",
      )
    )
    assertTrue(com.nexwork.edith.ui.components.filterMatchingApps("", apps).isEmpty())
    assertTrue(com.nexwork.edith.ui.components.filterMatchingApps("   ", apps).isEmpty())
  }
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}
