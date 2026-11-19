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
  @SerialName("extracted_destination")
  val extractedDestination: String = "",
  @SerialName("valid_from_epoch")
  val validFromEpochMs: Long = 0L,
  @SerialName("valid_until_epoch")
  val validUntilEpochMs: Long = 0L,
  @SerialName("event_title")
  val eventTitle: String = "",
  @SerialName("event_start_epoch")
  val eventStartEpochMs: Long = 0L,
  @SerialName("event_end_epoch")
  val eventEndEpochMs: Long = 0L,
  @SerialName("event_location")
  val eventLocation: String = "",
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
  val isGroupConversation: Boolean = false,
  val isUserMentioned: Boolean = false,
  val selfSentSamples: List<String> = emptyList(),
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

enum class EventSource(val label: String) {
  MESSAGE("MSG"),
  CALENDAR("CAL"),
  MANUAL("MANUAL"),
}

enum class EventStatus {
  ACTIVE,
  RESOLVED,
  DISMISSED,
}

/**
 * Planned event extracted from an incoming message, read from the device calendar,
 * or entered manually by the user.
 */
data class ScheduledEvent(
  val id: Long = 0L,
  val title: String,
  val startEpochMs: Long,
  val endEpochMs: Long,
  val location: String = "",
  val source: EventSource = EventSource.MESSAGE,
  val sender: String = "",
  val sourceSummary: String = "",
  val status: EventStatus = EventStatus.ACTIVE,
  val createdAtMs: Long = System.currentTimeMillis(),
)

enum class ClashSeverity(val label: String) {
  HARD_OVERLAP("OVERLAP"),
  TIGHT_TRANSITION("TIGHT"),
}

/**
 * Pairwise schedule conflict detected between two active [ScheduledEvent] entries.
 */
data class EventClash(
  val clashId: String,
  val existingEvent: ScheduledEvent,
  val incomingEvent: ScheduledEvent,
  val overlapMinutes: Int,
  val gapMinutes: Int,
  val severity: ClashSeverity,
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
 * Coarse physical activity classification mapped from [ActivityRecognitionClient] transitions.
 */
enum class MotionState {
  UNKNOWN,
  IN_VEHICLE,
  WALKING,
  STILL,
}

/**
 * Known saved Wi-Fi zone profile.
 */
enum class PlaceTag {
  NONE,
  HOME,
  CAMPUS,
}

/**
 * Immutable passive ambient context snapshot emitted by [com.nexwork.edith.jarvis.senses.PassiveContextEngine]
 * after 45-second transition debouncing.
 */
data class AmbientContext(
  val motionState: MotionState = MotionState.UNKNOWN,
  val previousMotionState: MotionState = MotionState.UNKNOWN,
  val wifiSsid: String? = null,
  val placeTag: PlaceTag = PlaceTag.NONE,
  val focusQuietActive: Boolean = false,
  val bluetoothConnectedCount: Int = 0,
  val isScreenInteractive: Boolean = true,
  val lastUnlockMs: Long = 0L,
  val batteryPct: Int = 100,
  val isCharging: Boolean = false,
  val motionSinceMs: Long = 0L,
  val awayDwellSinceMs: Long? = null,
  val updatedAtMs: Long = 0L,
)

/**
 * User-tagged SSID profile stored in `saved_places` to classify HOME vs CAMPUS zones.
 */
data class SavedPlaceProfile(
  val id: Long = 0L,
  val ssidNormalized: String,
  val displaySsid: String,
  val placeTag: PlaceTag,
  val autoFocusQuiet: Boolean = true,
  val updatedAtMs: Long = 0L,
)

/**
 * Destination harvested from an incoming message by [com.nexwork.edith.jarvis.brain.GeminiClient]
 * with a 24-hour expiration TTL stored in `upcoming_destinations`.
 */
data class UpcomingDestination(
  val id: Long = 0L,
  val placeName: String,
  val sourceSummary: String,
  val expiresAtMs: Long,
  val used: Boolean = false,
  val createdAtMs: Long = 0L,
  val validFromEpochMs: Long = 0L,
  val validUntilEpochMs: Long = expiresAtMs,
)

/**
 * Learned per-contact style & relationship profile passively populated from the user's own
 * outgoing replies, stored in `contact_personas`.
 */
data class ContactPersona(
  val id: Long = 0L,
  val contactKey: String,
  val displayName: String,
  val packageName: String = "",
  val relationshipTag: String = "general",
  val sentSamples: List<String> = emptyList(),
  val updatedAtMs: Long = 0L,
)

/**
 * Group chat engagement counters stored in `group_engagement` to silently suppress
 * low-engagement noisy groups unless the user is explicitly mentioned.
 */
data class GroupEngagementRecord(
  val id: Long = 0L,
  val groupKey: String,
  val packageName: String,
  val groupName: String,
  val receivedCount: Int = 0,
  val userRepliedCount: Int = 0,
  val lastReceivedAtMs: Long = 0L,
  val lastRepliedAtMs: Long = 0L,
)

/**
 * Proactive launcher pill / fallback notification action surfaced by [com.nexwork.edith.jarvis.reflex.ProactiveEngine].
 */
sealed interface ProactiveSuggestion {
  val id: String
  val sourceSummary: String
  val triggeredAtMs: Long

  data class OpenMaps(
    override val id: String,
    val destinationId: Long,
    val placeName: String,
    override val sourceSummary: String,
    override val triggeredAtMs: Long = System.currentTimeMillis(),
  ) : ProactiveSuggestion

  data class ScanUpiQr(
    override val id: String,
    val placeLabel: String,
    val dwellMinutes: Long,
    override val sourceSummary: String,
    override val triggeredAtMs: Long = System.currentTimeMillis(),
  ) : ProactiveSuggestion

  data class EventClashAlert(
    override val id: String,
    val clash: EventClash,
    override val sourceSummary: String,
    override val triggeredAtMs: Long = System.currentTimeMillis(),
  ) : ProactiveSuggestion
}

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
  val shizukuRunning: Boolean = false,
  val shizukuAuthorized: Boolean = false,
  val hasLocationPermission: Boolean = false,
  val hasBackgroundLocationPermission: Boolean = false,
  val hasBluetoothConnectPermission: Boolean = false,
  val hasActivityRecognitionPermission: Boolean = false,
  val hasCalendarPermission: Boolean = false,
  val batteryUnrestricted: Boolean = false,
  val hasApiKey: Boolean = false,
  val maskedApiKey: String = "",
  val activeReplyHandleCount: Int = 0,
  val personaCount: Int = 0,
  val suppressedGroupCount: Int = 0,
  val isProcessing: Boolean = false,
  val lastStatusBanner: String? = null,
  val lastError: String? = null,
  val lastExecutionLog: List<String> = emptyList(),
  val pendingTier3Approval: Tier3ApprovalRequest? = null,
  val ambientContext: AmbientContext = AmbientContext(),
  val savedPlaces: List<SavedPlaceProfile> = emptyList(),
  val upcomingDestinations: List<UpcomingDestination> = emptyList(),
  val proactiveSuggestion: ProactiveSuggestion? = null,
  val scheduledEvents: List<ScheduledEvent> = emptyList(),
  val eventClashes: List<EventClash> = emptyList(),
  val events: List<JarvisEventRecord> = emptyList(),
  val rules: List<JarvisRule> = emptyList(),
  val trustedContacts: List<TrustedContact> = emptyList(),
)
