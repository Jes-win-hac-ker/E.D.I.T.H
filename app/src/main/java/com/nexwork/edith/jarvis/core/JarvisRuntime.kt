package com.nexwork.edith.jarvis.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nexwork.edith.MainActivity
import com.nexwork.edith.R
import com.nexwork.edith.jarvis.brain.GeminiClient
import com.nexwork.edith.jarvis.brain.JarvisLanguagePrompt
import com.nexwork.edith.jarvis.brain.PlannerClient
import com.nexwork.edith.jarvis.memory.JarvisDatabase
import com.nexwork.edith.jarvis.reflex.CommandExecutionResult
import com.nexwork.edith.jarvis.reflex.DestinationStore
import com.nexwork.edith.jarvis.reflex.GatekeeperCandidate
import com.nexwork.edith.jarvis.reflex.GatekeeperDecision
import com.nexwork.edith.jarvis.reflex.GroupRelevanceScorer
import com.nexwork.edith.jarvis.reflex.LocalGatekeeper
import com.nexwork.edith.jarvis.reflex.MessageBurstAggregator
import com.nexwork.edith.jarvis.reflex.PlayServicesOneShotLocationVerifier
import com.nexwork.edith.jarvis.reflex.PlaybookEnvironment
import com.nexwork.edith.jarvis.reflex.PlaybookExecutor
import com.nexwork.edith.jarvis.reflex.PlaybookStore
import com.nexwork.edith.jarvis.reflex.ProactiveEngine
import com.nexwork.edith.jarvis.security.DenyCode
import com.nexwork.edith.jarvis.security.JarvisActionType
import com.nexwork.edith.jarvis.security.SecurityActionRequest
import com.nexwork.edith.jarvis.security.SecurityGateway
import com.nexwork.edith.jarvis.security.SecurityVerdict
import com.nexwork.edith.jarvis.senses.ExtractedNotification
import com.nexwork.edith.jarvis.senses.JarvisAccessibilityService
import com.nexwork.edith.jarvis.senses.JarvisNotificationService
import com.nexwork.edith.jarvis.senses.PassiveContextEngine
import com.nexwork.edith.jarvis.senses.RemoteInputCache
import com.nexwork.edith.jarvis.senses.VoiceNoteObserver
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Isolated Milestone 1 Jarvis coordinator.
 *
 * All I/O, Keystore decryption, SQLite WAL writes, and Gemini HTTP calls execute inside a
 * dedicated `SupervisorJob() + Dispatchers.IO` scope with a top-level [CoroutineExceptionHandler]
 * so background faults can never crash the launcher UI.
 */
object JarvisRuntime {

  private const val PROACTIVE_CHANNEL_ID = "jarvis_proactive_channel"
  private const val PROACTIVE_NOTIFICATION_ID = 4110
  private const val JARVIS_PREFS_NAME = "jarvis_runtime_prefs"
  private const val KEY_OPUS_VOICE_READER = "opus_voice_reader_enabled"

  private val firstFrameScheduled = AtomicBoolean(false)
  private val initialized = AtomicBoolean(false)
  private val observersStarted = AtomicBoolean(false)
  private val contextCollectorsStarted = AtomicBoolean(false)
  private val componentLock = Any()

  private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    val msg = throwable.message ?: throwable::class.java.simpleName
    JarvisForegroundService.recordError(msg)
    _uiState.update { it.copy(isProcessing = false, lastError = msg) }
  }

  internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

  val remoteInputCache = RemoteInputCache()

  @Volatile
  private var keyVault: KeyVault? = null

  @Volatile
  private var database: JarvisDatabase? = null

  @Volatile
  private var gatekeeper: LocalGatekeeper? = null

  @Volatile
  private var geminiClient: GeminiClient? = null

  @Volatile
  private var securityGateway: SecurityGateway? = null

  @Volatile
  private var plannerClient: PlannerClient? = null

  @Volatile
  private var playbookExecutor: PlaybookExecutor? = null

  @Volatile
  private var passiveContextEngine: PassiveContextEngine? = null

  @Volatile
  private var proactiveEngine: ProactiveEngine? = null

  @Volatile
  private var burstAggregator: MessageBurstAggregator? = null

  @Volatile
  private var voiceNoteObserver: VoiceNoteObserver? = null

  private val suppressedGroupCount = AtomicInteger(0)

  @Volatile
  private var appContextRef: Context? = null

  @Volatile
  private var launcherInForeground: Boolean = true

  private val approvalLock = Any()
  private var pendingApprovalPair: Pair<String, CompletableDeferred<Boolean>>? = null

  private val jobLock = Any()
  private var activeCommandJob: Job? = null
  private val activeTriageJobs = LinkedHashSet<Job>()

  @Volatile
  private var isOtaBusy: Boolean = false

  private val _isAutomationActive = MutableStateFlow(false)
  val isAutomationActive: StateFlow<Boolean> = _isAutomationActive.asStateFlow()

  private val _isLauncherForegroundFlow = MutableStateFlow(true)
  val isLauncherForegroundFlow: StateFlow<Boolean> = _isLauncherForegroundFlow.asStateFlow()

  private val _emergencyStopEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val emergencyStopEvents: SharedFlow<String> = _emergencyStopEvents.asSharedFlow()

  private val _proactiveSuggestion = MutableStateFlow<ProactiveSuggestion?>(null)
  val proactiveSuggestion: StateFlow<ProactiveSuggestion?> = _proactiveSuggestion.asStateFlow()

  private val _uiState = MutableStateFlow(JarvisUiState())
  val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

  /**
   * Schedules Jarvis initialization and sticky foreground service startup after the launcher's
   * first frame finishes rendering, keeping cold-boot main-thread time minimal.
   */
  fun schedulePostFirstFrame(activity: ComponentActivity) {
    if (!firstFrameScheduled.compareAndSet(false, true)) return
    val appCtx = activity.applicationContext
    Choreographer.getInstance().postFrameCallback {
      activity.window.decorView.post {
        startServiceSafely(appCtx)
        ensureInitialized(appCtx)
      }
    }
  }

  /**
   * Ensures core Jarvis components are hydrated on the isolated IO scope. Safe to invoke from
   * background services or UI entry points.
   */
  fun ensureInitialized(context: Context) {
    val appCtx = context.applicationContext
    startStatusObserversIfNeeded()
    scope.launch {
      ensureComponentsLocked(appCtx)
      if (initialized.compareAndSet(false, true)) {
        keyVault?.warmUp()
        database?.isWalEnabled()
      }
      syncStateSnapshot(appCtx)
    }
  }

  fun startServiceSafely(context: Context) {
    val appCtx = context.applicationContext
    JarvisForegroundService.start(appCtx).onFailure { err ->
      _uiState.update { it.copy(lastError = err.message ?: "Foreground service start blocked") }
    }
    refreshEnvironmentStatus(appCtx)
  }

  fun refreshEnvironmentStatus(context: Context) {
    val appCtx = context.applicationContext
    scope.launch {
      syncEnvironmentOnly(appCtx)
    }
  }

  fun saveApiKey(context: Context, rawKey: String) {
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val vault = keyVault ?: return@launch
      vault.saveApiKey(rawKey)
        .onSuccess {
          val present = vault.hasApiKey.value
          _uiState.update {
            it.copy(
              hasApiKey = present,
              maskedApiKey = vault.maskedKeyPreview.value,
              lastError = null,
              lastStatusBanner = if (present) "API key sealed in KeyVault" else "API key cleared",
            )
          }
        }
        .onFailure { err ->
          _uiState.update { it.copy(lastError = err.message ?: "Failed to seal API key") }
        }
    }
  }

  fun clearApiKey(context: Context) {
    saveApiKey(context, "")
  }

  fun onNotificationCaptured(context: Context, extracted: ExtractedNotification) {
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val keeper = gatekeeper ?: return@launch
      val db = database ?: return@launch
      val captured = extracted.captured

      val decision = keeper.evaluate(
        GatekeeperCandidate(
          packageName = captured.packageName,
          title = captured.title,
          sender = captured.sender,
          body = captured.body,
          flags = captured.flags,
          isOngoing = captured.isOngoing,
          hasMessagingStyle = captured.hasMessagingStyle,
          hasRemoteInput = captured.hasRemoteInput,
        )
      )

      if (decision is GatekeeperDecision.Ignore) {
        return@launch
      }

      val now = captured.postTimeMs.takeIf { it > 0L } ?: System.currentTimeMillis()

      // Passively harvest user's own replies visible in MessagingStyle history
      if (captured.selfSentSamples.isNotEmpty()) {
        val personaContact = if (captured.isGroupConversation && captured.sender.isNotBlank()) {
          captured.sender
        } else {
          captured.title.ifBlank { captured.sender }
        }
        var savedAny = false
        captured.selfSentSamples.forEach { sample ->
          if (db.recordOutgoingReplySample(personaContact, captured.packageName, sample, nowMs = now)) {
            savedAny = true
          }
        }
        if (captured.isGroupConversation && captured.title.isNotBlank()) {
          db.recordGroupUserReply(captured.title, captured.packageName, nowMs = now)
        }
        if (savedAny) {
          _uiState.update { it.copy(personaCount = db.getContactPersonaCount()) }
        }
      }

      // Group relevance scoring: track incoming volume and silently suppress noisy unmentioned groups
      if (captured.isGroupConversation && captured.title.isNotBlank()) {
        val engagement = db.recordGroupMessageReceived(
          groupName = captured.title,
          packageName = captured.packageName,
          nowMs = now,
        )
        if (
          GroupRelevanceScorer.shouldSuppressLowEngagement(
            engagement = engagement,
            isGroupConversation = true,
            isUserMentioned = captured.isUserMentioned,
          )
        ) {
          val totalSuppressed = suppressedGroupCount.incrementAndGet()
          _uiState.update { it.copy(suppressedGroupCount = totalSuppressed) }
          JarvisForegroundService.recordProcessed("Muted low-engagement group: ${captured.title}")
          return@launch
        }
      }

      extracted.replyHandle?.let { handle ->
        remoteInputCache.put(handle)
      }

      burstAggregator?.submit(extracted)
    }
  }

  private fun processDebouncedNotification(appCtx: Context, extracted: ExtractedNotification) {
    val triageJob = scope.launch(start = CoroutineStart.LAZY) {
      ensureComponentsLocked(appCtx)
      val db = database ?: return@launch
      val captured = extracted.captured
      val now = captured.postTimeMs.takeIf { it > 0L } ?: System.currentTimeMillis()
      val contentHash = LocalGatekeeper.computeContentHash(
        packageName = captured.packageName,
        title = captured.title,
        sender = captured.sender,
        body = captured.body,
      )

      val eventId = db.insertIncomingEvent(
        captured = captured,
        contentHash = contentHash,
        createdAtMs = now,
      )
      syncEventsSnapshot(db)

      val apiKey = keyVault?.getApiKey()
      if (apiKey.isNullOrBlank()) {
        db.updateTriageStatus(
          eventId = eventId,
          status = TriageStatus.SKIPPED_NO_KEY,
          errorMessage = "API key not configured in KeyVault",
        )
        JarvisForegroundService.recordProcessed("${captured.appLabel}: ${captured.sender} (no key)")
        syncEventsSnapshot(db)
        return@launch
      }

      _uiState.update { it.copy(isProcessing = true, lastError = null) }
      try {
        val rules = db.getActiveRules()
        val persona = db.getContactPersona(captured.sender, captured.packageName)
          ?: db.getContactPersona(captured.title, captured.packageName)
        val audioBytes = voiceNoteObserver?.resolveAudioBytesForNotification(appCtx, captured)
        val client = geminiClient ?: return@launch
        val result = client.classifyNotificationCancellable(
          apiKey = apiKey,
          notification = captured,
          rules = rules,
          persona = persona,
          audioOggBytes = audioBytes,
        )
        result
          .onSuccess { triageResult ->
            db.updateTriageResult(
              eventId = eventId,
              triage = triageResult.triage,
              triageJson = triageResult.triageJson,
            )
            val harvestedDest = triageResult.triage.extractedDestination.trim()
            if (harvestedDest.isNotEmpty()) {
              db.insertUpcomingDestination(
                placeName = harvestedDest,
                sourceSummary = "${captured.appLabel} · ${captured.sender}: ${captured.body.take(72)}",
                nowMs = System.currentTimeMillis(),
                validFromEpochMs = triageResult.triage.validFromEpochMs,
                validUntilEpochMs = triageResult.triage.validUntilEpochMs,
              )
              syncProactiveSnapshot(db)
            }
            val quietPrefix = if (passiveContextEngine?.contextState?.value?.focusQuietActive == true) "[QUIET] " else ""
            val voicePrefix = if (audioBytes != null) "[VOICE] " else ""
            JarvisForegroundService.recordProcessed(
              "$quietPrefix$voicePrefix${triageResult.triage.urgency} · ${triageResult.triage.sender}: ${triageResult.triage.intent}",
            )
          }
          .onFailure { err ->
            if (err is CancellationException) throw err
            val msg = err.message ?: "Gemini triage failed"
            db.updateTriageStatus(
              eventId = eventId,
              status = TriageStatus.FAILED,
              errorMessage = msg,
            )
            JarvisForegroundService.recordError(msg)
            _uiState.update { it.copy(lastError = msg) }
          }
      } catch (c: CancellationException) {
        db.updateTriageStatus(
          eventId = eventId,
          status = TriageStatus.FAILED,
          errorMessage = "Triage stopped by user",
        )
        throw c
      } finally {
        val stillProcessing = synchronized(jobLock) {
          activeTriageJobs.removeAll { !it.isActive }
          activeTriageJobs.isNotEmpty()
        }
        _uiState.update { it.copy(isProcessing = stillProcessing) }
        syncEventsSnapshot(db)
      }
    }
    synchronized(jobLock) {
      activeTriageJobs.add(triageJob)
    }
    triageJob.invokeOnCompletion {
      synchronized(jobLock) {
        activeTriageJobs.remove(triageJob)
      }
    }
    triageJob.start()
  }

  fun onNotificationRemoved(sbnKey: String) {
    if (remoteInputCache.remove(sbnKey)) {
      scope.launch {
        database?.let { syncEventsSnapshot(it) }
        _uiState.update { it.copy(activeReplyHandleCount = remoteInputCache.activeCount()) }
      }
    }
  }

  fun sendTestReply(
    context: Context,
    event: JarvisEventRecord,
    replyText: String,
  ) {
    val appCtx = context.applicationContext
    val resolvedText = replyText.trim().ifEmpty {
      event.triage?.suggestedReply?.trim().orEmpty().ifEmpty { "Test reply from Jarvis" }
    }
    scope.launch {
      ensureComponentsLocked(appCtx)
      val gateway = securityGateway
      val db = database
      val trusted = db?.isContactTrusted(event.sender, event.packageName) ?: false
      if (gateway != null) {
        val verdict = gateway.evaluateAndConsume(
          SecurityActionRequest(
            actionType = JarvisActionType.REPLY_NOTIFICATION,
            activePackage = event.packageName,
            targetPackage = event.packageName,
            outboundText = resolvedText,
            contactName = event.sender,
            isTrustedContact = trusted,
          ),
        )
        when (verdict) {
          is SecurityVerdict.Deny -> {
            _uiState.update { it.copy(lastError = verdict.reason, lastStatusBanner = verdict.reason) }
            return@launch
          }
          is SecurityVerdict.RequireBiometric -> {
            val approved = awaitTier3Approval(
              actionTitle = "UNTRUSTED REPLY",
              actionSummary = "Send '$resolvedText' to ${event.sender}",
              reason = verdict.reason,
              targetPackage = event.packageName,
              contactName = event.sender,
            )
            if (!approved) {
              _uiState.update { it.copy(lastError = "Reply blocked: Tier 3 biometric denied") }
              return@launch
            }
          }
          is SecurityVerdict.Allow -> Unit
        }
      }

      val result = remoteInputCache.sendReply(appCtx, event.sbnKey, resolvedText)
      result
        .onSuccess {
          if (trusted) {
            gateway?.recordTrustedAutoReply(event.packageName, event.sender)
          }
          db?.let { store ->
            store.markReplySent(event.id, resolvedText)
            store.recordOutgoingReplySample(event.sender, event.packageName, resolvedText)
            if (event.title.isNotBlank() && !event.title.equals(event.sender, ignoreCase = true)) {
              store.recordGroupUserReply(event.title, event.packageName)
            }
            syncEventsSnapshot(store)
            _uiState.update { it.copy(personaCount = store.getContactPersonaCount()) }
          }
          _uiState.update {
            it.copy(
              activeReplyHandleCount = remoteInputCache.activeCount(),
              lastError = null,
              lastStatusBanner = "Sent reply to ${event.sender} (${event.appLabel})",
            )
          }
        }
        .onFailure { err ->
          val msg = err.message ?: "RemoteInput dispatch failed"
          db?.let { syncEventsSnapshot(it) }
          _uiState.update {
            it.copy(
              activeReplyHandleCount = remoteInputCache.activeCount(),
              lastError = msg,
              lastStatusBanner = msg,
            )
          }
        }
    }
  }

  fun openAccessibilitySettings(context: Context) {
    JarvisAccessibilityService.openAccessibilitySettings(context)
  }

  fun addOrUpdateTrustedContact(
    context: Context,
    displayName: String,
    packageName: String = "",
    autoReplyEnabled: Boolean = true,
  ) {
    val cleanName = displayName.trim()
    if (cleanName.isEmpty()) return
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val db = database ?: return@launch
      db.upsertTrustedContact(cleanName, packageName, autoReplyEnabled)
      syncPlaybooksAndContacts(db)
      _uiState.update {
        it.copy(
          lastError = null,
          lastStatusBanner = "Trusted contact updated: $cleanName",
        )
      }
    }
  }

  fun toggleTrustedContact(context: Context, contactKey: String, enabled: Boolean) {
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val db = database ?: return@launch
      db.setTrustedContactEnabled(contactKey, enabled)
      syncPlaybooksAndContacts(db)
    }
  }

  fun deleteTrustedContact(context: Context, contactKey: String) {
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val db = database ?: return@launch
      db.deleteTrustedContact(contactKey)
      syncPlaybooksAndContacts(db)
    }
  }

  fun executeUiCommand(context: Context, rawCommand: String) {
    val trimmed = rawCommand.trim()
    if (trimmed.isEmpty()) return
    val appCtx = context.applicationContext
    val job = scope.launch(start = CoroutineStart.LAZY) {
      ensureComponentsLocked(appCtx)
      val executor = playbookExecutor ?: return@launch
      val apiKey = keyVault?.getApiKey()
      _uiState.update {
        it.copy(
          isExecutingCommand = true,
          lastError = null,
          lastStatusBanner = "Executing: $trimmed",
        )
      }
      _isAutomationActive.value = true
      JarvisForegroundService.updateRunningTask(appCtx, "PLAYBOOK // ${trimmed.take(36)}")
      try {
        val result: CommandExecutionResult = executor.executeCommand(
          commandText = trimmed,
          apiKey = apiKey,
        )
        database?.let { syncPlaybooksAndContacts(it) }
        _uiState.update {
          it.copy(
            isExecutingCommand = false,
            lastExecutionLog = result.traceLog,
            lastError = if (result.ok) null else result.summary,
            lastStatusBanner = if (result.ok) result.summary else null,
          )
        }
        JarvisForegroundService.recordProcessed("CMD: ${result.summary.take(64)}")
      } catch (c: CancellationException) {
        val reason = c.message?.takeIf { it.isNotBlank() } ?: "Execution stopped by user"
        _uiState.update {
          it.copy(
            isExecutingCommand = false,
            lastExecutionLog = (it.lastExecutionLog + "[STOP] $reason").takeLast(12),
            lastError = null,
            lastStatusBanner = "STOPPED: $reason",
          )
        }
        throw c
      } finally {
        synchronized(jobLock) {
          if (activeCommandJob === coroutineContext[Job]) {
            activeCommandJob = null
          }
        }
        recomputeAutomationActive()
        _uiState.update { state ->
          val cmdStillRunning = synchronized(jobLock) { activeCommandJob?.isActive == true }
          if (cmdStillRunning) state else state.copy(isExecutingCommand = false)
        }
        JarvisForegroundService.updateRunningTask(appCtx, null)
      }
    }
    val previousJob = synchronized(jobLock) {
      val prev = activeCommandJob
      activeCommandJob = job
      prev
    }
    previousJob?.cancel(CancellationException("Superseded by new UI command"))
    recomputeAutomationActive()
    job.start()
  }

  fun setOtaActive(active: Boolean) {
    isOtaBusy = active
  }

  fun isAnyAbortableWorkActive(): Boolean = synchronized(jobLock) {
    activeCommandJob?.isActive == true ||
      activeTriageJobs.any { it.isActive } ||
      synchronized(approvalLock) { pendingApprovalPair != null } ||
      isOtaBusy
  }

  fun stopCommandExecution(context: Context, reason: String = "Playbook execution stopped") {
    val appCtx = context.applicationContext
    val cmdToCancel = synchronized(jobLock) {
      val job = activeCommandJob
      activeCommandJob = null
      job
    }
    cmdToCancel?.cancel(CancellationException(reason))
    cancelPendingTier3Approval()
    recomputeAutomationActive()
    JarvisAccessibilityService.instance()?.abortActiveAutomation(bounceHome = false)
    _uiState.update { current ->
      current.copy(
        isExecutingCommand = false,
        pendingTier3Approval = null,
        lastExecutionLog = (current.lastExecutionLog + "[STOP] $reason").takeLast(12),
        lastError = null,
        lastStatusBanner = "STOPPED: $reason",
      )
    }
    JarvisForegroundService.recordStopped(appCtx, reason)
  }

  fun emergencyStop(
    context: Context,
    reason: String = "Emergency stop triggered",
    bounceHome: Boolean = false,
  ) {
    val appCtx = context.applicationContext
    val (cmdJob, triageSnapshot) = synchronized(jobLock) {
      val cmd = activeCommandJob
      activeCommandJob = null
      val triage = activeTriageJobs.toList()
      activeTriageJobs.clear()
      cmd to triage
    }
    cmdJob?.cancel(CancellationException(reason))
    triageSnapshot.forEach { it.cancel(CancellationException(reason)) }
    burstAggregator?.cancelAll()
    cancelPendingTier3Approval()
    recomputeAutomationActive()
    JarvisAccessibilityService.instance()?.abortActiveAutomation(bounceHome = bounceHome)
    _uiState.update { current ->
      current.copy(
        isExecutingCommand = false,
        isProcessing = false,
        pendingTier3Approval = null,
        lastExecutionLog = (current.lastExecutionLog + "[E-STOP] $reason").takeLast(12),
        lastError = null,
        lastStatusBanner = "STOPPED // $reason",
      )
    }
    JarvisForegroundService.recordStopped(appCtx, reason)
    _emergencyStopEvents.tryEmit(reason)
  }

  fun stopForegroundService(context: Context) {
    val appCtx = context.applicationContext
    emergencyStop(appCtx, reason = "Baseplate FGS stopped", bounceHome = false)
    JarvisForegroundService.stop(appCtx)
  }

  private fun cancelPendingTier3Approval() {
    val deferred = synchronized(approvalLock) {
      val prev = pendingApprovalPair?.second
      pendingApprovalPair = null
      prev
    }
    if (deferred != null && !deferred.isCompleted) {
      deferred.complete(false)
    }
  }

  private fun recomputeAutomationActive() {
    val cmdRunning = synchronized(jobLock) { activeCommandJob?.isActive == true }
    val approvalWaiting = synchronized(approvalLock) { pendingApprovalPair != null }
    _isAutomationActive.value = cmdRunning || approvalWaiting
  }

  fun triggerTier3BiometricTest(context: Context, actionLabel: String = "Authorize Tier 3 Sensitive Action") {
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val approved = awaitTier3Approval(
        actionTitle = "TIER 3 // BIOMETRIC GATE",
        actionSummary = actionLabel,
        reason = "Requires explicit BiometricPrompt verification before execution",
      )
      _uiState.update {
        it.copy(
          lastError = if (approved) null else "Tier 3 confirmation denied/cancelled",
          lastStatusBanner = if (approved) "Tier 3 Biometric approved: $actionLabel" else null,
        )
      }
    }
  }

  fun resolveTier3Approval(requestId: String, approved: Boolean) {
    val deferred = synchronized(approvalLock) {
      val current = pendingApprovalPair
      if (current != null && current.first == requestId) {
        pendingApprovalPair = null
        current.second
      } else {
        null
      }
    }
    _uiState.update { state ->
      if (state.pendingTier3Approval?.requestId == requestId) {
        state.copy(pendingTier3Approval = null)
      } else {
        state
      }
    }
    if (deferred != null && !deferred.isCompleted) {
      deferred.complete(approved)
    }
    recomputeAutomationActive()
  }

  private suspend fun awaitTier3Approval(
    actionTitle: String,
    actionSummary: String,
    reason: String,
    targetPackage: String = "",
    contactName: String = "",
  ): Boolean {
    val reqId = UUID.randomUUID().toString()
    val deferred = CompletableDeferred<Boolean>()
    val previousToCancel: CompletableDeferred<Boolean>? = synchronized(approvalLock) {
      val prev = pendingApprovalPair?.second
      pendingApprovalPair = reqId to deferred
      prev
    }
    previousToCancel?.takeIf { !it.isCompleted }?.complete(false)
    recomputeAutomationActive()

    val req = Tier3ApprovalRequest(
      requestId = reqId,
      actionTitle = actionTitle,
      actionSummary = actionSummary,
      reason = reason,
      targetPackage = targetPackage,
      contactName = contactName,
    )
    _uiState.update { it.copy(pendingTier3Approval = req) }

    return try {
      withTimeoutOrNull(60_000L) { deferred.await() } ?: false
    } finally {
      synchronized(approvalLock) {
        if (pendingApprovalPair?.first == reqId) {
          pendingApprovalPair = null
        }
      }
      _uiState.update { state ->
        if (state.pendingTier3Approval?.requestId == reqId) state.copy(pendingTier3Approval = null) else state
      }
      recomputeAutomationActive()
    }
  }

  fun clearBanner() {
    _uiState.update { it.copy(lastStatusBanner = null, lastError = null) }
  }

  fun setLauncherForeground(context: Context, visible: Boolean) {
    val appCtx = context.applicationContext
    appContextRef = appCtx
    launcherInForeground = visible
    _isLauncherForegroundFlow.value = visible
    if (visible) {
      cancelProactiveNotification(appCtx)
      refreshEnvironmentStatus(appCtx)
    } else {
      val currentSuggestion = _proactiveSuggestion.value
      if (currentSuggestion != null) {
        handleFallbackNotification(appCtx, currentSuggestion)
      }
    }
  }

  fun dismissProactiveSuggestion() {
    proactiveEngine?.dismissSuggestion()
    _proactiveSuggestion.value = null
    _uiState.update { it.copy(proactiveSuggestion = null) }
    appContextRef?.let { cancelProactiveNotification(it) }
  }

  fun executeProactiveSuggestion(context: Context, suggestion: ProactiveSuggestion) {
    val appCtx = context.applicationContext
    cancelProactiveNotification(appCtx)
    when (suggestion) {
      is ProactiveSuggestion.OpenMaps -> {
        scope.launch {
          ensureComponentsLocked(appCtx)
          if (suggestion.destinationId > 0L) {
            proactiveEngine?.consumeOpenMapsSuggestion(suggestion.destinationId)
          } else {
            proactiveEngine?.dismissSuggestion()
          }
          database?.let { syncProactiveSnapshot(it) }
        }
        val mapsIntent = buildMapsNavigationIntent(appCtx, suggestion.placeName)
        runCatching { appCtx.startActivity(mapsIntent) }.onFailure { err ->
          _uiState.update { it.copy(lastError = err.message ?: "Unable to launch Maps navigation") }
        }
      }

      is ProactiveSuggestion.ScanUpiQr -> {
        proactiveEngine?.dismissSuggestion()
        val upiIntent = buildUpiScannerIntent(appCtx)
        if (upiIntent == null) {
          _uiState.update { it.copy(lastError = "No UPI or payment scanner app found on device") }
        } else {
          runCatching { appCtx.startActivity(upiIntent) }.onFailure { err ->
            _uiState.update { it.copy(lastError = err.message ?: "Unable to launch UPI QR scanner") }
          }
        }
      }
    }
  }

  fun savePlaceProfile(
    context: Context,
    ssid: String,
    placeTag: PlaceTag,
    autoFocusQuiet: Boolean = true,
  ) {
    val cleanSsid = ssid.trim()
    if (cleanSsid.isEmpty()) return
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val db = database ?: return@launch
      db.upsertSavedPlace(cleanSsid, placeTag, autoFocusQuiet = autoFocusQuiet)
      val saved = db.getSavedPlaces()
      passiveContextEngine?.updateSavedPlaces(saved)
      val activeContext = passiveContextEngine?.contextState?.value ?: _uiState.value.ambientContext
      _uiState.update {
        it.copy(
          savedPlaces = saved,
          ambientContext = activeContext,
          lastError = null,
          lastStatusBanner = "Saved '$cleanSsid' as ${placeTag.name} (Focus/Quiet=${if (autoFocusQuiet) "ON" else "OFF"})",
        )
      }
    }
  }

  fun deletePlaceProfile(context: Context, ssidNormalized: String) {
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val db = database ?: return@launch
      db.deleteSavedPlace(ssidNormalized)
      val saved = db.getSavedPlaces()
      passiveContextEngine?.updateSavedPlaces(saved)
      val activeContext = passiveContextEngine?.contextState?.value ?: _uiState.value.ambientContext
      _uiState.update {
        it.copy(
          savedPlaces = saved,
          ambientContext = activeContext,
          lastStatusBanner = "Removed saved Wi-Fi profile",
        )
      }
    }
  }

  fun addUpcomingDestinationManual(
    context: Context,
    placeName: String,
    sourceSummary: String = "Manual destination entry",
  ) {
    val clean = placeName.trim()
    if (clean.isEmpty()) return
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val db = database ?: return@launch
      db.insertUpcomingDestination(clean, sourceSummary)
      syncProactiveSnapshot(db)
      _uiState.update { it.copy(lastError = null, lastStatusBanner = "Saved destination (24h TTL): $clean") }
    }
  }

  fun refreshSensorsAfterPermissions(context: Context) {
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      // Re-invoke FGS start so API 34+ dynamically promotes location/connectedDevice FGS types once granted.
      JarvisForegroundService.start(appCtx)
      passiveContextEngine?.refreshSensorsAfterPermissionGrant(appCtx)
      voiceNoteObserver?.startIfPermitted(appCtx)
      syncEnvironmentOnly(appCtx)
    }
  }

  fun audioReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Manifest.permission.READ_MEDIA_AUDIO
    } else {
      Manifest.permission.READ_EXTERNAL_STORAGE
    }

  fun hasMediaAudioPermission(context: Context): Boolean =
    VoiceNoteObserver.hasAudioReadPermission(context)

  fun setOpusVoiceReaderEnabled(context: Context, enabled: Boolean) {
    val appCtx = context.applicationContext
    appCtx.getSharedPreferences(JARVIS_PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_OPUS_VOICE_READER, enabled)
      .apply()
    scope.launch {
      ensureComponentsLocked(appCtx)
      voiceNoteObserver?.setEnabled(appCtx, enabled)
      val hasPerm = VoiceNoteObserver.hasAudioReadPermission(appCtx)
      _uiState.update {
        it.copy(
          opusVoiceReaderEnabled = enabled,
          hasMediaAudioPermission = hasPerm,
          lastError = null,
          lastStatusBanner = if (enabled) {
            if (hasPerm) "OPUS voice-note reader ON" else "OPUS reader ON (awaiting audio permission)"
          } else {
            "OPUS voice-note reader OFF"
          },
        )
      }
    }
  }

  fun simulateTrafficStallTrigger(context: Context, fallbackPlace: String = "Koramangala 4th Block") {
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      val db = database ?: return@launch
      if (db.getActiveUpcomingDestinations().isEmpty()) {
        db.insertUpcomingDestination(
          placeName = fallbackPlace,
          sourceSummary = "Debug WhatsApp trip note: Meet at $fallbackPlace",
        )
        syncProactiveSnapshot(db)
      }
      proactiveEngine?.simulateOpenMapsForDebug(fallbackPlace)
      _uiState.update { it.copy(lastError = null, lastStatusBanner = "Simulated IN_VEHICLE -> STILL Maps trigger") }
    }
  }

  fun simulatePostDiningQrTrigger(context: Context, venueLabel: String = "Truffles Indiranagar") {
    val appCtx = context.applicationContext
    scope.launch {
      ensureComponentsLocked(appCtx)
      proactiveEngine?.simulateScanUpiQrForDebug(venueLabel)
      _uiState.update { it.copy(lastError = null, lastStatusBanner = "Simulated >20m Post-Dining QR trigger") }
    }
  }

  fun buildMapsNavigationIntent(context: Context, placeName: String): Intent {
    val encoded = Uri.encode(placeName)
    val pm = context.packageManager
    val candidates = listOf(
      Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")).setPackage("com.google.android.apps.maps"),
      Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")),
      Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")),
      Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")),
    )
    val chosen = candidates.firstOrNull { it.resolveActivity(pm) != null } ?: candidates.last()
    return chosen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  fun buildUpiScannerIntent(context: Context): Intent? {
    val pm = context.packageManager
    val uriCandidates = listOf(
      "tez://upi/scan",
      "phonepe://scan",
      "paytmmp://scan",
      "upi://pay",
    ).map { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }

    uriCandidates.firstOrNull { it.resolveActivity(pm) != null }?.let { resolved ->
      return resolved.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val packageFallbacks = listOf(
      "com.google.android.apps.nbu.paisa.user",
      "com.phonepe.app",
      "net.one97.paytm",
      "in.org.npci.upiapp",
      "com.google.android.apps.walletnfcrel",
    )
    for (pkg in packageFallbacks) {
      val launch = pm.getLaunchIntentForPackage(pkg)
      if (launch != null) {
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }
    return null
  }

  fun foregroundContextPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      add(Manifest.permission.POST_NOTIFICATIONS)
    }
  }.toTypedArray()

  fun openAppDetailsSettings(context: Context) {
    runCatching {
      val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }
  }

  fun hasFineOrCoarseLocation(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  fun hasBackgroundLocation(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      hasFineOrCoarseLocation(context)
    }
  }

  fun hasBluetoothConnect(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  fun hasActivityRecognition(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  fun isBatteryUnrestricted(context: Context): Boolean {
    return runCatching {
      val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
      pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
  }

  fun openNotificationListenerSettings(context: Context) {
    runCatching {
      val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
          putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, JarvisNotificationService::class.java).flattenToString(),
          )
        }
      } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
      }
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }.onFailure {
      runCatching {
        context.startActivity(
          Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        )
      }
    }
  }

  fun requestBatteryUnrestricted(context: Context) {
    runCatching {
      val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }.onFailure {
      runCatching {
        context.startActivity(
          Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        )
      }
    }
  }

  private fun startStatusObserversIfNeeded() {
    if (!observersStarted.compareAndSet(false, true)) return
    scope.launch {
      JarvisForegroundService.status.collect { svc ->
        _uiState.update { it.copy(serviceStatus = svc) }
      }
    }
    scope.launch {
      JarvisNotificationService.isConnected.collect { connected ->
        _uiState.update { it.copy(notificationListenerConnected = connected) }
      }
    }
    scope.launch {
      JarvisAccessibilityService.isConnected.collect { connected ->
        _uiState.update { it.copy(accessibilityConnected = connected) }
      }
    }
  }

  private fun ensureComponentsLocked(appCtx: Context) {
    appContextRef = appCtx
    synchronized(componentLock) {
      if (keyVault == null) keyVault = KeyVault(appCtx)
      if (database == null) database = JarvisDatabase(appCtx)
      if (gatekeeper == null) gatekeeper = LocalGatekeeper(appCtx.packageName)
      if (geminiClient == null) geminiClient = GeminiClient()
      if (securityGateway == null) securityGateway = SecurityGateway(selfPackageName = appCtx.packageName)
      if (plannerClient == null) plannerClient = PlannerClient()
      if (playbookExecutor == null) {
        val db = database!!
        val gw = securityGateway!!
        val pc = plannerClient!!
        val store = object : PlaybookStore {
          override fun findPlaybooksForIntent(triggerIntent: String, targetPackage: String?): List<UiPlaybook> =
            db.findPlaybooksForIntent(triggerIntent, targetPackage)

          override fun upsertPlaybook(
            skillId: String,
            triggerIntent: String,
            targetPackage: String,
            steps: List<PlaybookStep>,
            incrementSuccess: Boolean,
          ): Long = db.upsertPlaybook(skillId, triggerIntent, targetPackage, steps, incrementSuccess)

          override fun incrementPlaybookSuccess(skillId: String) = db.incrementPlaybookSuccess(skillId)
        }
        val env = object : PlaybookEnvironment {
          override fun currentForegroundPackage(): String =
            JarvisAccessibilityService.instance()?.currentForegroundPackage().orEmpty()

          override fun dumpCompactUiTree(): CompactUiTree {
            val svc = JarvisAccessibilityService.instance()
              ?: return CompactUiTree("", blockedReason = "Accessibility service not connected")
            return svc.dumpCompactUiTree()
          }

          override suspend fun clickNode(id: Int): Result<String> {
            val svc = JarvisAccessibilityService.instance()
              ?: return Result.failure(IllegalStateException("Accessibility service not connected"))
            return svc.clickNode(id)
          }

          override fun inputText(id: Int, text: String): Result<String> {
            val svc = JarvisAccessibilityService.instance()
              ?: return Result.failure(IllegalStateException("Accessibility service not connected"))
            return svc.inputText(id, text)
          }

          override suspend fun scrollNode(id: Int?, forward: Boolean): Result<String> {
            val svc = JarvisAccessibilityService.instance()
              ?: return Result.failure(IllegalStateException("Accessibility service not connected"))
            return svc.scrollNode(id, forward)
          }

          override fun openApp(packageName: String): Result<String> {
            val cleanPkg = packageName.trim()
            if (SecurityGateway.isRestrictedPackage(cleanPkg, appCtx.packageName)) {
              return Result.failure(SecurityException("Blocked restricted package: $cleanPkg"))
            }
            val launchIntent = appCtx.packageManager.getLaunchIntentForPackage(cleanPkg)
              ?: return Result.failure(IllegalArgumentException("No launch intent found for package: $cleanPkg"))
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
              appCtx.startActivity(launchIntent)
              "Launched $cleanPkg"
            }
          }

          override fun sendNotificationReply(sbnKey: String, replyText: String): Result<String> {
            return remoteInputCache.sendReply(appCtx, sbnKey, replyText).map { "Sent notification reply" }
          }

          override fun isContactTrusted(contactName: String, packageName: String): Boolean =
            db.isContactTrusted(contactName, packageName)

          override suspend fun requestTier3BiometricApproval(
            actionTitle: String,
            actionSummary: String,
            reason: String,
            targetPackage: String,
            contactName: String,
          ): Boolean = awaitTier3Approval(actionTitle, actionSummary, reason, targetPackage, contactName)
        }
        playbookExecutor = PlaybookExecutor(
          env = env,
          store = store,
          security = gw,
          planner = pc,
          resolvePersonaContext = { cmdText ->
            val words = cmdText.split(Regex("""\s+""")).map { it.trim(',', '.', '!', '?') }.filter { it.length >= 2 }
            val matched = words.firstNotNullOfOrNull { token -> db.getContactPersona(token) }
            if (matched != null) JarvisLanguagePrompt.formatContactPersona(matched) else ""
          },
        )
      }
      if (burstAggregator == null) {
        burstAggregator = MessageBurstAggregator(
          scope = scope,
          onFlush = { merged -> processDebouncedNotification(appCtx, merged) },
        )
      }
      if (voiceNoteObserver == null) {
        val observer = VoiceNoteObserver()
        val opusOptIn = appCtx.getSharedPreferences(JARVIS_PREFS_NAME, Context.MODE_PRIVATE)
          .getBoolean(KEY_OPUS_VOICE_READER, false)
        observer.setEnabled(appCtx, opusOptIn)
        voiceNoteObserver = observer
      }
      if (passiveContextEngine == null) {
        passiveContextEngine = PassiveContextEngine(scope = scope)
      }
      if (proactiveEngine == null) {
        val db = database!!
        val destStore = object : DestinationStore {
          override fun getActiveUpcomingDestinations(nowMs: Long): List<UpcomingDestination> =
            db.getActiveUpcomingDestinations(nowMs = nowMs)

          override fun markDestinationUsed(id: Long) {
            db.markDestinationUsed(id)
            syncProactiveSnapshot(db)
          }
        }
        proactiveEngine = ProactiveEngine(
          destinationStore = destStore,
          locationVerifier = PlayServicesOneShotLocationVerifier(appCtx),
          onSuggestionUpdated = { next ->
            _proactiveSuggestion.value = next
            _uiState.update { it.copy(proactiveSuggestion = next) }
            handleFallbackNotification(appCtx, next)
          },
        )
      }
    }

    val engine = passiveContextEngine ?: return
    val savedPlaces = runCatching { database?.getSavedPlaces().orEmpty() }.getOrDefault(emptyList())
    engine.start(appCtx, savedPlaces)
    if (contextCollectorsStarted.compareAndSet(false, true)) {
      scope.launch {
        engine.contextState.collect { ambient ->
          _uiState.update { it.copy(ambientContext = ambient) }
        }
      }
      scope.launch {
        engine.signals.collect { signal ->
          proactiveEngine?.onSignal(signal)
        }
      }
    }
  }

  private fun syncStateSnapshot(appCtx: Context) {
    val db = database ?: return
    val vault = keyVault
    val events = runCatching {
      db.getRecentEvents(canReplyChecker = remoteInputCache::hasHandle)
    }.getOrDefault(emptyList())
    val rules = runCatching { db.getActiveRules() }.getOrDefault(emptyList())
    val playbooks = runCatching { db.getAllPlaybooks() }.getOrDefault(emptyList())
    val contacts = runCatching { db.getTrustedContacts() }.getOrDefault(emptyList())
    val savedPlaces = runCatching { db.getSavedPlaces() }.getOrDefault(emptyList())
    val destinations = runCatching { db.getAllUnexpiredDestinations() }.getOrDefault(emptyList())
    val personaTotal = runCatching { db.getContactPersonaCount() }.getOrDefault(0)
    val opusEnabled = appCtx.getSharedPreferences(JARVIS_PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_OPUS_VOICE_READER, false)
    val ambient = passiveContextEngine?.contextState?.value ?: _uiState.value.ambientContext

    _uiState.update { current ->
      current.copy(
        initialized = true,
        serviceStatus = JarvisForegroundService.status.value,
        notificationListenerEnabled = JarvisNotificationService.isAccessGranted(appCtx),
        notificationListenerConnected = JarvisNotificationService.isConnected.value,
        accessibilityEnabled = JarvisAccessibilityService.isEnabledInSettings(appCtx),
        accessibilityConnected = JarvisAccessibilityService.isConnected.value,
        hasLocationPermission = hasFineOrCoarseLocation(appCtx),
        hasBackgroundLocationPermission = hasBackgroundLocation(appCtx),
        hasBluetoothConnectPermission = hasBluetoothConnect(appCtx),
        hasActivityRecognitionPermission = hasActivityRecognition(appCtx),
        hasMediaAudioPermission = hasMediaAudioPermission(appCtx),
        opusVoiceReaderEnabled = opusEnabled,
        batteryUnrestricted = isBatteryUnrestricted(appCtx),
        hasApiKey = vault?.hasApiKey?.value ?: false,
        maskedApiKey = vault?.maskedKeyPreview?.value.orEmpty(),
        activeReplyHandleCount = remoteInputCache.activeCount(),
        personaCount = personaTotal,
        suppressedGroupCount = suppressedGroupCount.get(),
        ambientContext = ambient,
        savedPlaces = savedPlaces,
        upcomingDestinations = destinations,
        proactiveSuggestion = _proactiveSuggestion.value,
        events = events,
        rules = rules,
        playbooks = playbooks,
        trustedContacts = contacts,
      )
    }
  }

  private fun syncEnvironmentOnly(appCtx: Context) {
    val vault = keyVault
    val opusEnabled = appCtx.getSharedPreferences(JARVIS_PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_OPUS_VOICE_READER, false)
    val ambient = passiveContextEngine?.contextState?.value ?: _uiState.value.ambientContext
    _uiState.update { current ->
      current.copy(
        serviceStatus = JarvisForegroundService.status.value,
        notificationListenerEnabled = JarvisNotificationService.isAccessGranted(appCtx),
        notificationListenerConnected = JarvisNotificationService.isConnected.value,
        accessibilityEnabled = JarvisAccessibilityService.isEnabledInSettings(appCtx),
        accessibilityConnected = JarvisAccessibilityService.isConnected.value,
        hasLocationPermission = hasFineOrCoarseLocation(appCtx),
        hasBackgroundLocationPermission = hasBackgroundLocation(appCtx),
        hasBluetoothConnectPermission = hasBluetoothConnect(appCtx),
        hasActivityRecognitionPermission = hasActivityRecognition(appCtx),
        hasMediaAudioPermission = hasMediaAudioPermission(appCtx),
        opusVoiceReaderEnabled = opusEnabled,
        batteryUnrestricted = isBatteryUnrestricted(appCtx),
        hasApiKey = vault?.hasApiKey?.value ?: current.hasApiKey,
        maskedApiKey = vault?.maskedKeyPreview?.value ?: current.maskedApiKey,
        activeReplyHandleCount = remoteInputCache.activeCount(),
        ambientContext = ambient,
        proactiveSuggestion = _proactiveSuggestion.value,
      )
    }
  }

  private fun syncProactiveSnapshot(db: JarvisDatabase) {
    val savedPlaces = runCatching { db.getSavedPlaces() }.getOrDefault(emptyList())
    val destinations = runCatching { db.getAllUnexpiredDestinations() }.getOrDefault(emptyList())
    _uiState.update {
      it.copy(
        savedPlaces = savedPlaces,
        upcomingDestinations = destinations,
        proactiveSuggestion = _proactiveSuggestion.value,
      )
    }
  }

  @SuppressLint("MissingPermission")
  private fun handleFallbackNotification(appCtx: Context, suggestion: ProactiveSuggestion?) {
    if (suggestion == null || launcherInForeground) {
      cancelProactiveNotification(appCtx)
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        PROACTIVE_CHANNEL_ID,
        "Jarvis Proactive Context",
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "Anticipatory navigation and UPI scanner actions outside launcher"
      }
      nm.createNotificationChannel(channel)
    }
    val actionIntent = when (suggestion) {
      is ProactiveSuggestion.OpenMaps -> buildMapsNavigationIntent(appCtx, suggestion.placeName)
      is ProactiveSuggestion.ScanUpiQr ->
        buildUpiScannerIntent(appCtx) ?: Intent(appCtx, MainActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
    val contentPi = PendingIntent.getActivity(
      appCtx,
      PROACTIVE_NOTIFICATION_ID,
      actionIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val (title, text) = when (suggestion) {
      is ProactiveSuggestion.OpenMaps ->
        "Navigate to ${suggestion.placeName}" to suggestion.sourceSummary
      is ProactiveSuggestion.ScanUpiQr ->
        "Scan UPI QR (${suggestion.placeLabel})" to "Dwelt ${suggestion.dwellMinutes}m away · Tap to open scanner"
    }
    val notification = NotificationCompat.Builder(appCtx, PROACTIVE_CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(contentPi)
      .build()
    runCatching { nm.notify(PROACTIVE_NOTIFICATION_ID, notification) }
  }

  private fun cancelProactiveNotification(appCtx: Context) {
    runCatching {
      val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
      nm.cancel(PROACTIVE_NOTIFICATION_ID)
    }
  }

  private fun syncPlaybooksAndContacts(db: JarvisDatabase) {
    val playbooks = runCatching { db.getAllPlaybooks() }.getOrDefault(emptyList())
    val contacts = runCatching { db.getTrustedContacts() }.getOrDefault(emptyList())
    _uiState.update { it.copy(playbooks = playbooks, trustedContacts = contacts) }
  }

  private fun syncEventsSnapshot(db: JarvisDatabase) {
    val events = runCatching {
      db.getRecentEvents(canReplyChecker = remoteInputCache::hasHandle)
    }.getOrDefault(emptyList())
    _uiState.update {
      it.copy(
        events = events,
        activeReplyHandleCount = remoteInputCache.activeCount(),
      )
    }
  }

  private fun recordFault(message: String) {
    JarvisForegroundService.recordError(message)
    _uiState.update { it.copy(isProcessing = false, lastError = message) }
  }
}
