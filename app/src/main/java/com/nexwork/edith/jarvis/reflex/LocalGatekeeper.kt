package com.nexwork.edith.jarvis.reflex

import android.app.Notification
import java.security.MessageDigest

/**
 * Lightweight notification metadata evaluated by [LocalGatekeeper] before persistence or LLM calls.
 */
data class GatekeeperCandidate(
  val packageName: String,
  val title: String,
  val sender: String,
  val body: String,
  val flags: Int,
  val isOngoing: Boolean,
  val hasMessagingStyle: Boolean,
  val hasRemoteInput: Boolean,
)

enum class IgnoreReason {
  SELF_PACKAGE,
  ONGOING,
  GROUP_SUMMARY,
  EMPTY_CONTENT,
  DUPLICATE_10S,
}

sealed interface GatekeeperDecision {
  data class Allow(val contentHash: String) : GatekeeperDecision
  data class Ignore(val reason: IgnoreReason, val contentHash: String? = null) : GatekeeperDecision
}

/**
 * Reflex filter that rejects self/ongoing/empty notifications and deduplicates identical content
 * within a rolling 10-second window so bursty Android re-posts never spam SQLite or Gemini.
 */
class LocalGatekeeper(
  private val selfPackageName: String,
  private val windowMs: Long = DEFAULT_WINDOW_MS,
  private val maxTrackedHashes: Int = DEFAULT_MAX_HASHES,
  private val clockMs: () -> Long = System::currentTimeMillis,
) {

  private val lock = Any()
  private val recentHashes = LinkedHashMap<String, Long>(64, 0.75f, true)

  fun evaluate(candidate: GatekeeperCandidate): GatekeeperDecision {
    if (candidate.packageName == selfPackageName) {
      return GatekeeperDecision.Ignore(IgnoreReason.SELF_PACKAGE)
    }

    val ongoingFlag = (candidate.flags and Notification.FLAG_ONGOING_EVENT) != 0
    val fgsFlag = (candidate.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
    if (candidate.isOngoing || ongoingFlag || fgsFlag) {
      return GatekeeperDecision.Ignore(IgnoreReason.ONGOING)
    }

    val isGroupSummary = (candidate.flags and Notification.FLAG_GROUP_SUMMARY) != 0
    if (isGroupSummary && !candidate.hasMessagingStyle && !candidate.hasRemoteInput) {
      return GatekeeperDecision.Ignore(IgnoreReason.GROUP_SUMMARY)
    }

    val hasContent = candidate.title.isNotBlank() ||
      candidate.sender.isNotBlank() ||
      candidate.body.isNotBlank()
    if (!hasContent) {
      return GatekeeperDecision.Ignore(IgnoreReason.EMPTY_CONTENT)
    }

    val hash = computeContentHash(
      packageName = candidate.packageName,
      title = candidate.title,
      sender = candidate.sender,
      body = candidate.body,
    )

    val now = clockMs()
    synchronized(lock) {
      pruneExpiredLocked(now)
      val seenAt = recentHashes[hash]
      if (seenAt != null && (now - seenAt) < windowMs) {
        return GatekeeperDecision.Ignore(IgnoreReason.DUPLICATE_10S, hash)
      }
      recentHashes[hash] = now
      trimCapacityLocked()
    }

    return GatekeeperDecision.Allow(hash)
  }

  fun clear() {
    synchronized(lock) {
      recentHashes.clear()
    }
  }

  private fun pruneExpiredLocked(now: Long) {
    val iter = recentHashes.entries.iterator()
    while (iter.hasNext()) {
      val entry = iter.next()
      if ((now - entry.value) >= windowMs) {
        iter.remove()
      }
    }
  }

  private fun trimCapacityLocked() {
    while (recentHashes.size > maxTrackedHashes) {
      val eldest = recentHashes.entries.firstOrNull() ?: break
      recentHashes.remove(eldest.key)
    }
  }

  companion object {
    const val DEFAULT_WINDOW_MS = 10_000L
    private const val DEFAULT_MAX_HASHES = 256
    private val WHITESPACE_REGEX = Regex("""\s+""")

    internal fun computeContentHash(
      packageName: String,
      title: String,
      sender: String,
      body: String,
    ): String {
      val normalized = buildString(128) {
        append(packageName.trim().lowercase())
        append('|')
        append(normalizeToken(title))
        append('|')
        append(normalizeToken(sender))
        append('|')
        append(normalizeToken(body))
      }
      val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
      return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun normalizeToken(raw: String): String =
      raw.trim().replace(WHITESPACE_REGEX, " ")
  }
}
