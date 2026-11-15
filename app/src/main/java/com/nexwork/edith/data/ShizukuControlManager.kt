package com.nexwork.edith.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.nexwork.edith.notifications.EdithNotificationListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Result of a Shizuku tactical shell action.
 */
data class ShizukuActionResult(
  val ok: Boolean,
  val message: String,
  val usedShizuku: Boolean,
) {
  /**
   * Formats a compact single-line telemetry summary for HUD feedback.
   */
  fun formatStatusMessage(): String {
    val source = if (usedShizuku) "SHIZUKU" else "LOCAL"
    val state = if (ok) "OK" else "ERR"
    return "[$source // $state] $message"
  }
}

/**
 * Client-side controller for [ShizukuControlService] (`uid 2000` UserService).
 *
 * Safely guards all Shizuku binder calls and binds on demand with a 4-second timeout so
 * missing Shizuku installations, revoked permissions, or binder drops never crash the launcher.
 */
class ShizukuControlManager(private val context: Context) {

  private val serviceMutex = Mutex()

  /**
   * Returns `true` when the Shizuku binder is reachable and this package already holds
   * Shizuku runtime permission without prompting the user.
   */
  suspend fun isShizukuAuthorized(): Boolean = withContext(Dispatchers.IO) {
    isBinderAlive() && hasPermissionGranted()
  }

  /**
   * Silently grants `GET_USAGE_STATS` and [EdithNotificationListener] access when Shizuku is
   * already alive and authorized. Never triggers a UI permission prompt.
   */
  suspend fun autoGrantPermissionsIfReady(): ShizukuActionResult {
    return executeControlAction(
      actionLabel = "Launcher permissions granted",
      failureLabel = "Failed to grant launcher permissions",
    ) { service ->
      val pkg = context.packageName
      val listener = ComponentName(context, EdithNotificationListener::class.java).flattenToString()
      if (!sanitizeComponentToken(pkg) || !sanitizeComponentToken(listener)) {
        false
      } else {
        service.grantLauncherPermissions(pkg, listener)
      }
    }
  }

  /**
   * Enables or disables Wi-Fi via [ShizukuControlService].
   */
  suspend fun toggleWifi(enabled: Boolean): ShizukuActionResult {
    val target = if (enabled) "enabled" else "disabled"
    return executeControlAction(
      actionLabel = "Wi-Fi $target",
      failureLabel = "Failed to set Wi-Fi $target",
    ) { service ->
      service.setWifiEnabled(enabled)
    }
  }

  /**
   * Enables or disables Bluetooth via [ShizukuControlService].
   */
  suspend fun toggleBluetooth(enabled: Boolean): ShizukuActionResult {
    val target = if (enabled) "enabled" else "disabled"
    return executeControlAction(
      actionLabel = "Bluetooth $target",
      failureLabel = "Failed to set Bluetooth $target",
    ) { service ->
      service.setBluetoothEnabled(enabled)
    }
  }

  /**
   * Enables or disables Do Not Disturb via [ShizukuControlService].
   */
  suspend fun toggleDnd(enabled: Boolean): ShizukuActionResult {
    val target = if (enabled) "enabled" else "disabled"
    return executeControlAction(
      actionLabel = "Do Not Disturb $target",
      failureLabel = "Failed to set Do Not Disturb $target",
    ) { service ->
      service.setDndEnabled(enabled)
    }
  }

  /**
   * Applies a normalized display refresh rate preset (`60`, `90`, or `120` Hz).
   */
  suspend fun setRefreshRate(hz: Int): ShizukuActionResult {
    val preset = parseRefreshRatePreset(hz)
    return executeControlAction(
      actionLabel = "Refresh rate set to ${preset}Hz",
      failureLabel = "Failed to set ${preset}Hz refresh rate",
    ) { service ->
      service.setRefreshRateHz(preset)
    }
  }

  /**
   * Reads a single-line system status summary (`WIFI | Hz | DND`) via [ShizukuControlService],
   * or `null` when Shizuku is unavailable, unauthorized, or binding fails.
   */
  suspend fun readStatusSummary(): String? = withContext(Dispatchers.IO) {
    if (!isBinderAlive() || !hasPermissionGranted()) return@withContext null
    serviceMutex.withLock {
      withBoundControlService { service ->
        runCatching { service.readSystemStatus()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
      }
    }
  }

  private suspend fun executeControlAction(
    actionLabel: String,
    failureLabel: String,
    block: (IShizukuControlService) -> Boolean,
  ): ShizukuActionResult = withContext(Dispatchers.IO) {
    if (!isBinderAlive()) {
      return@withContext ShizukuActionResult(
        ok = false,
        message = "Shizuku service unavailable",
        usedShizuku = false,
      )
    }
    if (!hasPermissionGranted()) {
      return@withContext ShizukuActionResult(
        ok = false,
        message = "Shizuku permission not granted",
        usedShizuku = false,
      )
    }

    serviceMutex.withLock {
      val result = withBoundControlService { service ->
        runCatching { block(service) }.getOrDefault(false)
      }
      when (result) {
        null -> ShizukuActionResult(
          ok = false,
          message = "Shizuku control service bind timed out",
          usedShizuku = false,
        )
        true -> ShizukuActionResult(
          ok = true,
          message = actionLabel,
          usedShizuku = true,
        )
        false -> ShizukuActionResult(
          ok = false,
          message = failureLabel,
          usedShizuku = true,
        )
      }
    }
  }

  private suspend fun <T> withBoundControlService(
    block: suspend (IShizukuControlService) -> T,
  ): T? {
    val serviceDeferred = CompletableDeferred<IShizukuControlService?>()
    val serviceArgs = Shizuku.UserServiceArgs(
      ComponentName(context.packageName, ShizukuControlService::class.java.name),
    )
      .daemon(false)
      .processNameSuffix("shizuku_control")
      .debuggable(false)
      .version(1)

    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val svc = IShizukuControlService.Stub.asInterface(binder)
        if (svc != null && binder != null && binder.pingBinder()) {
          if (!serviceDeferred.isCompleted) serviceDeferred.complete(svc)
        } else {
          if (!serviceDeferred.isCompleted) serviceDeferred.complete(null)
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        if (!serviceDeferred.isCompleted) serviceDeferred.complete(null)
      }
    }

    return try {
      Shizuku.bindUserService(serviceArgs, connection)
      val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { serviceDeferred.await() } ?: return null
      block(service)
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
    }
  }

  private fun isBinderAlive(): Boolean {
    return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
  }

  private fun hasPermissionGranted(): Boolean {
    return runCatching {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
  }

  companion object {
    private const val BIND_TIMEOUT_MS = 4_000L
    private const val MAX_TOKEN_LENGTH = 256
    private val SAFE_TOKEN_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._/$]*$")

    /**
     * Validates that [value] is a safe Android package or flattened component token containing
     * only `[a-zA-Z0-9._/$]` without path traversal or shell metacharacters.
     */
    fun sanitizeComponentToken(value: String): Boolean {
      if (value.isEmpty() || value.length > MAX_TOKEN_LENGTH) return false
      if (value.contains("..")) return false
      return SAFE_TOKEN_REGEX.matches(value)
    }

    /**
     * Normalizes an arbitrary refresh-rate input to one of the supported presets (`60`, `90`, `120`).
     * Defaults to `120` when [raw] is `null`.
     */
    fun parseRefreshRatePreset(raw: Int?): Int {
      if (raw == null) return 120
      return when {
        raw < 75 -> 60
        raw < 105 -> 90
        else -> 120
      }
    }
  }
}
