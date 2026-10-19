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
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}
