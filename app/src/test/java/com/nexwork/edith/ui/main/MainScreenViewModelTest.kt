package com.nexwork.edith.ui.main

import com.nexwork.edith.data.DataRepository
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
  fun handleBackPress_closesAppBrowse() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    viewModel.openAppBrowse()
    assertTrue(viewModel.launcherState.value.isAppBrowseOpen)

    val handled = viewModel.handleBackPress()
    assertTrue(handled)
    assertFalse(viewModel.launcherState.value.isAppBrowseOpen)
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

    val matched = com.nexwork.edith.ui.hud.filterMatchingApps("cal", apps)
    assertEquals(2, matched.size)
    // Between Calendar (10_000L) and Calculator (1_000L), Calendar has higher usage so it must be first
    assertEquals("Calendar", matched.first().label)

    val matchedC = com.nexwork.edith.ui.hud.filterMatchingApps("c", apps)
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
    assertTrue(com.nexwork.edith.ui.hud.filterMatchingApps("", apps).isEmpty())
    assertTrue(com.nexwork.edith.ui.hud.filterMatchingApps("   ", apps).isEmpty())
  }

  @Test
  fun filterMatchingApps_strictlyMatchesDisplayLabel_notPackageName() {
    val youtube = com.nexwork.edith.model.AppEntry(
      label = "YouTube",
      packageName = "com.google.android.youtube",
      activityName = "",
      usageTimeMillis14d = 100_000L,
    )
    val chrome = com.nexwork.edith.model.AppEntry(
      label = "Chrome",
      packageName = "com.android.chrome",
      activityName = "",
      usageTimeMillis14d = 80_000L,
    )
    val googleApp = com.nexwork.edith.model.AppEntry(
      label = "Google",
      packageName = "com.google.android.googlequicksearchbox",
      activityName = "",
      usageTimeMillis14d = 50_000L,
    )
    val googleDrive = com.nexwork.edith.model.AppEntry(
      label = "Google Drive",
      packageName = "com.google.android.apps.docs",
      activityName = "",
      usageTimeMillis14d = 60_000L,
    )

    val apps = listOf(youtube, chrome, googleApp, googleDrive)

    // Searching "google" must only match "Google" and "Google Drive", NOT YouTube or Chrome
    val googleMatches = com.nexwork.edith.ui.hud.filterMatchingApps("google", apps)
    assertEquals(2, googleMatches.size)
    // "Google" is an exact match (Tier 1), so it must rank #1 even though Google Drive has more usage
    assertEquals("Google", googleMatches[0].label)
    assertEquals("Google Drive", googleMatches[1].label)

    // Searching "chrome" must only match Chrome
    val chromeMatches = com.nexwork.edith.ui.hud.filterMatchingApps("chrome", apps)
    assertEquals(1, chromeMatches.size)
    assertEquals("Chrome", chromeMatches[0].label)
  }

  @Test
  fun ambientCenter_entersOnIdleAndWakesOnActivity() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertFalse(viewModel.launcherState.value.isAmbientCentered)

    viewModel.enterAmbientCenter()
    assertTrue(viewModel.launcherState.value.isAmbientCentered)

    viewModel.wakeFromAmbient()
    assertFalse(viewModel.launcherState.value.isAmbientCentered)
  }

  @Test
  fun autoDeactivateConsole_closesSearchAndBrowseIntoAmbientCenter() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())

    viewModel.openSpotlightSearch()
    viewModel.onSearchQueryChange("cam")
    assertTrue(viewModel.launcherState.value.isSpotlightSearchOpen)
    assertFalse(viewModel.launcherState.value.isAmbientCentered)

    // Cannot enter ambient directly while console is open
    viewModel.enterAmbientCenter()
    assertFalse(viewModel.launcherState.value.isAmbientCentered)

    // Auto-deactivation closes console cleanly and glides globe straight to center
    viewModel.autoDeactivateConsole()
    assertFalse(viewModel.launcherState.value.isSpotlightSearchOpen)
    assertFalse(viewModel.launcherState.value.isAppBrowseOpen)
    assertEquals("", viewModel.launcherState.value.searchQuery)
    assertTrue(viewModel.launcherState.value.isAmbientCentered)

    // Opening browse wakes from ambient; manual close stays active on horizon arc
    viewModel.openAppBrowse()
    viewModel.openAppGroup("TOOLS")
    assertFalse(viewModel.launcherState.value.isAmbientCentered)

    viewModel.closeAppBrowse()
    assertFalse(viewModel.launcherState.value.isAppBrowseOpen)
    assertEquals(null, viewModel.launcherState.value.browseGroup)
    assertFalse(viewModel.launcherState.value.isAmbientCentered)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun consoleMath_previewsInHintAndEmitsClipboardFeedback() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    val events = mutableListOf<HudFeedbackEvent>()
    val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      viewModel.feedbackEvents.collect { events.add(it) }
    }

    viewModel.openSpotlightSearch()
    viewModel.onSearchQueryChange("12+8")
    assertEquals("= 20", viewModel.launcherState.value.commandHint)

    val handled = viewModel.submitConsoleQuery()
    assertTrue(handled)
    assertEquals("= 20  ·  COPIED", viewModel.launcherState.value.commandHint)
    assertEquals(1, events.size)
    assertEquals("20", events[0].clipboardText)

    viewModel.closeSpotlightSearch()
    assertNull(viewModel.launcherState.value.commandHint)
    collectJob.cancel()
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun consoleCommands_previewTelemetryPinAndHelp() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    val events = mutableListOf<HudFeedbackEvent>()
    val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      viewModel.feedbackEvents.collect { events.add(it) }
    }

    viewModel.openSpotlightSearch()
    viewModel.onSearchQueryChange("!bat")
    assertTrue(viewModel.launcherState.value.commandHint!!.startsWith("BAT "))
    assertTrue(viewModel.submitConsoleQuery())

    viewModel.onSearchQueryChange("!ram")
    assertTrue(viewModel.launcherState.value.commandHint!!.startsWith("RAM "))
    assertTrue(viewModel.submitConsoleQuery())

    viewModel.onSearchQueryChange("!pin da")
    assertEquals("PIN  ·  DOLBY ATMOS", viewModel.launcherState.value.commandHint)

    viewModel.onSearchQueryChange("!pin ch")
    assertEquals("UNPIN  ·  CHROME", viewModel.launcherState.value.commandHint)

    viewModel.onSearchQueryChange("!help")
    assertTrue(viewModel.launcherState.value.commandHint!!.contains("!PIN"))
    assertTrue(viewModel.submitConsoleQuery())

    assertEquals(3, events.size)
    assertTrue(events.last().message.contains("!pin"))
    collectJob.cancel()
  }
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}
