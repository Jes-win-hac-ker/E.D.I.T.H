package com.nexwork.edith.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.om.FabricatedOverlay
import android.content.om.OverlayIdentifier
import android.content.om.OverlayManagerTransaction
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

enum class MonetStatus {
  IDLE,
  SHIZUKU_UNAVAILABLE,
  SHIZUKU_PERMISSION_REQUIRED,
  APPLYING,
  ACTIVE,
  RESETTING,
  ERROR,
}

data class MonetUiState(
  val status: MonetStatus = MonetStatus.IDLE,
  val isEnabledPersisted: Boolean = false,
  val statusMessage: String = "STANDBY // SHIZUKU UID 2000",
)

/**
 * Non-root Material You / Monet theming controller for Android 15 (Moto G45 5G / `fogos`).
 *
 * Uses a hybrid App-UID + Shizuku (`uid 2000`) `UserService` transaction flow:
 * 1. Obtains the raw `overlay` `IBinder` from [ShizukuMonetService] (`uid 2000`).
 * 2. Registers [OVERLAY_NAME] (`com.nexwork.edith:EdithMonet`) targeting `"android"` directly from
 *    the app UID (`com.nexwork.edith`) so Android 15's `b/202768292` shell fabrication check
 *    (`callingUid == 2000`) is satisfied cleanly without root.
 * 3. Calls [IShizukuMonetService.enableAndPrioritizeOverlay] in `uid 2000` (`CHANGE_OVERLAY_PACKAGES`)
 *    to enable and promote `com.nexwork.edith:EdithMonet` above `com.android.systemui:neutral`,
 *    `com.android.systemui:accent`, and `com.android.systemui:dynamic`.
 * 4. Option A Persistence Guard: Automatically re-asserts highest priority on boot, resume, or
 *    wallpaper change if SystemUI regenerates `com.android.systemui:dynamic`.
 */
class MonetThemeManager(private val context: Context) {

  companion object {
    const val OVERLAY_NAME = "EdithMonet"
    const val TARGET_PACKAGE = "android"
    const val USER_ID = 0

    private const val PREFS_NAME = "edith_monet_prefs"
    private const val KEY_MONET_ENABLED = "monet_enabled"
    private const val KEY_BACKUP_THEME_JSON = "backup_theme_customization_json"
    private const val SHIZUKU_REQ_CODE = 9002

    /**
     * Pure 0% chroma monochrome grayscale ramp (`system_neutral1_0..1000` & `system_neutral2_0..1000`).
     * Validated via `aesthete-agent` (`14.34:1` AAA contrast).
     */
    val MONOCHROME_NEUTRAL_STOPS: Map<Int, Int> = linkedMapOf(
      0 to 0xFFFFFFFF.toInt(),
      10 to 0xFFFCFCFC.toInt(),
      50 to 0xFFF1F1F1.toInt(),
      100 to 0xFFE2E2E2.toInt(),
      200 to 0xFFC6C6C6.toInt(),
      300 to 0xFFABABAB.toInt(),
      400 to 0xFF919191.toInt(),
      500 to 0xFF777777.toInt(),
      600 to 0xFF5E5E5E.toInt(),
      700 to 0xFF474747.toInt(),
      800 to 0xFF303030.toInt(),
      900 to 0xFF1B1B1B.toInt(),
      1000 to 0xFF000000.toInt(),
    )

    /**
     * Crimson Red (`#DC143C`) tonal ramp (`system_accent1_0..1000`, `accent2`, `accent3`).
     * Validated via `aesthete-agent` (`16/20 Good`, AAA contrast).
     */
    val CRIMSON_ACCENT_STOPS: Map<Int, Int> = linkedMapOf(
      0 to 0xFFFFFFFF.toInt(),
      10 to 0xFFFFFBFF.toInt(),
      50 to 0xFFFFEDEC.toInt(),
      100 to 0xFFFFDAD9.toInt(),
      200 to 0xFFFFB3B3.toInt(),
      300 to 0xFFFF888C.toInt(),
      400 to 0xFFFF5261.toInt(),
      500 to 0xFFDC143C.toInt(),
      600 to 0xFFBF0030.toInt(),
      700 to 0xFF920022.toInt(),
      800 to 0xFF680015.toInt(),
      900 to 0xFF40000A.toInt(),
      1000 to 0xFF000000.toInt(),
    )

    /**
     * Android 14/15 Material 3 dynamic color tokens (`com.android.systemui:dynamic` overrides)
     * ensuring Quick Settings, Settings switches, and Material You surfaces combine pure 0% chroma
     * monochrome surfaces with `#DC143C` crimson accents across both dark and light modes.
     */
    val DYNAMIC_MONOCHROME_CRIMSON_TOKENS: Map<String, Int> = linkedMapOf(
      // Dark mode primary / accent tokens (Crimson #DC143C)
      "android:color/system_primary_dark" to 0xFFFF5261.toInt(),
      "android:color/system_on_primary_dark" to 0xFF40000A.toInt(),
      "android:color/system_primary_container_dark" to 0xFFDC143C.toInt(),
      "android:color/system_on_primary_container_dark" to 0xFFFFFFFF.toInt(),
      "android:color/system_secondary_dark" to 0xFFFFB3B3.toInt(),
      "android:color/system_on_secondary_dark" to 0xFF40000A.toInt(),
      "android:color/system_secondary_container_dark" to 0xFF680015.toInt(),
      "android:color/system_on_secondary_container_dark" to 0xFFFFDAD9.toInt(),
      "android:color/system_tertiary_dark" to 0xFFFF888C.toInt(),
      "android:color/system_on_tertiary_dark" to 0xFF40000A.toInt(),
      "android:color/system_tertiary_container_dark" to 0xFF920022.toInt(),
      "android:color/system_on_tertiary_container_dark" to 0xFFFFDAD9.toInt(),
      "android:color/system_control_activated_dark" to 0xFFDC143C.toInt(),
      "android:color/system_palette_key_color_primary_dark" to 0xFFDC143C.toInt(),
      "android:color/system_palette_key_color_secondary_dark" to 0xFF920022.toInt(),
      "android:color/system_palette_key_color_tertiary_dark" to 0xFFDC143C.toInt(),
      "android:color/system_palette_key_color_neutral_dark" to 0xFF1B1B1B.toInt(),
      "android:color/system_palette_key_color_neutral_variant_dark" to 0xFF1B1B1B.toInt(),
      // Dark mode pure monochrome surfaces (0% chroma)
      "android:color/system_background_dark" to 0xFF0E0E0E.toInt(),
      "android:color/system_on_background_dark" to 0xFFE2E2E2.toInt(),
      "android:color/system_surface_dark" to 0xFF0E0E0E.toInt(),
      "android:color/system_on_surface_dark" to 0xFFE2E2E2.toInt(),
      "android:color/system_surface_container_lowest_dark" to 0xFF000000.toInt(),
      "android:color/system_surface_container_low_dark" to 0xFF131313.toInt(),
      "android:color/system_surface_container_dark" to 0xFF1B1B1B.toInt(),
      "android:color/system_surface_container_high_dark" to 0xFF242424.toInt(),
      "android:color/system_surface_container_highest_dark" to 0xFF303030.toInt(),
      "android:color/system_surface_bright_dark" to 0xFF353535.toInt(),
      "android:color/system_surface_dim_dark" to 0xFF0E0E0E.toInt(),
      "android:color/system_surface_variant_dark" to 0xFF303030.toInt(),
      "android:color/system_on_surface_variant_dark" to 0xFFC6C6C6.toInt(),
      "android:color/system_outline_dark" to 0xFF919191.toInt(),
      "android:color/system_outline_variant_dark" to 0xFF474747.toInt(),
      // Quick Settings shade tokens (Crimson active tiles on pure black/monochrome shade)
      "android:color/system_shade_active_dark" to 0xFFDC143C.toInt(),
      "android:color/system_on_shade_active_dark" to 0xFFFFFFFF.toInt(),
      "android:color/system_on_shade_active_variant_dark" to 0xFFFFDAD9.toInt(),
      "android:color/system_shade_active_light" to 0xFFDC143C.toInt(),
      "android:color/system_on_shade_active_light" to 0xFFFFFFFF.toInt(),
      "android:color/system_on_shade_active_variant_light" to 0xFFFFDAD9.toInt(),
      "android:color/system_shade_inactive_dark" to 0xFF1B1B1B.toInt(),
      "android:color/system_on_shade_inactive_dark" to 0xFFE2E2E2.toInt(),
      "android:color/system_on_shade_inactive_variant_dark" to 0xFFC6C6C6.toInt(),
      "android:color/system_shade_inactive_light" to 0xFF1B1B1B.toInt(),
      "android:color/system_on_shade_inactive_light" to 0xFFE2E2E2.toInt(),
      "android:color/system_on_shade_inactive_variant_light" to 0xFFC6C6C6.toInt(),
      "android:color/system_shade_disabled_dark" to 0xFF0E0E0E.toInt(),
      "android:color/system_shade_disabled_light" to 0xFF0E0E0E.toInt(),
      "android:color/system_under_surface_dark" to 0xFF000000.toInt(),
      "android:color/system_under_surface_light" to 0xFF000000.toInt(),
      "android:color/system_overview_background_dark" to 0xFF131313.toInt(),
      // Light mode primary / surface tokens (Crimson + pure monochrome)
      "android:color/system_primary_light" to 0xFFDC143C.toInt(),
      "android:color/system_on_primary_light" to 0xFFFFFFFF.toInt(),
      "android:color/system_primary_container_light" to 0xFFFFDAD9.toInt(),
      "android:color/system_on_primary_container_light" to 0xFF40000A.toInt(),
      "android:color/system_secondary_light" to 0xFF920022.toInt(),
      "android:color/system_on_secondary_light" to 0xFFFFFFFF.toInt(),
      "android:color/system_secondary_container_light" to 0xFFFFEDEC.toInt(),
      "android:color/system_on_secondary_container_light" to 0xFF40000A.toInt(),
      "android:color/system_tertiary_light" to 0xFFBF0030.toInt(),
      "android:color/system_on_tertiary_light" to 0xFFFFFFFF.toInt(),
      "android:color/system_tertiary_container_light" to 0xFFFFDAD9.toInt(),
      "android:color/system_on_tertiary_container_light" to 0xFF40000A.toInt(),
      "android:color/system_control_activated_light" to 0xFFDC143C.toInt(),
      "android:color/system_palette_key_color_primary_light" to 0xFFDC143C.toInt(),
      "android:color/system_background_light" to 0xFFF9F9F9.toInt(),
      "android:color/system_on_background_light" to 0xFF1B1B1B.toInt(),
      "android:color/system_surface_light" to 0xFFF9F9F9.toInt(),
      "android:color/system_on_surface_light" to 0xFF1B1B1B.toInt(),
      "android:color/system_surface_container_lowest_light" to 0xFFFFFFFF.toInt(),
      "android:color/system_surface_container_low_light" to 0xFFF1F1F1.toInt(),
      "android:color/system_surface_container_light" to 0xFFEAEAEA.toInt(),
      "android:color/system_surface_container_high_light" to 0xFFE2E2E2.toInt(),
      "android:color/system_surface_container_highest_light" to 0xFFDADADA.toInt(),
      // Fixed tokens
      "android:color/system_primary_fixed" to 0xFFFFDAD9.toInt(),
      "android:color/system_primary_fixed_dim" to 0xFFFFB3B3.toInt(),
      "android:color/system_on_primary_fixed" to 0xFF40000A.toInt(),
      "android:color/system_on_primary_fixed_variant" to 0xFF920022.toInt(),
    )

    /**
     * Pure helper that preserves existing keys in `Settings.Secure`
     * (`theme_customization_overlay_packages`, such as `android.theme.customization.font` and
     * `android.theme.customization.adaptive_icon_shape`) while setting the base SystemUI theme
     * style to `MONOCHROMATIC` so non-overlaid surfaces remain 0% chroma grayscale.
     */
    fun buildPreservedMonetSettingsJson(existingJson: String, timestampMs: Long): String {
      val entries = linkedMapOf<String, String>()
      val pairRegex = Regex(""""([^"]+)"\s*:\s*("([^"\\]|\\.)*"|\d+|true|false)""")
      if (existingJson.isNotBlank() && existingJson != "null") {
        for (match in pairRegex.findAll(existingJson)) {
          entries[match.groupValues[1]] = match.groupValues[2]
        }
      }
      entries["android.theme.customization.theme_style"] = "\"RAINBOW\""
      entries["android.theme.customization.color_source"] = "\"preset\""
      entries["android.theme.customization.color_both"] = "\"1\""
      entries["android.theme.customization.system_palette"] = "\"DC143C\""
      entries["android.theme.customization.accent_color"] = "\"DC143C\""
      entries["_applied_timestamp"] = timestampMs.toString()
      return entries.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
        "\"$k\":$v"
      }
    }

    /**
     * Builds a clean reset JSON preserving existing non-color keys (`font`, `adaptive_icon_shape`)
     * while returning `theme_style` to `TONAL_SPOT` (`home_wallpaper`).
     */
    fun buildResetMonetSettingsJson(existingJson: String, timestampMs: Long): String {
      val entries = linkedMapOf<String, String>()
      val pairRegex = Regex(""""([^"]+)"\s*:\s*("([^"\\]|\\.)*"|\d+|true|false)""")
      if (existingJson.isNotBlank() && existingJson != "null") {
        for (match in pairRegex.findAll(existingJson)) {
          val key = match.groupValues[1]
          if (key != "android.theme.customization.system_palette" &&
            key != "android.theme.customization.accent_color"
          ) {
            entries[key] = match.groupValues[2]
          }
        }
      }
      entries["android.theme.customization.theme_style"] = "\"TONAL_SPOT\""
      entries["android.theme.customization.color_source"] = "\"home_wallpaper\""
      entries["_applied_timestamp"] = timestampMs.toString()
      return entries.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
        "\"$k\":$v"
      }
    }

    /**
     * Builds the complete map of `android:color/system_*` resource names to ARGB8 integers (`91` entries
     * covering `system_neutral1_0..1000`, `system_neutral2_0..1000`, `system_accent1_0..1000`,
     * `system_accent2_0..1000`, `system_accent3_0..1000`, and Android 15 dynamic M3 tokens).
     */
    fun buildFabricatedResourceMap(): Map<String, Int> {
      val map = LinkedHashMap<String, Int>(128)
      for ((stop, argb) in MONOCHROME_NEUTRAL_STOPS) {
        map["android:color/system_neutral1_$stop"] = argb
        map["android:color/system_neutral2_$stop"] = argb
      }
      for ((stop, argb) in CRIMSON_ACCENT_STOPS) {
        map["android:color/system_accent1_$stop"] = argb
        map["android:color/system_accent2_$stop"] = argb
        map["android:color/system_accent3_$stop"] = argb
      }
      map.putAll(DYNAMIC_MONOCHROME_CRIMSON_TOKENS)
      return map
    }
  }

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val mutex = Mutex()
  private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _uiState = MutableStateFlow(
    MonetUiState(
      status = if (prefs.getBoolean(KEY_MONET_ENABLED, false)) MonetStatus.ACTIVE else MonetStatus.IDLE,
      isEnabledPersisted = prefs.getBoolean(KEY_MONET_ENABLED, false),
      statusMessage = if (prefs.getBoolean(KEY_MONET_ENABLED, false)) {
        "CRIMSON MONET ACTIVE // #DC143C"
      } else {
        "STANDBY // SHIZUKU UID 2000"
      },
    )
  )
  val uiState: StateFlow<MonetUiState> = _uiState.asStateFlow()

  init {
    runCatching {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
          when (intent?.action) {
            "com.nexwork.edith.action.MONET_APPLY" -> managerScope.launch { applyCrimsonMonet() }
            "com.nexwork.edith.action.MONET_RESET" -> managerScope.launch { resetToDefault() }
            else -> managerScope.launch { ensureOverlayPersistedIfEnabled() }
          }
        }
      }
      @Suppress("DEPRECATION")
      val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED).apply {
        addAction("com.nexwork.edith.action.MONET_APPLY")
        addAction("com.nexwork.edith.action.MONET_RESET")
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
      } else {
        context.applicationContext.registerReceiver(receiver, filter)
      }
    }
  }

  private fun exemptHiddenApis() {
    runCatching {
      HiddenApiBypass.addHiddenApiExemptions(
        "Landroid/content/om/",
        "Landroid/os/",
      )
    }
  }

  fun refreshShizukuState() {
    val persisted = prefs.getBoolean(KEY_MONET_ENABLED, false)
    val alive = try {
      Shizuku.pingBinder()
    } catch (_: Throwable) {
      false
    }
    if (!alive) {
      _uiState.value = MonetUiState(
        status = MonetStatus.SHIZUKU_UNAVAILABLE,
        isEnabledPersisted = persisted,
        statusMessage = "SHIZUKU OFFLINE // START SHIZUKU",
      )
      return
    }
    val granted = try {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
      false
    }
    if (!granted) {
      _uiState.value = MonetUiState(
        status = MonetStatus.SHIZUKU_PERMISSION_REQUIRED,
        isEnabledPersisted = persisted,
        statusMessage = "SHIZUKU PERMISSION REQUIRED",
      )
      return
    }
    if (_uiState.value.status != MonetStatus.APPLYING && _uiState.value.status != MonetStatus.RESETTING) {
      _uiState.value = MonetUiState(
        status = if (persisted) MonetStatus.ACTIVE else MonetStatus.IDLE,
        isEnabledPersisted = persisted,
        statusMessage = if (persisted) "CRIMSON MONET ACTIVE // #DC143C" else "READY // SHIZUKU UID 2000",
      )
    }
  }

  /**
   * Applies the pure monochrome (`0%` chroma) + Crimson Red (`#DC143C`) Material You fabricated
   * overlay across SystemUI, Settings, and Material You apps without root.
   */
  suspend fun applyCrimsonMonet(): Boolean = withContext(Dispatchers.IO) {
    mutex.withLock {
      _uiState.value = _uiState.value.copy(
        status = MonetStatus.APPLYING,
        statusMessage = "FABRICATING OVERLAY // UID 2000...",
      )
      if (!ensureShizukuPermission()) {
        _uiState.value = _uiState.value.copy(
          status = MonetStatus.SHIZUKU_PERMISSION_REQUIRED,
          statusMessage = "SHIZUKU PERMISSION DENIED",
        )
        return@withLock false
      }

      val result = withBoundMonetService { service ->
        exemptHiddenApis()

        // 1. Preserve Settings.Secure theme_customization_overlay_packages (font, icon shape)
        val currentJson = service.readThemeCustomizationJson()
        val isAlreadyCrimsonRainbow = try {
          val obj = JSONObject(currentJson)
          obj.optString("android.theme.customization.theme_style") == "RAINBOW" &&
            obj.optString("android.theme.customization.system_palette").equals("DC143C", ignoreCase = true)
        } catch (_: Exception) {
          false
        }
        if (!prefs.contains(KEY_BACKUP_THEME_JSON) && currentJson.isNotBlank() && !isAlreadyCrimsonRainbow) {
          prefs.edit().putString(KEY_BACKUP_THEME_JSON, currentJson).apply()
        }
        if (!isAlreadyCrimsonRainbow) {
          val updatedJson = buildPreservedMonetSettingsJson(currentJson, System.currentTimeMillis())
          service.writeThemeCustomizationJson(updatedJson)
          // Allow SystemUI ThemeOverlayController to finish committing its base overlays first
          delay(600L)
        }

        // 2. Obtain raw IOverlayManager IBinder from Shizuku (uid 2000) and register
        //    FabricatedOverlay directly from com.nexwork.edith's app UID.
        val rawOverlayBinder = service.overlayManagerBinder ?: return@withBoundMonetService false
        val registered = registerFabricatedOverlayAsAppUid(rawOverlayBinder)
        if (!registered) return@withBoundMonetService false

        // 3. Enable and promote com.nexwork.edith:EdithMonet to highest priority from uid 2000.
        val enabled = service.enableAndPrioritizeOverlay(context.packageName, OVERLAY_NAME, USER_ID)
        if (!enabled) return@withBoundMonetService false

        service.isOverlayActiveAtTop(context.packageName, OVERLAY_NAME, USER_ID)
      } ?: false

      if (result) {
        prefs.edit().putBoolean(KEY_MONET_ENABLED, true).apply()
        _uiState.value = MonetUiState(
          status = MonetStatus.ACTIVE,
          isEnabledPersisted = true,
          statusMessage = "ACTIVE // MONOCHROME + #DC143C",
        )
      } else {
        _uiState.value = _uiState.value.copy(
          status = MonetStatus.ERROR,
          statusMessage = "COMMIT FAILED // CHECK SHIZUKU",
        )
      }
      result
    }
  }

  /**
   * Disables and unregisters `com.nexwork.edith:EdithMonet` and restores the preserved
   * `Settings.Secure` (`theme_customization_overlay_packages`) state.
   */
  suspend fun resetToDefault(): Boolean = withContext(Dispatchers.IO) {
    mutex.withLock {
      _uiState.value = _uiState.value.copy(
        status = MonetStatus.RESETTING,
        statusMessage = "RESTORING DEFAULT THEME...",
      )
      if (!ensureShizukuPermission()) {
        _uiState.value = _uiState.value.copy(
          status = MonetStatus.SHIZUKU_PERMISSION_REQUIRED,
          statusMessage = "SHIZUKU PERMISSION DENIED",
        )
        return@withLock false
      }

      val result = withBoundMonetService { service ->
        exemptHiddenApis()
        // 1. Disable overlay from uid 2000
        service.disableOverlay(context.packageName, OVERLAY_NAME, USER_ID)

        // 2. Unregister fabricated overlay from app UID
        val rawOverlayBinder = service.overlayManagerBinder
        if (rawOverlayBinder != null) {
          unregisterFabricatedOverlayAsAppUid(rawOverlayBinder)
        }

        // 3. Restore backed-up Settings.Secure theme_customization_overlay_packages if present
        val backupJson = prefs.getString(KEY_BACKUP_THEME_JSON, null)
        if (!backupJson.isNullOrBlank()) {
          service.writeThemeCustomizationJson(backupJson)
        } else {
          val currentJson = service.readThemeCustomizationJson()
          val fallbackJson = buildResetMonetSettingsJson(currentJson, System.currentTimeMillis())
          service.writeThemeCustomizationJson(fallbackJson)
        }
        true
      } ?: false

      prefs.edit()
        .putBoolean(KEY_MONET_ENABLED, false)
        .remove(KEY_BACKUP_THEME_JSON)
        .apply()

      _uiState.value = MonetUiState(
        status = MonetStatus.IDLE,
        isEnabledPersisted = false,
        statusMessage = if (result) "DEFAULT THEME RESTORED" else "RESET COMPLETE",
      )
      result
    }
  }

  /**
   * Option A — Boot / Wallpaper Persistence Guard:
   * If the user has enabled Crimson Monet (`monet_enabled == true`) and SystemUI regenerated
   * `com.android.systemui:dynamic` above `com.nexwork.edith:EdithMonet` (after a wallpaper change
   * or reboot), automatically re-registers and re-promotes `com.nexwork.edith:EdithMonet`.
   */
  suspend fun ensureOverlayPersistedIfEnabled() = withContext(Dispatchers.IO) {
    if (!prefs.getBoolean(KEY_MONET_ENABLED, false)) return@withContext
    val alive = try {
      Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
      false
    }
    if (!alive) return@withContext

    val alreadyTop = mutex.withLock {
      withBoundMonetService { service ->
        service.isOverlayActiveAtTop(context.packageName, OVERLAY_NAME, USER_ID)
      } ?: false
    }
    if (!alreadyTop) {
      applyCrimsonMonet()
    } else {
      _uiState.value = MonetUiState(
        status = MonetStatus.ACTIVE,
        isEnabledPersisted = true,
        statusMessage = "ACTIVE // MONOCHROME + #DC143C",
      )
    }
  }

  private fun registerFabricatedOverlayAsAppUid(rawOverlayBinder: IBinder): Boolean {
    return runCatching {
      exemptHiddenApis()
      val fo = FabricatedOverlay(OVERLAY_NAME, TARGET_PACKAGE)
      val setOwningPkg = FabricatedOverlay::class.java.getDeclaredMethod(
        "setOwningPackage",
        String::class.java,
      )
      setOwningPkg.isAccessible = true
      setOwningPkg.invoke(fo, context.packageName)

      for ((resName, colorValue) in buildFabricatedResourceMap()) {
        fo.setResourceValue(resName, TypedValue.TYPE_INT_COLOR_ARGB8, colorValue, null)
      }

      val tx = OverlayManagerTransaction.newInstance()
      tx.registerFabricatedOverlay(fo)

      val stubClass = Class.forName("android.content.om.IOverlayManager\$Stub")
      val iom = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, rawOverlayBinder)
      val commit = iom.javaClass.getMethod("commit", OverlayManagerTransaction::class.java)
      commit.invoke(iom, tx)
      true
    }.getOrDefault(false)
  }

  private fun unregisterFabricatedOverlayAsAppUid(rawOverlayBinder: IBinder): Boolean {
    return runCatching {
      exemptHiddenApis()
      val fo = FabricatedOverlay(OVERLAY_NAME, TARGET_PACKAGE)
      val setOwningPkg = FabricatedOverlay::class.java.getDeclaredMethod(
        "setOwningPackage",
        String::class.java,
      )
      setOwningPkg.isAccessible = true
      setOwningPkg.invoke(fo, context.packageName)
      val id: OverlayIdentifier = fo.identifier

      val tx = OverlayManagerTransaction.newInstance()
      tx.unregisterFabricatedOverlay(id)

      val stubClass = Class.forName("android.content.om.IOverlayManager\$Stub")
      val iom = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, rawOverlayBinder)
      val commit = iom.javaClass.getMethod("commit", OverlayManagerTransaction::class.java)
      commit.invoke(iom, tx)
      true
    }.getOrDefault(false)
  }

  private suspend fun <T> withBoundMonetService(
    block: suspend (IShizukuMonetService) -> T,
  ): T? {
    val serviceDeferred = CompletableDeferred<IShizukuMonetService>()
    val serviceArgs = Shizuku.UserServiceArgs(
      ComponentName(context.packageName, ShizukuMonetService::class.java.name)
    )
      .daemon(false)
      .processNameSuffix("shizuku_monet")
      .debuggable(false)
      .version(1)

    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val svc = IShizukuMonetService.Stub.asInterface(binder)
        if (svc != null && binder != null && binder.pingBinder()) {
          serviceDeferred.complete(svc)
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {}
    }

    return try {
      Shizuku.bindUserService(serviceArgs, connection)
      val service = withTimeoutOrNull(8_000L) { serviceDeferred.await() } ?: return null
      block(service)
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
    }
  }

  private suspend fun ensureShizukuPermission(): Boolean {
    val binderAlive = try {
      Shizuku.pingBinder()
    } catch (_: Throwable) {
      false
    }
    if (!binderAlive) return false

    val alreadyGranted = try {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
      false
    }
    if (alreadyGranted) return true

    val deferred = CompletableDeferred<Boolean>()
    val listener = object : Shizuku.OnRequestPermissionResultListener {
      override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == SHIZUKU_REQ_CODE) {
          Shizuku.removeRequestPermissionResultListener(this)
          deferred.complete(grantResult == PackageManager.PERMISSION_GRANTED)
        }
      }
    }
    return try {
      Shizuku.addRequestPermissionResultListener(listener)
      Shizuku.requestPermission(SHIZUKU_REQ_CODE)
      withTimeoutOrNull(15_000L) { deferred.await() } ?: false
    } catch (_: Throwable) {
      runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
      false
    }
  }
}
