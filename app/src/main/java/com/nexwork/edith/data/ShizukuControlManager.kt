package com.nexwork.edith.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.nexwork.edith.jarvis.core.CompactUiTree
import com.nexwork.edith.jarvis.security.SecurityGateway
import com.nexwork.edith.notifications.EdithNotificationListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

  /**
   * Dumps the active UI window hierarchy via `uiautomator dump /data/local/tmp/jarvis_ui.xml`
   * in Shizuku (`uid 2000`), filters out `com.nexwork.edith` and financial/UPI/installer packages,
   * and returns a 50-element capped [CompactUiTree].
   */
  suspend fun dumpCompactUiTree(): CompactUiTree = withContext(Dispatchers.IO) {
    if (!isBinderAlive()) {
      return@withContext CompactUiTree(
        packageName = "",
        blockedReason = "Shizuku service is not running",
      )
    }
    if (!hasPermissionGranted()) {
      return@withContext CompactUiTree(
        packageName = "",
        blockedReason = "Shizuku permission not granted",
      )
    }
    val rawXml = serviceMutex.withLock {
      withBoundControlService { service ->
        runCatching { service.dumpUiHierarchyXml().orEmpty() }.getOrDefault("")
      }
    }.orEmpty()
    ShizukuControlService.parseUiHierarchyXml(rawXml, context.packageName)
  }

  /**
   * Executes `input tap <x> <y>` via Shizuku (`uid 2000`).
   */
  suspend fun tap(x: Int, y: Int): Result<String> = withContext(Dispatchers.IO) {
    val activePkg = ShizukuControlService.lastKnownForegroundPackage()
    if (activePkg.isNotBlank() && SecurityGateway.isRestrictedPackage(activePkg, context.packageName)) {
      ShizukuControlService.clearNodeCache()
      return@withContext Result.failure(SecurityException("Blocked tap inside restricted package: $activePkg"))
    }
    val res = executeControlAction(
      actionLabel = "Tapped ($x,$y)",
      failureLabel = "Shizuku input tap failed at ($x,$y)",
    ) { service ->
      service.inputTap(x, y)
    }
    if (res.ok) Result.success(res.message) else Result.failure(IllegalStateException(res.message))
  }

  /**
   * Resolves cached node [id] center `(x, y)` and executes `input tap <x> <y>` via Shizuku.
   */
  suspend fun clickNode(id: Int): Result<String> = withContext(Dispatchers.IO) {
    val activePkg = ShizukuControlService.lastKnownForegroundPackage()
    if (activePkg.isNotBlank() && SecurityGateway.isRestrictedPackage(activePkg, context.packageName)) {
      ShizukuControlService.clearNodeCache()
      return@withContext Result.failure(SecurityException("Blocked click inside restricted package: $activePkg"))
    }
    val node = ShizukuControlService.getCachedNode(id)
      ?: return@withContext Result.failure(IllegalArgumentException("UI node id=$id not found in latest tree"))
    val cx = node.centerX.coerceAtLeast(1)
    val cy = node.centerY.coerceAtLeast(1)
    val label = node.text.ifBlank { node.contentDesc }.ifBlank { node.viewId }
    val res = executeControlAction(
      actionLabel = "Clicked node id=$id at ($cx,$cy) label=$label",
      failureLabel = "Shizuku input tap failed for node id=$id at ($cx,$cy)",
    ) { service ->
      service.inputTap(cx, cy)
    }
    if (res.ok) Result.success(res.message) else Result.failure(IllegalStateException(res.message))
  }

  /**
   * Executes `input swipe <x1> <y1> <x2> <y2> <durationMs>` via Shizuku (`uid 2000`).
   */
  suspend fun swipe(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    durationMs: Int = 240,
  ): Result<String> = withContext(Dispatchers.IO) {
    val activePkg = ShizukuControlService.lastKnownForegroundPackage()
    if (activePkg.isNotBlank() && SecurityGateway.isRestrictedPackage(activePkg, context.packageName)) {
      ShizukuControlService.clearNodeCache()
      return@withContext Result.failure(SecurityException("Blocked swipe inside restricted package: $activePkg"))
    }
    val res = executeControlAction(
      actionLabel = "Swiped ($x1,$y1)->($x2,$y2) in ${durationMs}ms",
      failureLabel = "Shizuku input swipe failed",
    ) { service ->
      service.inputSwipe(x1, y1, x2, y2, durationMs)
    }
    if (res.ok) Result.success(res.message) else Result.failure(IllegalStateException(res.message))
  }

  /**
   * Scrolls node [id] (or the active screen viewport) forward or backward via Shizuku `input swipe`.
   */
  suspend fun scrollNode(id: Int?, forward: Boolean): Result<String> = withContext(Dispatchers.IO) {
    val node = id?.let { ShizukuControlService.getCachedNode(it) }
    val dm = context.resources.displayMetrics
    val cx = node?.centerX ?: (dm.widthPixels / 2).coerceAtLeast(180)
    val topBound = node?.top ?: (dm.heightPixels * 0.25f).toInt()
    val bottomBound = node?.bottom ?: (dm.heightPixels * 0.75f).toInt()
    val span = (bottomBound - topBound).coerceAtLeast(160)
    val yStart = if (forward) topBound + (span * 0.78f).toInt() else topBound + (span * 0.22f).toInt()
    val yEnd = if (forward) topBound + (span * 0.22f).toInt() else topBound + (span * 0.78f).toInt()
    swipe(cx, yStart, cx, yEnd, durationMs = 240)
  }

  /**
   * Taps node [id]'s center `(x, y)` to focus it, then executes `input text` via Shizuku with
   * shell-escaped text after verifying [SecurityGateway.inspectOutboundText].
   */
  suspend fun inputText(id: Int, text: String): Result<String> = withContext(Dispatchers.IO) {
    val activePkg = ShizukuControlService.lastKnownForegroundPackage()
    if (activePkg.isNotBlank() && SecurityGateway.isRestrictedPackage(activePkg, context.packageName)) {
      ShizukuControlService.clearNodeCache()
      return@withContext Result.failure(SecurityException("Blocked input inside restricted package: $activePkg"))
    }
    SecurityGateway.inspectOutboundText(text)?.let { violation ->
      return@withContext Result.failure(SecurityException(violation))
    }
    val node = ShizukuControlService.getCachedNode(id)
      ?: return@withContext Result.failure(IllegalArgumentException("UI node id=$id not found in latest tree"))
    val cx = node.centerX.coerceAtLeast(1)
    val cy = node.centerY.coerceAtLeast(1)
    val escaped = ShizukuControlService.escapeShellInputText(text)

    val res = executeControlAction(
      actionLabel = "Injected ${text.length} chars into node id=$id at ($cx,$cy)",
      failureLabel = "Shizuku input text failed for node id=$id",
    ) { service ->
      if (!service.inputTap(cx, cy)) return@executeControlAction false
      try {
        Thread.sleep(110L)
      } catch (_: InterruptedException) {
      }
      service.inputTextShell(escaped)
    }
    if (res.ok) Result.success(res.message) else Result.failure(IllegalStateException(res.message))
  }

  /**
   * Executes `input keyevent <keyCode>` via Shizuku (`uid 2000`).
   */
  suspend fun pressKey(keyCode: Int): Result<String> = withContext(Dispatchers.IO) {
    val activePkg = ShizukuControlService.lastKnownForegroundPackage()
    if (activePkg.isNotBlank() && SecurityGateway.isRestrictedPackage(activePkg, context.packageName)) {
      ShizukuControlService.clearNodeCache()
      return@withContext Result.failure(SecurityException("Blocked keyevent inside restricted package: $activePkg"))
    }
    val res = executeControlAction(
      actionLabel = "Sent keyevent $keyCode",
      failureLabel = "Shizuku input keyevent $keyCode failed",
    ) { service ->
      service.inputKeyEvent(keyCode)
    }
    if (res.ok) Result.success(res.message) else Result.failure(IllegalStateException(res.message))
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
    private const val SHIZUKU_HANDS_REQUEST_CODE = 4208
    private val SAFE_TOKEN_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._/$]*$")

    fun isShizukuBinderAlive(): Boolean =
      runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isShizukuPermissionGranted(): Boolean =
      runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
      }.getOrDefault(false)

    fun requestOrOpenShizuku(context: Context) {
      runCatching {
        if (Shizuku.pingBinder()) {
          if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_HANDS_REQUEST_CODE)
            return
          }
        }
        val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) {
          launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(launch)
        }
      }
    }

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
