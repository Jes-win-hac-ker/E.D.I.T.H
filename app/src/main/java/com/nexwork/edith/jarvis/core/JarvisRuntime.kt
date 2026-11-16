package com.nexwork.edith.jarvis.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Choreographer
import androidx.activity.ComponentActivity
import com.nexwork.edith.jarvis.brain.GeminiClient
import com.nexwork.edith.jarvis.memory.JarvisDatabase
import com.nexwork.edith.jarvis.reflex.GatekeeperCandidate
import com.nexwork.edith.jarvis.reflex.GatekeeperDecision
import com.nexwork.edith.jarvis.reflex.LocalGatekeeper
import com.nexwork.edith.jarvis.senses.ExtractedNotification
import com.nexwork.edith.jarvis.senses.JarvisNotificationService
import com.nexwork.edith.jarvis.senses.RemoteInputCache
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Isolated Milestone 1 Jarvis coordinator.
 *
 * All I/O, Keystore decryption, SQLite WAL writes, and Gemini HTTP calls execute inside a
 * dedicated `SupervisorJob() + Dispatchers.IO` scope with a top-level [CoroutineExceptionHandler]
 * so background faults can never crash the launcher UI.
 */
object JarvisRuntime {

  private val firstFrameScheduled = AtomicBoolean(false)
  private val initialized = AtomicBoolean(false)
  private val observersStarted = AtomicBoolean(false)
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
      val contentHash = (decision as GatekeeperDecision.Allow).contentHash

      extracted.replyHandle?.let { handle ->
        remoteInputCache.put(handle)
      }

      val eventId = db.insertIncomingEvent(
        captured = captured,
        contentHash = contentHash,
        createdAtMs = captured.postTimeMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
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
        val client = geminiClient ?: return@launch
        val result = client.classifyNotification(apiKey, captured, rules)
        result
          .onSuccess { triageResult ->
            db.updateTriageResult(
              eventId = eventId,
              triage = triageResult.triage,
              triageJson = triageResult.triageJson,
            )
            JarvisForegroundService.recordProcessed(
              "${triageResult.triage.urgency} · ${triageResult.triage.sender}: ${triageResult.triage.intent}",
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
      } finally {
        _uiState.update { it.copy(isProcessing = false) }
        syncEventsSnapshot(db)
      }
    }
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
      val result = remoteInputCache.sendReply(appCtx, event.sbnKey, resolvedText)
      result
        .onSuccess {
          database?.let { db ->
            db.markReplySent(event.id, resolvedText)
            syncEventsSnapshot(db)
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
          database?.let { syncEventsSnapshot(it) }
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

  fun clearBanner() {
    _uiState.update { it.copy(lastStatusBanner = null, lastError = null) }
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
  }

  private fun ensureComponentsLocked(appCtx: Context) {
    synchronized(componentLock) {
      if (keyVault == null) keyVault = KeyVault(appCtx)
      if (database == null) database = JarvisDatabase(appCtx)
      if (gatekeeper == null) gatekeeper = LocalGatekeeper(appCtx.packageName)
      if (geminiClient == null) geminiClient = GeminiClient()
    }
  }

  private fun syncStateSnapshot(appCtx: Context) {
    val db = database ?: return
    val vault = keyVault
    val events = runCatching {
      db.getRecentEvents(canReplyChecker = remoteInputCache::hasHandle)
    }.getOrDefault(emptyList())
    val rules = runCatching { db.getActiveRules() }.getOrDefault(emptyList())

    _uiState.update { current ->
      current.copy(
        initialized = true,
        serviceStatus = JarvisForegroundService.status.value,
        notificationListenerEnabled = JarvisNotificationService.isAccessGranted(appCtx),
        notificationListenerConnected = JarvisNotificationService.isConnected.value,
        batteryUnrestricted = isBatteryUnrestricted(appCtx),
        hasApiKey = vault?.hasApiKey?.value ?: false,
        maskedApiKey = vault?.maskedKeyPreview?.value.orEmpty(),
        activeReplyHandleCount = remoteInputCache.activeCount(),
        events = events,
        rules = rules,
      )
    }
  }

  private fun syncEnvironmentOnly(appCtx: Context) {
    val vault = keyVault
    _uiState.update { current ->
      current.copy(
        serviceStatus = JarvisForegroundService.status.value,
        notificationListenerEnabled = JarvisNotificationService.isAccessGranted(appCtx),
        notificationListenerConnected = JarvisNotificationService.isConnected.value,
        batteryUnrestricted = isBatteryUnrestricted(appCtx),
        hasApiKey = vault?.hasApiKey?.value ?: current.hasApiKey,
        maskedApiKey = vault?.maskedKeyPreview?.value ?: current.maskedApiKey,
        activeReplyHandleCount = remoteInputCache.activeCount(),
      )
    }
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
