package com.nexwork.edith.jarvis.reflex

import com.nexwork.edith.jarvis.core.CapturedNotification
import com.nexwork.edith.jarvis.core.GroupEngagementRecord
import com.nexwork.edith.jarvis.core.ThreadMessage
import com.nexwork.edith.jarvis.senses.ExtractedNotification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupAndBurstReflexTest {

  @Test
  fun groupRelevanceScorer_suppressesLowEngagementOnlyAfterThresholdUnlessMentioned() {
    val newGroup = GroupEngagementRecord(
      groupKey = "com.whatsapp|college gossip",
      packageName = "com.whatsapp",
      groupName = "College Gossip",
      receivedCount = 4,
      userRepliedCount = 0,
    )
    assertFalse(
      GroupRelevanceScorer.shouldSuppressLowEngagement(
        engagement = newGroup,
        isGroupConversation = true,
        isUserMentioned = false,
      ),
    )

    val silentNoisyGroup = newGroup.copy(receivedCount = 25, userRepliedCount = 0)
    assertTrue(
      GroupRelevanceScorer.shouldSuppressLowEngagement(
        engagement = silentNoisyGroup,
        isGroupConversation = true,
        isUserMentioned = false,
      ),
    )
    assertFalse(
      GroupRelevanceScorer.shouldSuppressLowEngagement(
        engagement = silentNoisyGroup,
        isGroupConversation = true,
        isUserMentioned = true,
      ),
    )

    val activeGroup = newGroup.copy(receivedCount = 25, userRepliedCount = 3)
    assertFalse(
      GroupRelevanceScorer.shouldSuppressLowEngagement(
        engagement = activeGroup,
        isGroupConversation = true,
        isUserMentioned = false,
      ),
    )
  }

  @Test
  fun messageBurstAggregator_debounces3_5SecondsPerThreadAndMergesLines() = runTest {
    val flushed = mutableListOf<ExtractedNotification>()
    val aggregator = MessageBurstAggregator(
      scope = this,
      debounceMs = 3_500L,
      onFlush = { flushed += it },
    )

    fun msg(text: String, ts: Long) = ExtractedNotification(
      captured = CapturedNotification(
        sbnKey = "0|com.whatsapp|1|null|1000",
        packageName = "com.whatsapp",
        appLabel = "WhatsApp",
        title = "Arjun",
        sender = "Arjun",
        body = "Arjun: $text",
        threadMessages = listOf(ThreadMessage("Arjun", text, ts)),
        postTimeMs = ts,
        hasMessagingStyle = true,
      ),
      replyHandle = null,
    )

    aggregator.submit(msg("da", 1_000L))
    advanceTimeBy(1_500L)
    runCurrent()
    assertTrue(flushed.isEmpty())

    aggregator.submit(msg("evideya", 2_500L))
    advanceTimeBy(2_000L)
    runCurrent()
    // Reset happened at t=1500ms, so 2000ms later only 2.0s elapsed -> still waiting!
    assertTrue(flushed.isEmpty())

    advanceTimeBy(1_600L)
    runCurrent()
    assertEquals(1, flushed.size)
    val merged = flushed.single().captured
    assertEquals(2, merged.threadMessages.size)
    assertTrue(merged.body.contains("Arjun: da"))
    assertTrue(merged.body.contains("Arjun: evideya"))
  }
}
