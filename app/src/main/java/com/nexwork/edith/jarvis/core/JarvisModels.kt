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
 * Filtered UI node captured on-demand by [com.nexwork.edith.jarvis.senses.JarvisAccessibilityService].
 */
data class CompactUiNode(
  val id: Int,
  val className: String,
  val viewId: String = "",
  val text: String = "",
  val contentDesc: String = "",
  val clickable: Boolean = false,
  val editable: Boolean = false,
  val scrollable: Boolean = false,
  val left: Int = 0,
  val top: Int = 0,
  val right: Int = 0,
  val bottom: Int = 0,
) {
  val centerX: Int get() = (left + right) / 2
  val centerY: Int get() = (top + bottom) / 2
}

/**
 * Compact active-window snapshot handed to [com.nexwork.edith.jarvis.reflex.PlaybookExecutor]
 * and [com.nexwork.edith.jarvis.brain.PlannerClient].
 */
data class CompactUiTree(
  val packageName: String,
  val windowTitle: String = "",
  val nodes: List<CompactUiNode> = emptyList(),
  val formattedText: String = "",
  val blockedReason: String? = null,
)

/**
 * Parameterized step persisted in SQLite `ui_playbooks` for zero-LLM local replay.
 */
@Serializable
data class PlaybookStep(
  val action: String,
  val targetText: String? = null,
  val targetDesc: String? = null,
  val viewId: String? = null,
  val inputTemplate: String? = null,
  val scrollForward: Boolean = true,
  val packageName: String? = null,
)

/**
 * Saved UI automation workflow stored in `ui_playbooks`.
 */
data class UiPlaybook(
  val id: Long = 0L,
  val skillId: String,
  val triggerIntent: String,
  val targetPackage: String,
  val steps: List<PlaybookStep>,
  val stepsJson: String,
  val successCount: Int = 0,
  val createdAtMs: Long = 0L,
  val updatedAtMs: Long = 0L,
)

/**
 * Per-contact auto-reply allowlist record stored in `trusted_contacts`.
 */
data class TrustedContact(
  val id: Long = 0L,
  val contactKey: String,
  val displayName: String,
  val packageName: String = "",
  val autoReplyEnabled: Boolean = true,
  val maxTier: Int = 2,
  val createdAtMs: Long = 0L,
  val updatedAtMs: Long = 0L,
)

/**
 * Pending Tier-3 action suspended awaiting explicit hardware BiometricPrompt confirmation.
 */
data class Tier3ApprovalRequest(
  val requestId: String,
  val actionTitle: String,
  val actionSummary: String,
  val reason: String,
  val targetPackage: String = "",
  val contactName: String = "",
  val createdAtMs: Long = System.currentTimeMillis(),
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
  val accessibilityEnabled: Boolean = false,
  val accessibilityConnected: Boolean = false,
  val batteryUnrestricted: Boolean = false,
  val hasApiKey: Boolean = false,
  val maskedApiKey: String = "",
  val activeReplyHandleCount: Int = 0,
  val isProcessing: Boolean = false,
  val isExecutingCommand: Boolean = false,
  val lastStatusBanner: String? = null,
  val lastError: String? = null,
  val lastExecutionLog: List<String> = emptyList(),
  val pendingTier3Approval: Tier3ApprovalRequest? = null,
  val events: List<JarvisEventRecord> = emptyList(),
  val rules: List<JarvisRule> = emptyList(),
  val playbooks: List<UiPlaybook> = emptyList(),
  val trustedContacts: List<TrustedContact> = emptyList(),
)
