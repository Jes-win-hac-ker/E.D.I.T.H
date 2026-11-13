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
 * BroadcastReceiver for Option A Persistence Guard (BOOT_COMPLETED / MY_PACKAGE_REPLACED)
 * and background ADB command triggers (MONET_APPLY / MONET_RESET).
 */
class MonetCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val manager = MonetThemeManager(appContext)
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        Log.i(TAG, "Checking Crimson Monet persistence guard after $action")
                        manager.ensureOverlayPersistedIfEnabled()
                    }
                    ACTION_MONET_APPLY -> {
                        val res = manager.applyCrimsonMonet()
                        Log.i(TAG, "MONET_APPLY result: $res")
                    }
                    ACTION_MONET_RESET -> {
                        val res = manager.resetToDefault()
                        Log.i(TAG, "MONET_RESET result: $res")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MonetCommandReceiver failed for $action: ${t.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MonetCommandReceiver"
        const val ACTION_MONET_APPLY = "com.nexwork.edith.action.MONET_APPLY"
        const val ACTION_MONET_RESET = "com.nexwork.edith.action.MONET_RESET"
    }
}
