package com.nexwork.edith.jarvis.reflex

import com.nexwork.edith.jarvis.core.CapturedNotification
import com.nexwork.edith.jarvis.core.ThreadMessage
import com.nexwork.edith.jarvis.senses.ExtractedNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces rapid-fire incoming messages on the same chat thread for [debounceMs] (3.5s default)
 * so bursts like 4 back-to-back lines collapse into a single consolidated Gemini classification.
 */
class MessageBurstAggregator(
  private val scope: CoroutineScope,
  private val debounceMs: Long = DEFAULT_BURST_DEBOUNCE_MS,
  private val onFlush: suspend (ExtractedNotification) -> Unit,
) {

  private data class PendingBurst(
    var items: MutableList<ExtractedNotification>,
    var timerJob: Job? = null,
  )

  private val lock = Any()
  private val pendingByThread = HashMap<String, PendingBurst>()

  fun submit(extracted: ExtractedNotification) {
    val threadKey = buildThreadKey(extracted.captured)
    synchronized(lock) {
      val bucket = pendingByThread.getOrPut(threadKey) {
        PendingBurst(items = mutableListOf())
      }
      bucket.items.add(extracted)
      bucket.timerJob?.cancel()
      bucket.timerJob = scope.launch {
        try {
          delay(debounceMs)
          val flushed = synchronized(lock) {
            pendingByThread.remove(threadKey)?.items?.toList().orEmpty()
          }
          if (flushed.isNotEmpty()) {
            onFlush(mergeBurst(flushed))
          }
        } catch (c: CancellationException) {
          throw c
        }
      }
    }
  }

  fun cancelAll() {
    val jobs = synchronized(lock) {
      val list = pendingByThread.values.mapNotNull { it.timerJob }
      pendingByThread.clear()
      list
    }
    jobs.forEach { it.cancel() }
  }

  companion object {
    const val DEFAULT_BURST_DEBOUNCE_MS = 3_500L
    private const val MAX_MERGED_THREAD_LINES = 12

    internal fun buildThreadKey(captured: CapturedNotification): String {
      val pkg = captured.packageName.trim().lowercase()
      val threadLabel = captured.title.ifBlank { captured.sender }.trim().lowercase()
      return "$pkg|$threadLabel"
    }

    internal fun mergeBurst(burst: List<ExtractedNotification>): ExtractedNotification {
      require(burst.isNotEmpty())
      if (burst.size == 1) return burst.first()

      val latest = burst.last()
      val mergedThread = LinkedHashMap<String, ThreadMessage>()
      val looseBodies = LinkedHashSet<String>()
      val mergedSelfSamples = LinkedHashSet<String>()

      for (item in burst) {
        val cap = item.captured
        cap.selfSentSamples.forEach { if (it.isNotBlank()) mergedSelfSamples += it }
        if (cap.threadMessages.isNotEmpty()) {
          for (tm in cap.threadMessages) {
            val lineKey = "${tm.sender.trim().lowercase()}|${tm.text.trim()}"
            mergedThread[lineKey] = tm
          }
        } else if (cap.body.isNotBlank()) {
          looseBodies += cap.body.trim()
        }
      }

      val threadList = mergedThread.values.toList().takeLast(MAX_MERGED_THREAD_LINES)
      val mergedBody = buildString {
        if (threadList.isNotEmpty()) {
          if (latest.captured.isGroupConversation && latest.captured.title.isNotBlank()) {
            append("[").append(latest.captured.title).append("]\n")
          }
          threadList.forEachIndexed { idx, tm ->
            if (idx > 0) append('\n')
            append(tm.sender).append(": ").append(tm.text)
          }
          if (looseBodies.isNotEmpty()) {
            looseBodies.forEach { line ->
              append('\n').append(line)
            }
          }
        } else {
          append(looseBodies.joinToString("\n"))
        }
      }.ifBlank { latest.captured.body }

      val latestHandle = burst.asReversed().firstNotNullOfOrNull { it.replyHandle }
      val mergedCaptured = latest.captured.copy(
        body = mergedBody,
        threadMessages = threadList,
        postTimeMs = burst.maxOf { it.captured.postTimeMs },
        hasRemoteInput = burst.any { it.captured.hasRemoteInput } || latestHandle != null,
        isGroupConversation = burst.any { it.captured.isGroupConversation },
        isUserMentioned = burst.any { it.captured.isUserMentioned },
        selfSentSamples = mergedSelfSamples.toList().takeLast(6),
      )
      return ExtractedNotification(
        captured = mergedCaptured,
        replyHandle = latestHandle,
      )
    }
  }
}
