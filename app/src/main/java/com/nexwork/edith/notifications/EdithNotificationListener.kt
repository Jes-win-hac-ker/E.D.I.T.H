package com.nexwork.edith.notifications

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks active notifications per package and exposes counts to the launcher UI
 * (same process, so the companion StateFlow is directly observable).
 */
class EdithNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() {
    publish(activeNotifications)
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    publish(activeNotifications)
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    publish(activeNotifications)
  }

  private fun publish(notes: Array<StatusBarNotification>) {
    _activeCounts.value = notes.groupingBy { it.packageName }.eachCount()
  }

  companion object {
    private val _activeCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val activeCounts: StateFlow<Map<String, Int>> = _activeCounts.asStateFlow()

    fun isAccessGranted(context: Context): Boolean {
      return runCatching {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.isNotificationListenerAccessGranted(ComponentName(context, EdithNotificationListener::class.java))
      }.getOrDefault(false)
    }
  }
}
