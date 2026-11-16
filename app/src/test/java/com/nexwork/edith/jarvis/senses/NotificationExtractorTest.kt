package com.nexwork.edith.jarvis.senses

import com.nexwork.edith.jarvis.core.ThreadMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationExtractorTest {

  @Test
  fun buildBodyText_formatsMessagingStyleThreadAndFallsBackCleanly() {
    val thread = listOf(
      ThreadMessage("Maya", "Are you free at 6?", 1000L),
      ThreadMessage("Sam", "Let's confirm soon", 2000L),
    )

    val formattedThread = NotificationExtractor.buildBodyText(
      threadMessages = thread,
      conversationTitle = "Weekend Plans",
      rawBigText = "ignored",
      rawText = "ignored",
      rawLines = emptyList(),
    )
    assertEquals(
      "[Weekend Plans]\nMaya: Are you free at 6?\nSam: Let's confirm soon",
      formattedThread,
    )

    assertEquals(
      "Sam",
      NotificationExtractor.resolveSender(thread, "Weekend Plans", "Title", "WhatsApp"),
    )

    val bigText = NotificationExtractor.buildBodyText(
      threadMessages = emptyList(),
      conversationTitle = "",
      rawBigText = "Full security notice body",
      rawText = "Short preview",
      rawLines = listOf("Line 1", "Line 2"),
    )
    assertEquals("Full security notice body", bigText)

    val inboxLines = NotificationExtractor.buildBodyText(
      threadMessages = emptyList(),
      conversationTitle = "",
      rawBigText = "",
      rawText = "2 new emails",
      rawLines = listOf("Invoice ready", "Shipping update"),
    )
    assertEquals("Invoice ready\nShipping update", inboxLines)
  }

  @Test
  fun remoteInputCache_tracksMissingHandlesSafely() {
    val cache = RemoteInputCache(maxEntries = 10)
    assertFalse(cache.hasHandle("missing_key"))
    assertEquals(0, cache.activeCount())
    assertFalse(cache.remove("missing_key"))
  }
}
