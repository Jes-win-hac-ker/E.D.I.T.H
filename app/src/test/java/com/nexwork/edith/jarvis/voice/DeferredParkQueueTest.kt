package com.nexwork.edith.jarvis.voice

import com.nexwork.edith.jarvis.core.MotionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class DeferredParkQueueTest {

  @Test
  fun shouldOfferEarbudWhisper_requiresMotionAndEarbuds() {
    val q = DeferredParkQueue(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), speaker = {})
    assertFalse(q.shouldOfferEarbudWhisper(MotionState.STILL, earbudsConnected = true))
    assertFalse(q.shouldOfferEarbudWhisper(MotionState.WALKING, earbudsConnected = false))
    assertTrue(q.shouldOfferEarbudWhisper(MotionState.WALKING, earbudsConnected = true))
    assertTrue(q.shouldOfferEarbudWhisper(MotionState.IN_VEHICLE, earbudsConnected = true))
  }

  @Test
  fun unclaimedWhisper_parksAutomaticallyAfterFiveSeconds() = runTest {
    val spoken = mutableListOf<String>()
    val q = DeferredParkQueue(
      scope = this,
      speaker = WhisperSpeaker { spoken += it },
      whisperWindowMs = 5_000L,
      clockMs = { 1000L },
    )
    val msg = q.createParkedMessage(
      sbnKey = "sbn_1",
      appLabel = "WhatsApp",
      sender = "Rahul",
      preview = "Evideya machane?",
      summary = "Asking where you are",
      urgency = "HIGH",
      hasReplyHandle = true,
    )

    q.offerOrPark(msg, isLiveSessionActive = false)
    runCurrent()
    assertEquals(1, spoken.size)
    assertTrue(spoken[0].contains("Urgent Rahul on WhatsApp"))
    assertNotNull(q.activeWhisper.value)
    assertTrue(q.parkedMessages.value.isEmpty())

    advanceTimeBy(5_001L)
    runCurrent()

    assertNull(q.activeWhisper.value)
    assertEquals(1, q.parkedMessages.value.size)
    assertEquals("Rahul", q.parkedMessages.value.first().sender)
  }

  @Test
  fun claimActiveWhisper_cancelsParkingAndRemovesFromQueue() = runTest {
    val q = DeferredParkQueue(scope = this, speaker = {}, whisperWindowMs = 5_000L)
    val msg = q.createParkedMessage("k2", "Telegram", "Amma", "Reached?", "Check arrival", "MEDIUM", true)
    q.offerOrPark(msg)
    runCurrent()

    val claimed = q.claimActiveWhisper()
    assertNotNull(claimed)
    assertEquals("k2", claimed!!.sbnKey)

    advanceTimeBy(6_000L)
    runCurrent()
    assertTrue(q.parkedMessages.value.isEmpty())
  }

  @Test
  fun liveSessionActive_parksImmediatelyWithoutWhisperInterrupting() = runTest {
    val spoken = mutableListOf<String>()
    val q = DeferredParkQueue(scope = this, speaker = { spoken += it })
    val msg = q.createParkedMessage("k3", "Signal", "Dev", "PR merged", "PR update", "LOW", false)

    q.offerOrPark(msg, isLiveSessionActive = true)
    assertTrue(spoken.isEmpty())
    assertNull(q.activeWhisper.value)
    assertEquals(1, q.parkedMessages.value.size)
  }
}
