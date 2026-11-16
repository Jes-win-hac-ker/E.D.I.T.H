package com.nexwork.edith.jarvis.core

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nexwork.edith.MainActivity
import com.nexwork.edith.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sticky foreground service (`specialUse`) hosting the Milestone 1 Jarvis baseplate.
 * Survives recents task removal (`stopWithTask="false"` + `START_STICKY`) and exposes live
 * execution telemetry through [status].
 */
class JarvisForegroundService : Service() {

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    enterForeground()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      _status.update { it.copy(isRunning = false, lastEventSummary = "Service stopped") }
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    ensureChannel()
    enterForeground()

    val now = System.currentTimeMillis()
    _status.update { current ->
      current.copy(
        isRunning = true,
        startedAtMs = current.startedAtMs ?: now,
        lastError = null,
      )
    }

    // Rehydrate JarvisRuntime if the OS recreated the sticky service after process eviction.
    JarvisRuntime.ensureInitialized(applicationContext)
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    val now = System.currentTimeMillis()
    _status.update { it.copy(lastTaskRemovedAtMs = now) }
    enterForeground()
    scheduleRestartFallback()
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    _status.update { it.copy(isRunning = false) }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun enterForeground() {
    runCatching {
      val notification = buildStickyNotification()
      val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      } else {
        0
      }
      ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }.onFailure { err ->
      _status.update { it.copy(lastError = err.message ?: "Foreground promotion failed") }
    }
  }

  private fun ensureChannel() {
    val nm = getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.jarvis_fgs_channel_name),
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "Persistent baseplate status for E.D.I.T.H Jarvis notification triage"
      setShowBadge(false)
    }
    nm.createNotificationChannel(channel)
  }

  private fun buildStickyNotification(): Notification {
    val launchIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val contentPendingIntent = PendingIntent.getActivity(
      this,
      REQUEST_OPEN_APP,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("JARVIS // BASEPLATE ACTIVE")
      .setContentText("Monitoring notifications & reply cache")
      .setContentIntent(contentPendingIntent)
      .setOngoing(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun scheduleRestartFallback() {
    runCatching {
      val restartIntent = Intent(applicationContext, JarvisForegroundService::class.java).apply {
        action = ACTION_START
      }
      val pendingIntent = PendingIntent.getForegroundService(
        applicationContext,
        REQUEST_RESTART,
        restartIntent,
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
      )
      val alarmManager = getSystemService(AlarmManager::class.java) ?: return
      alarmManager.set(
        AlarmManager.ELAPSED_REALTIME,
        SystemClock.elapsedRealtime() + 1_000L,
        pendingIntent,
      )
    }
  }

  companion object {
    private const val CHANNEL_ID = "jarvis_baseplate_channel"
    private const val NOTIFICATION_ID = 4107
    private const val REQUEST_OPEN_APP = 4108
    private const val REQUEST_RESTART = 4109

    const val ACTION_START = "com.nexwork.edith.jarvis.action.START"
    const val ACTION_STOP = "com.nexwork.edith.jarvis.action.STOP"

    private val _status = MutableStateFlow(JarvisServiceStatus())
    val status: StateFlow<JarvisServiceStatus> = _status.asStateFlow()

    fun start(context: Context): Result<Unit> {
      val appCtx = context.applicationContext
      val intent = Intent(appCtx, JarvisForegroundService::class.java).apply {
        action = ACTION_START
      }
      return runCatching {
        ContextCompat.startForegroundService(appCtx, intent)
      }.onFailure { err ->
        _status.update { it.copy(lastError = err.message ?: "Unable to start foreground service") }
      }
    }

    fun stop(context: Context) {
      val appCtx = context.applicationContext
      val intent = Intent(appCtx, JarvisForegroundService::class.java).apply {
        action = ACTION_STOP
      }
      runCatching { appCtx.startService(intent) }
    }

    internal fun recordProcessed(summary: String) {
      _status.update { current ->
        current.copy(
          processedCount = current.processedCount + 1,
          lastEventSummary = summary,
          lastError = null,
        )
      }
    }

    internal fun recordError(message: String?) {
      _status.update { it.copy(lastError = message) }
    }
  }
}
