package com.nexwork.edith.jarvis.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured classification returned by the Quarantined Reader Gemini triage model.
 */
@Serializable
data class NotificationTriage(
  val sender: String = "",
  val intent: String = "other",
  val urgency: String = "LOW",
  @SerialName("suggested_reply")
  val suggestedReply: String = "",
)

/**
 * Single message item extracted from a Notification MessagingStyle thread.
 */
@Serializable
data class ThreadMessage(
  val sender: String,
  val text: String,
  val timestampMs: Long,
)

/**
 * Normalized snapshot of an incoming StatusBarNotification captured by Jarvis senses.
 */
data class CapturedNotification(
  val sbnKey: String,
  val packageName: String,
  val appLabel: String,
  val title: String,
  val sender: String,
  val body: String,
  val threadMessages: List<ThreadMessage> = emptyList(),
  val postTimeMs: Long = System.currentTimeMillis(),
  val flags: Int = 0,
  val isOngoing: Boolean = false,
  val hasMessagingStyle: Boolean = false,
  val hasRemoteInput: Boolean = false,
)

/**
 * Processing lifecycle state of a logged notification event.
 */
enum class TriageStatus {
  PENDING,
  TRIAGED,
  SKIPPED_NO_KEY,
  FAILED,
}

/**
 * Persisted and UI-observable record of a notification event and its Gemini triage.
 */
data class JarvisEventRecord(
  val id: Long,
  val sbnKey: String,
  val packageName: String,
  val appLabel: String,
  val sender: String,
  val title: String,
  val body: String,
  val threadMessages: List<ThreadMessage>,
  val contentHash: String,
  val hasRemoteInput: Boolean,
  val canReplyNow: Boolean,
  val triageStatus: TriageStatus,
  val triage: NotificationTriage?,
  val triageJson: String?,
  val errorMessage: String?,
  val replySent: String?,
  val createdAtMs: Long,
  val triagedAtMs: Long?,
)

/**
 * User-defined classification or handling rule stored in SQLite user_rulebook.
 */
data class JarvisRule(
  val id: Long = 0L,
  val ruleKey: String,
  val ruleType: String,
  val pattern: String,
  val instruction: String,
  val enabled: Boolean = true,
  val createdAtMs: Long = 0L,
  val updatedAtMs: Long = 0L,
)

/**
 * Live sticky foreground service state exposed via StateFlow.
 */
data class JarvisServiceStatus(
  val isRunning: Boolean = false,
  val startedAtMs: Long? = null,
  val processedCount: Int = 0,
  val lastEventSummary: String? = null,
  val lastTaskRemovedAtMs: Long? = null,
  val lastError: String? = null,
)

/**
 * Aggregated state consumed by the Jarvis Baseplate Compose UI.
 */
data class JarvisUiState(
  val initialized: Boolean = false,
  val serviceStatus: JarvisServiceStatus = JarvisServiceStatus(),
  val notificationListenerEnabled: Boolean = false,
  val notificationListenerConnected: Boolean = false,
  val batteryUnrestricted: Boolean = false,
  val hasApiKey: Boolean = false,
  val maskedApiKey: String = "",
  val activeReplyHandleCount: Int = 0,
  val isProcessing: Boolean = false,
  val lastStatusBanner: String? = null,
  val lastError: String? = null,
  val events: List<JarvisEventRecord> = emptyList(),
  val rules: List<JarvisRule> = emptyList(),
)
