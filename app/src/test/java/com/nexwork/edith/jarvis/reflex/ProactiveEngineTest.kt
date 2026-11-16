package com.nexwork.edith.jarvis.reflex

import com.nexwork.edith.jarvis.core.AmbientContext
import com.nexwork.edith.jarvis.core.MotionState
import com.nexwork.edith.jarvis.core.PlaceTag
import com.nexwork.edith.jarvis.core.ProactiveSuggestion
import com.nexwork.edith.jarvis.core.UpcomingDestination
import com.nexwork.edith.jarvis.senses.ContextSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProactiveEngineTest {

  private class FakeDestinationStore(
    val items: MutableList<UpcomingDestination> = mutableListOf(),
  ) : DestinationStore {
    val usedIds = mutableListOf<Long>()
    override fun getActiveUpcomingDestinations(nowMs: Long): List<UpcomingDestination> =
      items.filter { !it.used && it.expiresAtMs > nowMs }

    override fun markDestinationUsed(id: Long) {
      usedIds.add(id)
      val idx = items.indexOfFirst { it.id == id }
      if (idx >= 0) items[idx] = items[idx].copy(used = true)
    }
  }

  @Test
  fun trafficStall_vehicleToStill_awayWithDestination_emitsOpenMaps() = runTest {
    var nowMs = 1_000_000L
    val store = FakeDestinationStore(
      mutableListOf(
        UpcomingDestination(
          id = 7L,
          placeName = "Indiranagar 100ft Road",
          sourceSummary = "WhatsApp: Meet at 7pm",
          expiresAtMs = nowMs + 60_000_000L,
        )
      )
    )
    var verifierCalls = 0
    val engine = ProactiveEngine(
      destinationStore = store,
      locationVerifier = OneShotLocationVerifier {
        verifierCalls++
        "Nearby Venue"
      },
      clockMs = { nowMs },
    )

    val prev = AmbientContext(motionState = MotionState.IN_VEHICLE, placeTag = PlaceTag.NONE)
    val cur = AmbientContext(motionState = MotionState.STILL, previousMotionState = MotionState.IN_VEHICLE, placeTag = PlaceTag.NONE)
    engine.onSignal(ContextSignal.StateCommitted(prev, cur, nowMs))

    val suggestion = engine.suggestion.value
    assertTrue(suggestion is ProactiveSuggestion.OpenMaps)
    suggestion as ProactiveSuggestion.OpenMaps
    assertEquals(7L, suggestion.destinationId)
    assertEquals("Indiranagar 100ft Road", suggestion.placeName)
    assertEquals(0, verifierCalls)

    engine.consumeOpenMapsSuggestion(7L)
    assertNull(engine.suggestion.value)
    assertEquals(listOf(7L), store.usedIds)
  }

  @Test
  fun postDiningQr_requires20MinAwayDwellAndOneShotGeocodeConfirmation() = runTest {
    var nowMs = 10_000_000L
    val store = FakeDestinationStore()
    var verifierCalls = 0
    val engine = ProactiveEngine(
      destinationStore = store,
      locationVerifier = OneShotLocationVerifier {
        verifierCalls++
        "Truffles Koramangala"
      },
      clockMs = { nowMs },
    )

    // Only 10 mins away -> must NOT trigger location lookup or suggestion.
    val shortDwellCtx = AmbientContext(
      motionState = MotionState.WALKING,
      previousMotionState = MotionState.STILL,
      placeTag = PlaceTag.NONE,
      awayDwellSinceMs = nowMs - (10L * 60_000L),
    )
    engine.onSignal(
      ContextSignal.StateCommitted(
        previous = shortDwellCtx.copy(motionState = MotionState.STILL),
        current = shortDwellCtx,
        atMs = nowMs,
      )
    )
    assertNull(engine.suggestion.value)
    assertEquals(0, verifierCalls)

    // 22 mins away + WALKING -> runs single one-shot location confirmation and emits ScanUpiQr.
    val longDwellCtx = shortDwellCtx.copy(awayDwellSinceMs = nowMs - (22L * 60_000L))
    engine.onSignal(
      ContextSignal.StateCommitted(
        previous = longDwellCtx.copy(motionState = MotionState.STILL),
        current = longDwellCtx,
        atMs = nowMs,
      )
    )
    val emitted = engine.suggestion.value
    assertTrue(emitted is ProactiveSuggestion.ScanUpiQr)
    emitted as ProactiveSuggestion.ScanUpiQr
    assertEquals("Truffles Koramangala", emitted.placeLabel)
    assertEquals(22L, emitted.dwellMinutes)
    assertEquals(1, verifierCalls)
  }

  @Test
  fun cooldown_blocksRepeatTriggerWithin30MinutesAndUnlocksAfter() = runTest {
    var nowMs = 20_000_000L
    val store = FakeDestinationStore(
      mutableListOf(
        UpcomingDestination(
          id = 11L,
          placeName = "MG Road Metro",
          sourceSummary = "Telegram note",
          expiresAtMs = nowMs + 100_000_000L,
        )
      )
    )
    val engine = ProactiveEngine(
      destinationStore = store,
      locationVerifier = OneShotLocationVerifier { null },
      clockMs = { nowMs },
    )

    val unlockAway = AmbientContext(motionState = MotionState.STILL, placeTag = PlaceTag.NONE)
    engine.onSignal(ContextSignal.ScreenUnlocked(unlockAway, nowMs))
    assertTrue(engine.suggestion.value is ProactiveSuggestion.OpenMaps)
    engine.dismissSuggestion()

    // 10 minutes later: blocked by 30-min cooldown.
    nowMs += 10L * 60_000L
    engine.onSignal(ContextSignal.ScreenUnlocked(unlockAway, nowMs))
    assertNull(engine.suggestion.value)

    // 20 minutes and 1 second later (total > 30 mins): allowed again.
    nowMs += 20L * 60_000L + 1_000L
    engine.onSignal(ContextSignal.ScreenUnlocked(unlockAway, nowMs))
    assertTrue(engine.suggestion.value is ProactiveSuggestion.OpenMaps)
  }

  @Test
  fun knownSavedPlace_suppressesProactiveSuggestions() = runTest {
    val nowMs = 30_000_000L
    val store = FakeDestinationStore(
      mutableListOf(
        UpcomingDestination(
          id = 21L,
          placeName = "Airport",
          sourceSummary = "Flight reminder",
          expiresAtMs = nowMs + 86_400_000L,
        )
      )
    )
    var verifierCalls = 0
    val engine = ProactiveEngine(
      destinationStore = store,
      locationVerifier = OneShotLocationVerifier {
        verifierCalls++
        "Home"
      },
      clockMs = { nowMs },
    )

    val homeUnlock = AmbientContext(
      motionState = MotionState.STILL,
      placeTag = PlaceTag.HOME,
      focusQuietActive = true,
      awayDwellSinceMs = null,
    )
    engine.onSignal(ContextSignal.ScreenUnlocked(homeUnlock, nowMs))
    assertNull(engine.suggestion.value)
    assertEquals(0, verifierCalls)
  }
}
