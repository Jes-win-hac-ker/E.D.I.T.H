package com.nexwork.edith.jarvis.reflex

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.nexwork.edith.jarvis.core.ClashSeverity
import com.nexwork.edith.jarvis.core.EventClash
import com.nexwork.edith.jarvis.core.EventSource
import com.nexwork.edith.jarvis.core.EventStatus
import com.nexwork.edith.jarvis.core.ScheduledEvent
import kotlin.math.abs

/**
 * Pure-Kotlin schedule clash detector and read-only Android CalendarContract reader.
 *
 * Detects:
 * 1. [ClashSeverity.HARD_OVERLAP]: two active events whose `[startEpochMs, endEpochMs)` intervals overlap.
 * 2. [ClashSeverity.TIGHT_TRANSITION]: back-to-back events with a gap under [tightGapMinutes] minutes
 *    at different non-empty physical locations.
 */
object EventPlannerEngine {

  const val DEFAULT_TIGHT_GAP_MINUTES = 15
  const val DEFAULT_CALENDAR_HORIZON_MS = 7L * 24L * 60L * 60L * 1000L

  fun detectClashes(
    events: List<ScheduledEvent>,
    tightGapMinutes: Int = DEFAULT_TIGHT_GAP_MINUTES,
    ignoredClashIds: Set<String> = emptySet(),
  ): List<EventClash> {
    val active = events
      .filter { it.status == EventStatus.ACTIVE && it.startEpochMs > 0L && it.endEpochMs > it.startEpochMs }
      .sortedWith(compareBy<ScheduledEvent> { it.startEpochMs }.thenBy { it.endEpochMs }.thenBy { it.id })

    if (active.size < 2) return emptyList()

    val clashes = mutableListOf<EventClash>()
    for (i in 0 until active.size - 1) {
      val earlier = active[i]
      for (j in i + 1 until active.size) {
        val later = active[j]
        // Since active is sorted by startEpochMs, once later starts >= earlier.endEpochMs + tightGap,
        // no subsequent event in the list can overlap or be tight with `earlier`.
        val gapMs = later.startEpochMs - earlier.endEpochMs
        if (gapMs >= tightGapMinutes * 60_000L) break

        // Skip duplicate representations of the exact same event (e.g., message + synced calendar entry).
        if (isSameEvent(earlier, later)) continue

        val clash = if (gapMs < 0L) {
          val overlapEnd = minOf(earlier.endEpochMs, later.endEpochMs)
          val overlapStart = maxOf(earlier.startEpochMs, later.startEpochMs)
          val overlapMin = ((overlapEnd - overlapStart) / 60_000L).toInt().coerceAtLeast(1)
          val id = buildClashId(earlier, later)
          EventClash(
            clashId = id,
            existingEvent = earlier,
            incomingEvent = later,
            overlapMinutes = overlapMin,
            gapMinutes = 0,
            severity = ClashSeverity.HARD_OVERLAP,
          )
        } else {
          val gapMin = (gapMs / 60_000L).toInt()
          if (!hasDifferingLocations(earlier.location, later.location)) continue
          val id = buildClashId(earlier, later)
          EventClash(
            clashId = id,
            existingEvent = earlier,
            incomingEvent = later,
            overlapMinutes = 0,
            gapMinutes = gapMin,
            severity = ClashSeverity.TIGHT_TRANSITION,
          )
        }

        if (clash.clashId !in ignoredClashIds) {
          clashes += clash
        }
      }
    }
    return clashes
  }

  internal fun isSameEvent(a: ScheduledEvent, b: ScheduledEvent): Boolean {
    val startDiff = abs(a.startEpochMs - b.startEpochMs)
    if (startDiff > 5 * 60_000L) return false
    val titleA = a.title.trim().lowercase()
    val titleB = b.title.trim().lowercase()
    return titleA == titleB && (a.source != b.source || a.id == b.id)
  }

  internal fun hasDifferingLocations(locA: String, locB: String): Boolean {
    val a = locA.trim().lowercase()
    val b = locB.trim().lowercase()
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return false
    if (a.contains(b) || b.contains(a)) return false
    return true
  }

  internal fun buildClashId(a: ScheduledEvent, b: ScheduledEvent): String {
    val keyA = if (a.id != 0L) "id${a.id}" else "t${a.title.trim().lowercase().hashCode()}_${a.startEpochMs}"
    val keyB = if (b.id != 0L) "id${b.id}" else "t${b.title.trim().lowercase().hashCode()}_${b.startEpochMs}"
    return if (keyA <= keyB) "${keyA}__${keyB}" else "${keyB}__${keyA}"
  }

  fun hasCalendarPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun readDeviceCalendarEvents(
    context: Context,
    nowMs: Long = System.currentTimeMillis(),
    horizonMs: Long = DEFAULT_CALENDAR_HORIZON_MS,
  ): List<ScheduledEvent> {
    if (!hasCalendarPermission(context)) return emptyList()

    val startWindow = nowMs - 2L * 60L * 60L * 1000L
    val endWindow = nowMs + horizonMs
    val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(uriBuilder, startWindow)
    ContentUris.appendId(uriBuilder, endWindow)

    val projection = arrayOf(
      CalendarContract.Instances.EVENT_ID,
      CalendarContract.Instances.TITLE,
      CalendarContract.Instances.BEGIN,
      CalendarContract.Instances.END,
      CalendarContract.Instances.EVENT_LOCATION,
      CalendarContract.Instances.ALL_DAY,
    )

    val out = mutableListOf<ScheduledEvent>()
    runCatching {
      context.contentResolver.query(
        uriBuilder.build(),
        projection,
        "${CalendarContract.Instances.ALL_DAY} = 0",
        null,
        "${CalendarContract.Instances.BEGIN} ASC",
      )?.use { c ->
        while (c.moveToNext()) {
          val eventId = c.getLong(0)
          val title = c.getString(1)?.trim().orEmpty()
          val beginMs = c.getLong(2)
          val endMs = c.getLong(3)
          val location = c.getString(4)?.trim().orEmpty()
          if (title.isEmpty() || beginMs <= 0L || endMs <= beginMs) continue
          out += ScheduledEvent(
            id = -abs(eventId).coerceAtLeast(1L),
            title = title,
            startEpochMs = beginMs,
            endEpochMs = endMs,
            location = location,
            source = EventSource.CALENDAR,
            sender = "Device Calendar",
            sourceSummary = "Calendar",
            status = EventStatus.ACTIVE,
            createdAtMs = beginMs,
          )
        }
      }
    }
    return out
  }
}
