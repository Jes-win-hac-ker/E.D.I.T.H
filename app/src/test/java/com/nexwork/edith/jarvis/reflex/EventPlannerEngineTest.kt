package com.nexwork.edith.jarvis.reflex

import com.nexwork.edith.jarvis.core.ClashSeverity
import com.nexwork.edith.jarvis.core.EventSource
import com.nexwork.edith.jarvis.core.EventStatus
import com.nexwork.edith.jarvis.core.ScheduledEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPlannerEngineTest {

  private val baseMs = 1_795_000_000_000L
  private val minMs = 60_000L

  @Test
  fun detectClashes_flagsHardOverlapWithMinutes() {
    val existing = ScheduledEvent(
      id = 1L,
      title = "Team Standup",
      startEpochMs = baseMs,
      endEpochMs = baseMs + 60 * minMs,
      location = "Room A",
      source = EventSource.CALENDAR,
    )
    val incoming = ScheduledEvent(
      id = 2L,
      title = "Client Call",
      startEpochMs = baseMs + 30 * minMs,
      endEpochMs = baseMs + 90 * minMs,
      location = "Zoom",
      source = EventSource.MESSAGE,
    )

    val clashes = EventPlannerEngine.detectClashes(listOf(existing, incoming))
    assertEquals(1, clashes.size)
    assertEquals(ClashSeverity.HARD_OVERLAP, clashes[0].severity)
    assertEquals(30, clashes[0].overlapMinutes)
    assertEquals("Team Standup", clashes[0].existingEvent.title)
    assertEquals("Client Call", clashes[0].incomingEvent.title)
  }

  @Test
  fun detectClashes_flagsTightTransitionAcrossDifferentLocations() {
    val first = ScheduledEvent(
      id = 10L,
      title = "Lab Review",
      startEpochMs = baseMs,
      endEpochMs = baseMs + 60 * minMs,
      location = "Indiranagar",
      source = EventSource.MESSAGE,
    )
    val second = ScheduledEvent(
      id = 11L,
      title = "Coffee with Alex",
      startEpochMs = baseMs + 70 * minMs, // 10 min gap
      endEpochMs = baseMs + 130 * minMs,
      location = "Koramangala",
      source = EventSource.MESSAGE,
    )

    val clashes = EventPlannerEngine.detectClashes(listOf(first, second))
    assertEquals(1, clashes.size)
    assertEquals(ClashSeverity.TIGHT_TRANSITION, clashes[0].severity)
    assertEquals(10, clashes[0].gapMinutes)
  }

  @Test
  fun detectClashes_ignoresTightGapAtSameLocationOrBlankLocation() {
    val first = ScheduledEvent(
      id = 20L,
      title = "Sync 1",
      startEpochMs = baseMs,
      endEpochMs = baseMs + 60 * minMs,
      location = "Office",
    )
    val secondSame = ScheduledEvent(
      id = 21L,
      title = "Sync 2",
      startEpochMs = baseMs + 65 * minMs,
      endEpochMs = baseMs + 120 * minMs,
      location = "office",
    )
    val thirdBlank = ScheduledEvent(
      id = 22L,
      title = "Sync 3",
      startEpochMs = baseMs + 125 * minMs,
      endEpochMs = baseMs + 160 * minMs,
      location = "",
    )

    val clashes = EventPlannerEngine.detectClashes(listOf(first, secondSame, thirdBlank))
    assertTrue(clashes.isEmpty())
  }

  @Test
  fun detectClashes_ignoresDismissedEventsAndIgnoredClashIds() {
    val a = ScheduledEvent(
      id = 30L,
      title = "A",
      startEpochMs = baseMs,
      endEpochMs = baseMs + 60 * minMs,
      status = EventStatus.DISMISSED,
    )
    val b = ScheduledEvent(
      id = 31L,
      title = "B",
      startEpochMs = baseMs + 15 * minMs,
      endEpochMs = baseMs + 75 * minMs,
      status = EventStatus.ACTIVE,
    )
    assertTrue(EventPlannerEngine.detectClashes(listOf(a, b)).isEmpty())

    val aActive = a.copy(status = EventStatus.ACTIVE)
    val initial = EventPlannerEngine.detectClashes(listOf(aActive, b))
    assertEquals(1, initial.size)
    val afterIgnore = EventPlannerEngine.detectClashes(
      listOf(aActive, b),
      ignoredClashIds = setOf(initial[0].clashId),
    )
    assertTrue(afterIgnore.isEmpty())
  }
}
