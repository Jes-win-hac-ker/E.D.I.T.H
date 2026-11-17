package com.nexwork.edith.jarvis.core

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
      JarvisRuntime.emergencyStop(applicationContext, reason = "Baseplate service stopped", bounceHome = false)
      _status.update { it.copy(isRunning = false, lastEventSummary = "Service stopped") }
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    if (intent?.action == ACTION_EMERGENCY_ABORT) {
      JarvisRuntime.emergencyStop(applicationContext, reason = "Notification ABORT tapped", bounceHome = true)
      return START_STICKY
    }

    ensureChannel()
    enterForeground(includeMicrophone = intent?.action == ACTION_PROMOTE_MIC)

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

  private fun enterForeground(includeMicrophone: Boolean = false) {
    val notification = buildStickyNotification()
    val baseMask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
      val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
      if (hasFine || hasCoarse) {
        mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
      }
      val hasBtConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
          PackageManager.PERMISSION_GRANTED
      } else {
        true
      }
      if (hasBtConnect) {
        mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
      }
      mask
    } else {
      0
    }
    val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    val fullMask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && includeMicrophone && hasMicPerm) {
      baseMask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    } else {
      baseMask
    }
    runCatching {
      ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fullMask)
    }.onFailure { outerErr ->
      if (fullMask != baseMask) {
        runCatching {
          ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, baseMask)
        }.onFailure { err ->
          _status.update { it.copy(lastError = err.message ?: "Foreground promotion failed") }
        }
      } else {
        _status.update { it.copy(lastError = outerErr.message ?: "Foreground promotion failed") }
      }
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

  private fun buildStickyNotification(
    contentText: String = "Monitoring notifications & reply cache",
  ): Notification = buildNotification(this, contentText)

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
    private const val REQUEST_ABORT_TASK = 4111
    private const val REQUEST_STOP_SERVICE = 4112

    const val ACTION_START = "com.nexwork.edith.jarvis.action.START"
    const val ACTION_PROMOTE_MIC = "com.nexwork.edith.jarvis.action.PROMOTE_MIC"
    const val ACTION_STOP = "com.nexwork.edith.jarvis.action.STOP"
    const val ACTION_EMERGENCY_ABORT = "com.nexwork.edith.jarvis.action.EMERGENCY_ABORT"

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

    fun promoteForMicrophone(context: Context) {
      val appCtx = context.applicationContext
      val intent = Intent(appCtx, JarvisForegroundService::class.java).apply {
        action = ACTION_PROMOTE_MIC
      }
      runCatching { ContextCompat.startForegroundService(appCtx, intent) }
    }

    fun stop(context: Context) {
      val appCtx = context.applicationContext
      val intent = Intent(appCtx, JarvisForegroundService::class.java).apply {
        action = ACTION_STOP
      }
      runCatching { appCtx.startService(intent) }
    }

    internal fun updateRunningTask(context: Context, taskLabel: String?) {
      if (!_status.value.isRunning) return
      val label = taskLabel ?: "Monitoring notifications & reply cache"
      refreshNotification(context, label)
    }

    internal fun recordStopped(context: Context, summary: String) {
      _status.update { current ->
        current.copy(
          lastEventSummary = "STOPPED: $summary",
          lastError = null,
        )
      }
      if (_status.value.isRunning) {
        refreshNotification(context, "STOPPED: $summary")
      }
    }

    private fun buildNotification(context: Context, contentText: String): Notification {
      val launchIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
      val contentPi = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      val abortPi = PendingIntent.getService(
        context,
        REQUEST_ABORT_TASK,
        Intent(context, JarvisForegroundService::class.java).apply { action = ACTION_EMERGENCY_ABORT },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      val stopPi = PendingIntent.getService(
        context,
        REQUEST_STOP_SERVICE,
        Intent(context, JarvisForegroundService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("JARVIS // BASEPLATE ACTIVE")
        .setContentText(contentText)
        .setContentIntent(contentPi)
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .addAction(0, "ABORT TASK", abortPi)
        .addAction(0, "STOP FGS", stopPi)
        .build()
    }

    private fun refreshNotification(context: Context, contentText: String) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        return
      }
      runCatching {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(context, contentText))
      }
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
