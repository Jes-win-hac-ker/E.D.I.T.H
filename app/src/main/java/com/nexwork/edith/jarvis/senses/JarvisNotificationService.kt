package com.nexwork.edith.jarvis.senses

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nexwork.edith.jarvis.core.JarvisRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Captures incoming notifications, extracts MessagingStyle threads and RemoteInput reply
 * handles, and forwards them to [JarvisRuntime] off the main thread.
 */
class JarvisNotificationService : NotificationListenerService() {

  override fun onListenerConnected() {
    _isConnected.value = true
    JarvisRuntime.ensureInitialized(applicationContext)
    JarvisRuntime.refreshEnvironmentStatus(applicationContext)
  }

  override fun onListenerDisconnected() {
    _isConnected.value = false
    JarvisRuntime.refreshEnvironmentStatus(applicationContext)
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null) return
    runCatching {
      val extracted = NotificationExtractor.extract(applicationContext, sbn) ?: return
      JarvisRuntime.onNotificationCaptured(applicationContext, extracted)
    }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    val key = sbn?.key ?: return
    JarvisRuntime.onNotificationRemoved(key)
  }

  companion object {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun isAccessGranted(context: Context): Boolean {
      return runCatching {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
          ?: return false
        nm.isNotificationListenerAccessGranted(
          ComponentName(context, JarvisNotificationService::class.java),
        )
      }.getOrDefault(false)
    }
  }
}
