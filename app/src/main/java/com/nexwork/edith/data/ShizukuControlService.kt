package com.nexwork.edith.data

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService running in the privileged `shell` (`uid 2000`) process.
 *
 * Executes only fixed, allowlisted system commands via [ProcessBuilder] without invoking a shell,
 * providing permission auto-granting, connectivity/DND toggles, refresh-rate presets, and a
 * compact system status summary for E.D.I.T.H without root.
 */
@Keep
class ShizukuControlService : IShizukuControlService.Stub {

  @Keep
  constructor() : super()

  @Keep
  constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

  override fun destroy() {
    exitProcess(0)
  }

  override fun grantLauncherPermissions(packageName: String, listenerComponent: String): Boolean {
    if (!ShizukuControlManager.sanitizeComponentToken(packageName) ||
      !ShizukuControlManager.sanitizeComponentToken(listenerComponent)
    ) {
      return false
    }
    val appOpsOk = runCommand("appops", "set", packageName, "GET_USAGE_STATS", "allow").exitCode == 0
    val listenerOk = runCommand("cmd", "notification", "allow_listener", listenerComponent).exitCode == 0
    return appOpsOk || listenerOk
  }

  override fun setWifiEnabled(enabled: Boolean): Boolean {
    val primary = runCommand("cmd", "wifi", "set-wifi-enabled", if (enabled) "enabled" else "disabled")
    if (primary.exitCode == 0) return true
    return runCommand("svc", "wifi", if (enabled) "enable" else "disable").exitCode == 0
  }

  override fun setBluetoothEnabled(enabled: Boolean): Boolean {
    val primary = runCommand("cmd", "bluetooth_manager", if (enabled) "enable" else "disable")
    if (primary.exitCode == 0) return true
    return runCommand("svc", "bluetooth", if (enabled) "enable" else "disable").exitCode == 0
  }

  override fun setDndEnabled(enabled: Boolean): Boolean {
    return runCommand("cmd", "notification", "set_dnd", if (enabled) "priority" else "off").exitCode == 0
  }

  override fun setRefreshRateHz(hz: Int): Boolean {
    val targetHz = ShizukuControlManager.parseRefreshRatePreset(hz)
    val minHz = if (targetHz >= 90) "60.0" else "$targetHz.0"
    val peakOk = runCommand(
      "cmd",
      "settings",
      "put",
      "system",
      "peak_refresh_rate",
      "$targetHz.0",
    ).exitCode == 0
    val minOk = runCommand(
      "cmd",
      "settings",
      "put",
      "system",
      "min_refresh_rate",
      minHz,
    ).exitCode == 0
    return peakOk && minOk
  }

  override fun readSystemStatus(): String {
    return runCatching {
      val wifiOut = runCommand("cmd", "wifi", "status").stdout
      val wifiLabel = when {
        wifiOut.contains("Wi-Fi is enabled", ignoreCase = true) -> "WIFI:ON"
        wifiOut.contains("Wi-Fi is disabled", ignoreCase = true) -> "WIFI:OFF"
        else -> "WIFI:?"
      }

      val hzOut = runCommand("cmd", "settings", "get", "system", "peak_refresh_rate").stdout
      val hzLabel = hzOut.toDoubleOrNull()?.toInt()?.let { "${it}Hz" } ?: "--Hz"

      val zenOut = runCommand("cmd", "settings", "get", "global", "zen_mode").stdout
      val dndLabel = when (zenOut.toIntOrNull()) {
        null -> "DND:?"
        0 -> "DND:OFF"
        else -> "DND:ON"
      }

      "$wifiLabel | $hzLabel | $dndLabel"
    }.getOrDefault("WIFI:? | --Hz | DND:?")
  }

  private data class CommandResult(val exitCode: Int, val stdout: String)

  private fun runCommand(vararg argv: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): CommandResult {
    return runCatching {
      val proc = ProcessBuilder(*argv)
        .redirectErrorStream(true)
        .start()
      try {
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
          proc.destroyForcibly()
          return CommandResult(exitCode = -1, stdout = "")
        }
        val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
        CommandResult(exitCode = proc.exitValue(), stdout = out)
      } finally {
        runCatching { proc.inputStream.close() }
        runCatching { proc.outputStream.close() }
        runCatching { proc.errorStream.close() }
      }
    }.getOrDefault(CommandResult(exitCode = -1, stdout = ""))
  }

  companion object {
    private const val COMMAND_TIMEOUT_MS = 4_000L
  }
}
