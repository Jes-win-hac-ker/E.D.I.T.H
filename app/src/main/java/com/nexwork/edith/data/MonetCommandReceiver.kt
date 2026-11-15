package com.nexwork.edith.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for Option A Persistence Guard (BOOT_COMPLETED / MY_PACKAGE_REPLACED).
 */
class MonetCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val manager = MonetThemeManager(appContext)
                Log.i(TAG, "Checking Crimson Monet persistence guard after $action")
                manager.ensureOverlayPersistedIfEnabled()
            } catch (t: Throwable) {
                Log.w(TAG, "MonetCommandReceiver failed for $action: ${t.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MonetCommandReceiver"
    }
}
