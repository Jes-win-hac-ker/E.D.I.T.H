package com.nexwork.edith.jarvis.senses

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.nexwork.edith.jarvis.core.CapturedNotification
import com.nexwork.edith.jarvis.core.ThreadMessage

/**
 * Pure extraction result for a single notification, pairing normalized content with any inline
 * reply handle found on its actions.
 */
data class ExtractedNotification(
  val captured: CapturedNotification,
  val replyHandle: CachedReplyHandle?,
)

/**
 * Extracts MessagingStyle conversation threads, fallback text payloads, and RemoteInput inline
 * reply handles from Android notifications.
 */
object NotificationExtractor {

  private const val MAX_THREAD_MESSAGES = 6

  fun extract(context: Context, sbn: StatusBarNotification): ExtractedNotification? {
    val notification = sbn.notification ?: return null
    val pkg = sbn.packageName?.takeIf { it.isNotBlank() } ?: return null
    val appLabel = resolveAppLabel(context, pkg)

    val messagingStyle = runCatching {
      NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    }.getOrNull()

    val extras = notification.extras
    val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val rawBigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
    val rawText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    val rawLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
      ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
      .orEmpty()

    val threadMessages = extractThreadMessages(messagingStyle)
    val conversationTitle = messagingStyle?.conversationTitle?.toString()?.trim().orEmpty()

    val sender = resolveSender(
      threadMessages = threadMessages,
      conversationTitle = conversationTitle,
      rawTitle = rawTitle,
      appLabel = appLabel,
    )

    val title = conversationTitle.ifBlank { rawTitle.ifBlank { appLabel } }

    val body = buildBodyText(
      threadMessages = threadMessages,
      conversationTitle = conversationTitle,
      rawBigText = rawBigText,
      rawText = rawText,
      rawLines = rawLines,
    )

    val replyHandle = extractReplyHandle(
      sbnKey = sbn.key,
      packageName = pkg,
      notification = notification,
    )

    val captured = CapturedNotification(
      sbnKey = sbn.key,
      packageName = pkg,
      appLabel = appLabel,
      title = title,
      sender = sender,
      body = body,
      threadMessages = threadMessages,
      postTimeMs = sbn.postTime,
      flags = notification.flags,
      isOngoing = sbn.isOngoing,
      hasMessagingStyle = messagingStyle != null && threadMessages.isNotEmpty(),
      hasRemoteInput = replyHandle != null,
    )

    return ExtractedNotification(
      captured = captured,
      replyHandle = replyHandle,
    )
  }

  internal fun extractThreadMessages(
    messagingStyle: NotificationCompat.MessagingStyle?,
  ): List<ThreadMessage> {
    if (messagingStyle == null) return emptyList()
    return messagingStyle.messages
      .takeLast(MAX_THREAD_MESSAGES)
      .mapNotNull { msg ->
        val text = msg.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return@mapNotNull null
        val author = msg.person?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Contact"
        ThreadMessage(
          sender = author,
          text = text,
          timestampMs = msg.timestamp,
        )
      }
  }

  internal fun resolveSender(
    threadMessages: List<ThreadMessage>,
    conversationTitle: String,
    rawTitle: String,
    appLabel: String,
  ): String {
    val lastSender = threadMessages.lastOrNull()?.sender?.takeIf { it.isNotBlank() }
    return lastSender
      ?: rawTitle.takeIf { it.isNotBlank() }
      ?: conversationTitle.takeIf { it.isNotBlank() }
      ?: appLabel
  }

  internal fun buildBodyText(
    threadMessages: List<ThreadMessage>,
    conversationTitle: String,
    rawBigText: String,
    rawText: String,
    rawLines: List<String>,
  ): String {
    if (threadMessages.isNotEmpty()) {
      return buildString {
        if (conversationTitle.isNotBlank()) {
          append("[").append(conversationTitle).append("]\n")
        }
        threadMessages.forEachIndexed { idx, msg ->
          if (idx > 0) append('\n')
          append(msg.sender).append(": ").append(msg.text)
        }
      }
    }
    if (rawBigText.isNotBlank()) return rawBigText
    if (rawLines.isNotEmpty()) return rawLines.joinToString("\n")
    return rawText
  }

  internal fun extractReplyHandle(
    sbnKey: String,
    packageName: String,
    notification: Notification,
  ): CachedReplyHandle? {
    val actions = notification.actions ?: return null
    val candidates = actions.filter { action ->
      action.actionIntent != null && !action.remoteInputs.isNullOrEmpty()
    }
    if (candidates.isEmpty()) return null

    // Prefer explicit reply semantic actions or free-form RemoteInputs over choice-only inputs.
    val chosen = candidates.firstOrNull { action ->
      action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY &&
        action.remoteInputs?.any { it.allowFreeFormInput } == true
    } ?: candidates.firstOrNull { action ->
      action.remoteInputs?.any { it.allowFreeFormInput } == true
    } ?: candidates.first()

    val remoteInputs = chosen.remoteInputs ?: return null
    val primaryInput = remoteInputs.firstOrNull { it.allowFreeFormInput } ?: remoteInputs.firstOrNull()
      ?: return null
    val actionIntent: PendingIntent = chosen.actionIntent ?: return null

    return CachedReplyHandle(
      sbnKey = sbnKey,
      packageName = packageName,
      actionTitle = chosen.title?.toString()?.ifBlank { "Reply" } ?: "Reply",
      actionIntent = actionIntent,
      remoteInputs = remoteInputs,
      primaryResultKey = primaryInput.resultKey,
      cachedAtMs = System.currentTimeMillis(),
    )
  }

  private fun resolveAppLabel(context: Context, packageName: String): String {
    return runCatching {
      val pm = context.packageManager
      val info = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
      pm.getApplicationLabel(info).toString().ifBlank { packageName }
    }.getOrDefault(packageName)
  }
}
