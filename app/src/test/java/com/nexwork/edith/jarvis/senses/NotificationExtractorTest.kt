package com.nexwork.edith.jarvis.senses

import com.nexwork.edith.jarvis.core.CapturedNotification
import com.nexwork.edith.jarvis.core.ThreadMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

  @Test
  fun userMentionAndVoiceNoteDetection_identifyGroupPingsAndVoicePayloads() {
    assertTrue(NotificationExtractor.detectUserMention("Jeswin Joy", "@Jeswin class eppozha?"))
    assertTrue(NotificationExtractor.detectUserMention("Jeswin", "Hey everyone @all check pdf"))
    assertFalse(NotificationExtractor.detectUserMention("You", "Are you coming today?"))
    assertFalse(NotificationExtractor.detectUserMention("Jeswin", "Arjun is on the way"))

    val voiceCap = CapturedNotification(
      sbnKey = "0|com.whatsapp|55|null|1000",
      packageName = "com.whatsapp",
      appLabel = "WhatsApp",
      title = "Arjun",
      sender = "Arjun",
      body = "🎤 Voice message (0:14)",
    )
    assertTrue(VoiceNoteObserver.isVoiceNoteNotification(voiceCap))
    assertFalse(VoiceNoteObserver.isVoiceNoteNotification(voiceCap.copy(body = "Let's meet at 5")))
  }
}
