package com.nexwork.edith.jarvis.senses

import com.nexwork.edith.jarvis.core.MotionState
import com.nexwork.edith.jarvis.core.PlaceTag
import com.nexwork.edith.jarvis.core.SavedPlaceProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PassiveContextEngineTest {

  @Test
  fun motionTransition_uses45SecondDebounceAndCancelsFlaps() = runTest {
    val engine = PassiveContextEngine(
      debounceMs = 45_000L,
      scope = backgroundScope,
      clockMs = { 1_000_000L + testScheduler.currentTime },
    )
    val committedSignals = mutableListOf<ContextSignal.StateCommitted>()
    val collectJob = backgroundScope.launch {
      engine.signals.collect { sig ->
        if (sig is ContextSignal.StateCommitted) committedSignals.add(sig)
      }
    }
    runCurrent()

    engine.onRawMotionTransition(MotionState.IN_VEHICLE)
    advanceTimeBy(44_999L)
    runCurrent()
    assertEquals(MotionState.UNKNOWN, engine.contextState.value.motionState)
    assertTrue(committedSignals.isEmpty())

    advanceTimeBy(2L)
    runCurrent()
    assertEquals(MotionState.IN_VEHICLE, engine.contextState.value.motionState)
    assertEquals(1, committedSignals.size)

    // Brief flap to STILL followed back to IN_VEHICLE must cancel STILL commit.
    engine.onRawMotionTransition(MotionState.STILL)
    advanceTimeBy(20_000L)
    runCurrent()
    engine.onRawMotionTransition(MotionState.IN_VEHICLE)
    advanceTimeBy(30_000L)
    runCurrent()
    assertEquals(MotionState.IN_VEHICLE, engine.contextState.value.motionState)

    collectJob.cancel()
  }

  @Test
  fun savedPlaceProfiles_toggleFocusQuietAndManageAwayDwell() = runTest {
    val engine = PassiveContextEngine(
      debounceMs = 45_000L,
      scope = backgroundScope,
      clockMs = { 5_000_000L + testScheduler.currentTime },
    )
    val homeProfile = SavedPlaceProfile(
      id = 1L,
      ssidNormalized = "home_5g",
      displaySsid = "Home_5G",
      placeTag = PlaceTag.HOME,
      autoFocusQuiet = true,
    )
    val campusProfile = SavedPlaceProfile(
      id = 2L,
      ssidNormalized = "rvce_campus",
      displaySsid = "RVCE_Campus",
      placeTag = PlaceTag.CAMPUS,
      autoFocusQuiet = true,
    )
    engine.updateSavedPlaces(listOf(homeProfile, campusProfile))

    // Immediate initial Wi-Fi bind on HOME SSID activates Focus/Quiet and resets away dwell.
    engine.onRawWifiChanged("\"Home_5G\"", immediate = true)
    assertEquals(PlaceTag.HOME, engine.contextState.value.placeTag)
    assertTrue(engine.contextState.value.focusQuietActive)
    assertNull(engine.contextState.value.awayDwellSinceMs)

    // Leaving HOME SSID clears Focus/Quiet after 45s debounce and begins away dwell timer.
    engine.onRawWifiChanged(null)
    advanceTimeBy(45_000L)
    runCurrent()
    assertEquals(PlaceTag.NONE, engine.contextState.value.placeTag)
    assertFalse(engine.contextState.value.focusQuietActive)
    assertNotNull(engine.contextState.value.awayDwellSinceMs)

    // Entering CAMPUS SSID restores Focus/Quiet and clears away dwell.
    engine.onRawWifiChanged("RVCE_Campus", immediate = true)
    assertEquals(PlaceTag.CAMPUS, engine.contextState.value.placeTag)
    assertTrue(engine.contextState.value.focusQuietActive)
    assertNull(engine.contextState.value.awayDwellSinceMs)
  }

  @Test
  fun bluetoothScreenAndBatteryEvents_updateAmbientContextWithoutPolling() = runTest {
    val engine = PassiveContextEngine(
      debounceMs = 45_000L,
      scope = backgroundScope,
      clockMs = { 9_000_000L + testScheduler.currentTime },
    )
    val unlockSignals = mutableListOf<ContextSignal.ScreenUnlocked>()
    val collectJob = backgroundScope.launch {
      engine.signals.collect { sig ->
        if (sig is ContextSignal.ScreenUnlocked) unlockSignals.add(sig)
      }
    }
    runCurrent()

    engine.onBluetoothAclChanged("AA:BB:CC:DD:EE:FF", connected = true)
    assertEquals(1, engine.contextState.value.bluetoothConnectedCount)
    engine.onBluetoothAclChanged("AA:BB:CC:DD:EE:FF", connected = false)
    assertEquals(0, engine.contextState.value.bluetoothConnectedCount)

    engine.onBatteryUpdated(batteryPct = 42, isCharging = true)
    assertEquals(42, engine.contextState.value.batteryPct)
    assertTrue(engine.contextState.value.isCharging)

    engine.onScreenInteractiveChanged(interactive = false, isUserUnlock = false)
    assertFalse(engine.contextState.value.isScreenInteractive)
    engine.onScreenInteractiveChanged(interactive = true, isUserUnlock = true)
    runCurrent()
    assertTrue(engine.contextState.value.isScreenInteractive)
    assertEquals(1, unlockSignals.size)

    collectJob.cancel()
  }
}
