package com.nexwork.edith.ui.main

import com.nexwork.edith.data.DataRepository
import com.nexwork.edith.model.HudIntensityPreset
import com.nexwork.edith.model.JarvisSettingsSection
import com.nexwork.edith.model.LauncherAccentPreset
import com.nexwork.edith.model.LauncherFontStyle
import com.nexwork.edith.model.LauncherSettingsConfig
import com.nexwork.edith.model.LauncherSettingsSection
import com.nexwork.edith.model.SettingsStage
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.WarmSunAccent
import com.nexwork.edith.theme.buildLauncherStyleTokens
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

  @Test
  fun settingsTree_stageTransitionsAndStepBackUnwinding() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertEquals(SettingsStage.CLOSED, viewModel.launcherState.value.settingsStage)

    viewModel.openSettingsRoot()
    assertEquals(SettingsStage.ROOT_BRANCHES, viewModel.launcherState.value.settingsStage)
    assertNull(viewModel.launcherState.value.selectedSettingsSection)

    // Cannot enter ambient center while settings overlay is active
    viewModel.enterAmbientCenter()
    assertFalse(viewModel.launcherState.value.isAmbientCentered)

    viewModel.selectSettingsBranch(SettingsStage.LAUNCHER_TREE)
    assertEquals(SettingsStage.LAUNCHER_TREE, viewModel.launcherState.value.settingsStage)

    viewModel.selectLauncherSetting(LauncherSettingsSection.FONT_SCALE)
    assertEquals(LauncherSettingsSection.FONT_SCALE, viewModel.launcherState.value.selectedSettingsSection)

    // Back 1: detail card -> left list
    assertTrue(viewModel.handleBackPress())
    assertNull(viewModel.launcherState.value.selectedSettingsSection)
    assertEquals(SettingsStage.LAUNCHER_TREE, viewModel.launcherState.value.settingsStage)

    // Back 2: left list -> 3-branch root
    assertTrue(viewModel.handleBackPress())
    assertEquals(SettingsStage.ROOT_BRANCHES, viewModel.launcherState.value.settingsStage)

    // Agent / Jarvis branch drills into JarvisSettingsSection and unwinds cleanly back to root
    viewModel.selectSettingsBranch(SettingsStage.AGENT_PLACEHOLDER)
    assertEquals(SettingsStage.AGENT_PLACEHOLDER, viewModel.launcherState.value.settingsStage)
    assertNull(viewModel.launcherState.value.selectedJarvisSection)

    viewModel.selectJarvisSetting(JarvisSettingsSection.KEY_VAULT)
    assertEquals(JarvisSettingsSection.KEY_VAULT, viewModel.launcherState.value.selectedJarvisSection)

    assertTrue(viewModel.handleBackPress())
    assertNull(viewModel.launcherState.value.selectedJarvisSection)
    assertEquals(SettingsStage.AGENT_PLACEHOLDER, viewModel.launcherState.value.settingsStage)

    assertTrue(viewModel.handleBackPress())
    assertEquals(SettingsStage.ROOT_BRANCHES, viewModel.launcherState.value.settingsStage)

    // Back 3: 3-branch root -> closed home screen
    assertTrue(viewModel.handleBackPress())
    assertEquals(SettingsStage.CLOSED, viewModel.launcherState.value.settingsStage)
    assertFalse(viewModel.handleBackPress())
  }

  @Test
  fun launcherSettings_updatesIndependentOfOsMonet_andOtaCommandIntact() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertFalse(viewModel.launcherState.value.monetUiState.isEnabledPersisted)

    viewModel.updateLauncherAccent(LauncherAccentPreset.SOLAR_AMBER)
    viewModel.updateHudIntensity(HudIntensityPreset.OVERDRIVE)
    viewModel.updateLauncherFont(LauncherFontStyle.MONOSPACE)
    viewModel.updateLauncherFontScale(1.12f)
    viewModel.setUse24Hour(false)

    val cfg = viewModel.launcherState.value.launcherSettings
    assertEquals(LauncherAccentPreset.SOLAR_AMBER, cfg.accentPreset)
    assertEquals(HudIntensityPreset.OVERDRIVE, cfg.hudIntensity)
    assertEquals(LauncherFontStyle.MONOSPACE, cfg.fontStyle)
    assertEquals(1.12f, cfg.fontScale, 0.001f)
    assertFalse(cfg.use24Hour)
    assertFalse(viewModel.launcherState.value.use24Hour)
    // Launcher theme customization must never alter OS-wide Shizuku Monet state
    assertFalse(viewModel.launcherState.value.monetUiState.isEnabledPersisted)

    // Amber accent shifts semanticWarn to cyan so warnings stay distinct from active chrome
    val amberTokens = buildLauncherStyleTokens(cfg)
    assertEquals(IceGlowAccent, amberTokens.semanticWarn)
    assertEquals(1.0f, amberTokens.focusedBorderAlpha, 0.001f)

    viewModel.updateLauncherAccent(LauncherAccentPreset.VERIDIAN_TAC)
    val veridianTokens = buildLauncherStyleTokens(viewModel.launcherState.value.launcherSettings)
    assertEquals(WarmSunAccent, veridianTokens.semanticWarn)

    // !ota command remains available alongside the Settings OTA button
    viewModel.openSpotlightSearch()
    viewModel.onSearchQueryChange("!ota")
    assertEquals("OTA  ·  CHECK RELEASE", viewModel.launcherState.value.commandHint)
    assertTrue(viewModel.submitConsoleQuery())
  }

  @Test
  fun jarvisDrawer_opensViaCommandAndUnwindsViaBack() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertFalse(viewModel.launcherState.value.isJarvisSheetOpen)

    viewModel.openSpotlightSearch()
    viewModel.onSearchQueryChange("!jarvis")
    assertEquals("JARVIS  ·  BASEPLATE", viewModel.launcherState.value.commandHint)
    assertTrue(viewModel.submitConsoleQuery())
    assertTrue(viewModel.launcherState.value.isJarvisSheetOpen)
    assertFalse(viewModel.launcherState.value.isSpotlightSearchOpen)

    // Cannot enter ambient center while Jarvis sheet is open
    viewModel.enterAmbientCenter()
    assertFalse(viewModel.launcherState.value.isAmbientCentered)

    // Back closes Jarvis drawer cleanly
    assertTrue(viewModel.handleBackPress())
    assertFalse(viewModel.launcherState.value.isJarvisSheetOpen)
    assertFalse(viewModel.handleBackPress())
  }
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}
