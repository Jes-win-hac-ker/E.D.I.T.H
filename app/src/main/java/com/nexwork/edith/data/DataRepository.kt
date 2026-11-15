package com.nexwork.edith.data

import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.WindowManager
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.model.AppFootprint
import com.nexwork.edith.model.DeviceTelemetry
import com.nexwork.edith.model.LauncherAccentPreset
import com.nexwork.edith.model.LauncherFontStyle
import com.nexwork.edith.model.LauncherSettingsConfig
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

interface DataRepository {
  val data: Flow<List<String>>
}

class DefaultDataRepository : DataRepository {
  override val data: Flow<List<String>> =
    flowOf(listOf("Phone", "GPay", "Camera", "Messages", "Chrome", "WhatsApp", "Spotify", "Settings"))
}

class LauncherRepository(private var context: Context? = null) {

  fun setContext(ctx: Context) {
    if (this.context == null) {
      this.context = ctx.applicationContext
    }
  }

  fun hasContext(): Boolean = this.context != null

  private val footprintCache: MutableMap<String, AppFootprint?> =
    Collections.synchronizedMap(hashMapOf<String, AppFootprint?>())

  private val prefs: SharedPreferences?
    get() = context?.getSharedPreferences("edith_launcher_prefs", Context.MODE_PRIVATE)

  fun getPinnedPackages(): Set<String> {
    val saved = prefs?.getStringSet(KEY_PINNED_PACKAGES, null)
    if (!saved.isNullOrEmpty()) return saved
    return DEFAULT_ESSENTIAL_KEYWORDS
  }

  fun togglePinPackage(packageName: String): Set<String> {
    val current = getPinnedPackages().toMutableSet()
    if (current.contains(packageName)) {
      current.remove(packageName)
    } else {
      current.add(packageName)
    }
    prefs?.edit()?.putStringSet(KEY_PINNED_PACKAGES, current)?.apply()
    return current
  }

  fun getLauncherSettings(): LauncherSettingsConfig {
    val p = prefs ?: return LauncherSettingsConfig()
    val accentName = p.getString(KEY_LAUNCHER_ACCENT, null)
    val accent = LauncherAccentPreset.entries.firstOrNull { it.name == accentName }
      ?: LauncherAccentPreset.ICE_CYAN
    val fontName = p.getString(KEY_LAUNCHER_FONT, null)
    val font = LauncherFontStyle.entries.firstOrNull { it.name == fontName }
      ?: LauncherFontStyle.DEFAULT
    val fontScale = p.getFloat(KEY_LAUNCHER_FONT_SCALE, 1.0f).coerceIn(0.85f, 1.25f)
    val use24Hour = p.getBoolean(KEY_USE_24_HOUR, true)
    return LauncherSettingsConfig(
      accentPreset = accent,
      fontStyle = font,
      fontScale = fontScale,
      use24Hour = use24Hour,
    )
  }

  fun saveLauncherSettings(config: LauncherSettingsConfig) {
    prefs?.edit()
      ?.putString(KEY_LAUNCHER_ACCENT, config.accentPreset.name)
      ?.putString(KEY_LAUNCHER_FONT, config.fontStyle.name)
      ?.putFloat(KEY_LAUNCHER_FONT_SCALE, config.fontScale.coerceIn(0.85f, 1.25f))
      ?.putBoolean(KEY_USE_24_HOUR, config.use24Hour)
      ?.apply()
  }

  fun hasUsageStatsPermission(): Boolean {
    val ctx = context ?: return true
    return try {
      val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
      val mode =
        appOps.unsafeCheckOpNoThrow(
          AppOpsManager.OPSTR_GET_USAGE_STATS,
          Process.myUid(),
          ctx.packageName,
        )
      mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Queries foreground usage over the past 14 days using UsageStatsManager.
   */
  private fun queryUsageMapPast14Days(ctx: Context): Map<String, Long> {
    return try {
      val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
      val endTime = System.currentTimeMillis()
      val startTime = endTime - TimeUnit.DAYS.toMillis(14)
      val aggregated = usm.queryAndAggregateUsageStats(startTime, endTime)
      aggregated.mapValues { (_, stats) -> stats.totalTimeInForeground }
    } catch (_: Exception) {
      emptyMap()
    }
  }

  suspend fun loadInstalledApps(): List<AppEntry> =
    withContext(Dispatchers.IO) {
      val ctx = context ?: return@withContext sampleApps()
      val pinnedSet = getPinnedPackages()
      val usageMap = queryUsageMapPast14Days(ctx)
      val result = mutableListOf<AppEntry>()

      try {
        val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val activityList = launcherApps?.getActivityList(null, Process.myUserHandle()).orEmpty()
        if (activityList.isNotEmpty()) {
          for (info in activityList) {
            val pkg = info.componentName.packageName
            if (pkg == ctx.packageName) continue
            val label = info.label?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            val appFlags = info.applicationInfo.flags
            val isSystem = (appFlags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isMoto = pkg.startsWith("com.motorola") || pkg.startsWith("com.dolby")
            val isEssential = isEssentialApp(pkg, label)
            val isPinned = isEssential || pinnedSet.contains(pkg)
            val usage14d = usageMap[pkg] ?: 0L

            result.add(
              AppEntry(
                label = label,
                packageName = pkg,
                activityName = info.componentName.className,
                icon = runCatching { info.getBadgedIcon(0) }.getOrNull(),
                isSystemApp = isSystem,
                isMotoApp = isMoto,
                isPinned = isPinned,
                usageTimeMillis14d = usage14d,
                isEssentialQuickAccess = isEssential,
                androidCategory = info.applicationInfo.category,
              )
            )
          }
        } else {
          val pm = ctx.packageManager
          val mainIntent =
            Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
          val resolves = pm.queryIntentActivities(mainIntent, 0)
          for (ri in resolves) {
            val pkg = ri.activityInfo.packageName
            if (pkg == ctx.packageName) continue
            val label = ri.loadLabel(pm).toString().ifBlank { pkg }
            val isSystem = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isMoto = pkg.startsWith("com.motorola") || pkg.startsWith("com.dolby")
            val isEssential = isEssentialApp(pkg, label)
            val isPinned = isEssential || pinnedSet.contains(pkg)
            val usage14d = usageMap[pkg] ?: 0L

            result.add(
              AppEntry(
                label = label,
                packageName = pkg,
                activityName = ri.activityInfo.name,
                icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                isSystemApp = isSystem,
                isMotoApp = isMoto,
                isPinned = isPinned,
                usageTimeMillis14d = usage14d,
                isEssentialQuickAccess = isEssential,
                androidCategory = ri.activityInfo.applicationInfo.category,
              )
            )
          }
        }
      } catch (_: Exception) {
        return@withContext sampleApps()
      }

      if (result.isEmpty()) sampleApps()
      else result.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

  /**
   * Selects quick-access apps:
   * 1. Essential quick triggers first (Phone, GPay / Wallet, Camera)
   * 2. User-pinned apps
   * 3. Most-used apps from the past 14 days (sorted by foreground time descending)
   */
  fun selectQuickAccessApps(allApps: List<AppEntry>, maxCount: Int = 8): List<AppEntry> {
    if (allApps.isEmpty()) return sampleApps().take(maxCount)

    val ordered =
      allApps.sortedWith(
        compareByDescending<AppEntry> { it.isEssentialQuickAccess }
          .thenByDescending { it.isPinned }
          .thenByDescending { it.usageTimeMillis14d }
          .thenBy { essentialPriorityRank(it.packageName, it.label) }
          .thenBy { it.label.lowercase() }
      )
    return ordered.take(maxCount)
  }

  private fun isEssentialApp(pkg: String, label: String): Boolean {
    val p = pkg.lowercase()
    val l = label.lowercase()
    return p in ESSENTIAL_PACKAGES ||
      l == "phone" ||
      l == "gpay" ||
      l == "google pay" ||
      l == "wallet" ||
      l == "camera" ||
      p.contains("dialer") ||
      p.contains("nbu.paisa") ||
      p.contains("tez") ||
      p.contains("camera3")
  }

  private fun essentialPriorityRank(pkg: String, label: String): Int {
    val p = pkg.lowercase()
    val l = label.lowercase()
    return when {
      l.contains("phone") || p.contains("dialer") -> 0
      l.contains("gpay") || l.contains("google pay") || p.contains("paisa") || p.contains("wallet") -> 1
      l.contains("camera") || p.contains("camera") -> 2
      l.contains("messages") || p.contains("messaging") || p.contains("whatsapp") -> 3
      l.contains("chrome") || p.contains("browser") -> 4
      l.contains("youtube") || l.contains("spotify") -> 5
      else -> 10
    }
  }

  fun readTelemetry(): DeviceTelemetry {
    val ctx = context ?: return DeviceTelemetry()
    
    // 1. Direct BatteryManager query (fast, driver-level, no broadcast receiver required)
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val bmCapacity = try {
      bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) {
      null
    }
    val bmIsCharging = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        bm?.isCharging == true
      } else false
    } catch (_: Exception) {
      false
    }

    // 2. Sticky battery changed broadcast fallback
    val batteryIntent = try {
      val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ctx.registerReceiver(null, filter, Context.RECEIVER_EXPORTED)
      } else {
        ctx.registerReceiver(null, filter)
      }
    } catch (_: Exception) {
      null
    }

    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isChargingIntent =
      status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL

    val isCharging = bmIsCharging || isChargingIntent
    val pct = when {
      bmCapacity != null && bmCapacity in 0..100 -> bmCapacity
      level >= 0 && scale > 0 -> ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
      else -> 84
    }

    // 3. RAM telemetry
    var totalGb = 8.0f
    var availGb = 5.2f
    try {
      val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val memInfo = ActivityManager.MemoryInfo()
      am?.getMemoryInfo(memInfo)
      if (memInfo.totalMem > 0) totalGb = memInfo.totalMem / (1024f * 1024f * 1024f)
      if (memInfo.availMem > 0) availGb = memInfo.availMem / (1024f * 1024f * 1024f)
    } catch (_: Exception) {
    }

    // 4. Refresh rate telemetry
    val hz = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate?.roundToInt() ?: 120
      } else {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.refreshRate?.roundToInt() ?: 120
      }
    } catch (_: Exception) {
      120
    }

    return DeviceTelemetry(
      batteryPct = pct,
      isCharging = isCharging,
      availableRamGb = (availGb * 10f).roundToInt() / 10f,
      totalRamGb = (totalGb * 10f).roundToInt() / 10f,
      refreshRateHz = if (hz >= 90) hz else 120,
      deviceModel = "Moto G45 5G",
      hasUsageAccess = hasUsageStatsPermission(),
    )
  }

  fun observeTelemetry(): Flow<DeviceTelemetry> = callbackFlow {
    val ctx = context
    if (ctx == null) {
      trySend(DeviceTelemetry())
      close()
      return@callbackFlow
    }

    // Emit current state immediately
    trySend(readTelemetry())

    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context?, intent: Intent?) {
        trySend(readTelemetry())
      }
    }

    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_BATTERY_CHANGED)
      addAction(Intent.ACTION_POWER_CONNECTED)
      addAction(Intent.ACTION_POWER_DISCONNECTED)
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
      } else {
        ctx.registerReceiver(receiver, filter)
      }
    } catch (_: Exception) {
      // In case registration fails, initial telemetry is already sent
    }

    awaitClose {
      try {
        ctx.unregisterReceiver(receiver)
      } catch (_: Exception) {
      }
    }
  }

  fun isDefaultLauncher(): Boolean {
    val ctx = context ?: return true
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = ctx.getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
          return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }
      }
      val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
      val resolveInfo = ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
      resolveInfo?.activityInfo?.packageName == ctx.packageName
    } catch (_: Exception) {
      false
    }
  }

  /**
   * APK / user-data / cache bytes for a package. Gated by the same usage-access
   * app-op as the 14-day stats, so it yields null when access is off. Results — including
   * misses — are cached: the query costs tens of milliseconds and the deck asks per card.
   */
  fun footprint(packageName: String): AppFootprint? {
    if (footprintCache.containsKey(packageName)) return footprintCache[packageName]
    val ctx = context ?: return null
    val stats = try {
      (ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager)
        ?.queryStatsForPackage(StorageManager.UUID_DEFAULT, packageName, Process.myUserHandle())
    } catch (_: Exception) {
      null
    }
    val footprint = stats?.let {
      AppFootprint(appBytes = it.appBytes, dataBytes = it.dataBytes, cacheBytes = it.cacheBytes)
    }
    footprintCache[packageName] = footprint
    return footprint
  }

  /** Clears cached storage measurements for [packageName], or all packages when null. */
  fun invalidateFootprintCache(packageName: String? = null) {
    if (packageName == null) footprintCache.clear() else footprintCache.remove(packageName)
  }

  /**
   * Hands the system an explicit empty transition: no zoom-from-icon, no cross-fade, and no exit
   * animation on our own window either. The app appears at full size on the frame it is ready.
   */
  private fun noTransition(ctx: Context): Bundle =
    ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle()

  fun launchApp(app: AppEntry): Boolean {
    val ctx = context ?: return false
    return try {
      val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
      if (launcherApps != null && app.activityName.isNotBlank()) {
        val component = ComponentName(app.packageName, app.activityName)
        launcherApps.startMainActivity(component, Process.myUserHandle(), null, noTransition(ctx))
        true
      } else {
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
          launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          ctx.startActivity(launchIntent, noTransition(ctx))
          true
        } else {
          false
        }
      }
    } catch (_: Exception) {
      try {
        val fallback = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
        if (fallback != null) {
          fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          ctx.startActivity(fallback, noTransition(ctx))
          true
        } else {
          false
        }
      } catch (_: Exception) {
        false
      }
    }
  }

  fun openAppInfo(app: AppEntry) {
    val ctx = context ?: return
    runCatching {
      val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.fromParts("package", app.packageName, null)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      ctx.startActivity(intent)
    }
  }

  fun requestUninstall(app: AppEntry) {
    val ctx = context ?: return
    runCatching {
      val intent =
        Intent(Intent.ACTION_DELETE).apply {
          data = Uri.fromParts("package", app.packageName, null)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      ctx.startActivity(intent)
    }
  }

  fun openHomeSettings() {
    val ctx = context ?: return
    runCatching {
      val intent =
        Intent(Settings.ACTION_HOME_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      ctx.startActivity(intent)
    }.onFailure {
      runCatching {
        ctx.startActivity(
          Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
      }
    }
  }

  fun openUsageAccessSettings() {
    val ctx = context ?: return
    runCatching {
      val intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
          data = Uri.fromParts("package", ctx.packageName, null)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      ctx.startActivity(intent)
    }.onFailure {
      runCatching {
        ctx.startActivity(
          Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        )
      }
    }
  }

  fun openSystemClockOrAlarms() {
    val ctx = context ?: return
    runCatching {
      val clockPackages =
        listOf(
          "com.google.android.deskclock",
          "com.android.deskclock",
          "com.motorola.timeweatherwidget",
        )
      for (pkg in clockPackages) {
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
          launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          ctx.startActivity(launchIntent)
          return
        }
      }
      ctx.startActivity(
        Intent(Settings.ACTION_DATE_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      )
    }
  }

  fun hasNotificationListenerAccess(): Boolean {
    val ctx = context ?: return false
    return com.nexwork.edith.notifications.EdithNotificationListener.isAccessGranted(ctx)
  }

  fun openNotificationListenerSettings() {
    val ctx = context ?: return
    runCatching {
      ctx.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      )
    }
  }

  fun expandNotificationShade() {
    val ctx = context ?: return
    runCatching {
      val statusBarService = ctx.getSystemService("statusbar") ?: return
      val statusBarManager = Class.forName("android.app.StatusBarManager")
      val expand = statusBarManager.getMethod("expandNotificationsPanel")
      expand.invoke(statusBarService)
    }
  }

  /** Opens the Android internet connectivity panel, falling back to Wi-Fi settings. */
  fun openWifiSettings() {
    startSettingsAction(
      primaryAction = Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
      fallbackAction = Settings.ACTION_WIFI_SETTINGS,
    )
  }

  /** Opens system Bluetooth settings. */
  fun openBluetoothSettings() {
    startSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS)
  }

  /** Opens system Display settings. */
  fun openDisplaySettings() {
    startSettingsAction(Settings.ACTION_DISPLAY_SETTINGS)
  }

  /** Opens Do Not Disturb priority settings, falling back to Sound settings. */
  fun openDndSettings() {
    startSettingsAction(
      primaryAction = Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
      fallbackAction = Settings.ACTION_SOUND_SETTINGS,
    )
  }

  /** Opens [query] directly when it is a web URL, or launches a Google search in the browser. */
  fun openWebSearch(query: String) {
    val ctx = context ?: return
    val targetUrl = resolveWebSearchUrl(query) ?: return
    runCatching {
      val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      ctx.startActivity(intent)
    }
  }

  private fun startSettingsAction(primaryAction: String, fallbackAction: String? = null) {
    val ctx = context ?: return
    runCatching {
      ctx.startActivity(Intent(primaryAction).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }.onFailure {
      if (fallbackAction != null) {
        runCatching {
          ctx.startActivity(Intent(fallbackAction).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
      }
    }
  }

  companion object {
    private const val KEY_PINNED_PACKAGES = "pinned_packages"
    private const val KEY_LAUNCHER_ACCENT = "launcher_accent_preset"
    private const val KEY_LAUNCHER_FONT = "launcher_font_style"
    private const val KEY_LAUNCHER_FONT_SCALE = "launcher_font_scale"
    private const val KEY_USE_24_HOUR = "launcher_use_24_hour"

    private val BARE_DOMAIN_REGEX =
      Regex("""^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+([/?#].*)?$""")

    internal fun resolveWebSearchUrl(query: String): String? {
      val trimmed = query.trim()
      if (trimmed.isEmpty()) return null
      return when {
        trimmed.startsWith("https://", ignoreCase = true) ->
          "https://" + trimmed.substring(8)
        trimmed.startsWith("http://", ignoreCase = true) ->
          "http://" + trimmed.substring(7)
        !trimmed.contains(' ') && BARE_DOMAIN_REGEX.matches(trimmed) ->
          "https://$trimmed"
        else ->
          "https://www.google.com/search?q=${URLEncoder.encode(trimmed, "UTF-8")}"
      }
    }

    private val DEFAULT_ESSENTIAL_KEYWORDS =
      setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.google.android.apps.nbu.paisa.user",
        "com.google.android.apps.walletnfcrel",
        "com.motorola.camera3",
        "com.motorola.camera2",
        "com.google.android.apps.messaging",
        "com.android.chrome",
      )

    private val ESSENTIAL_PACKAGES =
      setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.google.android.apps.nbu.paisa.user",
        "com.google.android.apps.walletnfcrel",
        "com.motorola.camera3",
        "com.motorola.camera2",
        "com.android.camera2",
      )

    fun sampleApps(): List<AppEntry> =
      listOf(
        AppEntry(
          label = "Phone",
          packageName = "com.google.android.dialer",
          activityName = "",
          isPinned = true,
          isEssentialQuickAccess = true,
          usageTimeMillis14d = 18_400_000L,
        ),
        AppEntry(
          label = "GPay",
          packageName = "com.google.android.apps.nbu.paisa.user",
          activityName = "",
          isPinned = true,
          isEssentialQuickAccess = true,
          usageTimeMillis14d = 9_200_000L,
        ),
        AppEntry(
          label = "Camera",
          packageName = "com.motorola.camera3",
          activityName = "",
          isMotoApp = true,
          isPinned = true,
          isEssentialQuickAccess = true,
          usageTimeMillis14d = 7_500_000L,
        ),
        AppEntry(
          label = "Messages",
          packageName = "com.google.android.apps.messaging",
          activityName = "",
          isPinned = true,
          usageTimeMillis14d = 14_100_000L,
        ),
        AppEntry(
          label = "Chrome",
          packageName = "com.android.chrome",
          activityName = "",
          isPinned = true,
          usageTimeMillis14d = 22_000_000L,
        ),
        AppEntry(
          label = "Spotify",
          packageName = "com.spotify.music",
          activityName = "",
          usageTimeMillis14d = 19_000_000L,
        ),
        AppEntry(
          label = "YouTube",
          packageName = "com.google.android.youtube",
          activityName = "",
          usageTimeMillis14d = 31_000_000L,
        ),
        AppEntry(
          label = "Dolby Atmos",
          packageName = "com.dolby.daxappui",
          activityName = "",
          isMotoApp = true,
          usageTimeMillis14d = 3_200_000L,
        ),
        AppEntry(
          label = "Moto",
          packageName = "com.motorola.moto",
          activityName = "",
          isMotoApp = true,
          usageTimeMillis14d = 4_100_000L,
        ),
        AppEntry(
          label = "Settings",
          packageName = "com.android.settings",
          activityName = "",
          isSystemApp = true,
          usageTimeMillis14d = 5_000_000L,
        ),
        AppEntry(
          label = "Files",
          packageName = "com.google.android.apps.nbu.files",
          activityName = "",
          usageTimeMillis14d = 2_400_000L,
        ),
        AppEntry(
          label = "Maps",
          packageName = "com.google.android.apps.maps",
          activityName = "",
          usageTimeMillis14d = 6_800_000L,
        ),
      )
  }
}
