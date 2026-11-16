package com.nexwork.edith.jarvis.senses

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * In-memory inline reply capability extracted from a notification action and indexed by `sbn.key`.
 */
class CachedReplyHandle(
  val sbnKey: String,
  val packageName: String,
  val actionTitle: String,
  val actionIntent: PendingIntent,
  val remoteInputs: Array<RemoteInput>,
  val primaryResultKey: String,
  val cachedAtMs: Long,
)

/**
 * Thread-safe bounded cache of active [CachedReplyHandle]s keyed by `StatusBarNotification.key`.
 */
class RemoteInputCache(
  private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

  private val lock = Any()
  private val handles = LinkedHashMap<String, CachedReplyHandle>(32, 0.75f, true)

  fun put(handle: CachedReplyHandle) {
    synchronized(lock) {
      handles[handle.sbnKey] = handle
      while (handles.size > maxEntries) {
        val eldest = handles.entries.firstOrNull() ?: break
        handles.remove(eldest.key)
      }
    }
  }

  fun get(sbnKey: String): CachedReplyHandle? = synchronized(lock) { handles[sbnKey] }

  fun hasHandle(sbnKey: String): Boolean = synchronized(lock) { handles.containsKey(sbnKey) }

  fun activeCount(): Int = synchronized(lock) { handles.size }

  fun remove(sbnKey: String): Boolean = synchronized(lock) { handles.remove(sbnKey) != null }

  fun clear() {
    synchronized(lock) { handles.clear() }
  }

  /**
   * Dispatches [replyText] through the cached [RemoteInput] + [PendingIntent] for [sbnKey].
   */
  fun sendReply(context: Context, sbnKey: String, replyText: String): Result<Unit> {
    val trimmed = replyText.trim()
    if (trimmed.isEmpty()) {
      return Result.failure(IllegalArgumentException("Reply text cannot be empty"))
    }
    val handle = get(sbnKey)
      ?: return Result.failure(IllegalStateException("Reply handle expired or unavailable for notification"))

    return runCatching {
      val fillInIntent = Intent()
      val resultsBundle = Bundle().apply {
        putCharSequence(handle.primaryResultKey, trimmed)
      }
      RemoteInput.addResultsToIntent(handle.remoteInputs, fillInIntent, resultsBundle)
      RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
      handle.actionIntent.send(context.applicationContext, 0, fillInIntent)
    }.onFailure { err ->
      if (err is PendingIntent.CanceledException) {
        remove(sbnKey)
      }
    }
  }

  companion object {
    private const val DEFAULT_MAX_ENTRIES = 100
  }
}
