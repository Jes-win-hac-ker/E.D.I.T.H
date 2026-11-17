package com.nexwork.edith.model

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

data class AppEntry(
  val label: String,
  val packageName: String,
  val activityName: String,
  val icon: Drawable? = null,
  val isSystemApp: Boolean = false,
  val isMotoApp: Boolean = false,
  val isPinned: Boolean = false,
  val usageTimeMillis14d: Long = 0L,
  val isEssentialQuickAccess: Boolean = false,
  val androidCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED,
) {
  val initial: Char
    get() = label.trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
}

/** On-device storage split for one package, from StorageStatsManager. */
data class AppFootprint(
  val appBytes: Long,
  val dataBytes: Long,
  val cacheBytes: Long,
) {
  val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

enum class WeatherCondition(val label: String, val tempCelsius: Int) {  PARTLY_CLOUDY("Partly Cloudy", 25),
  CLEAR_SKY("Clear Sky", 28),
  OVERCAST("Overcast", 22),
  LIGHT_RAIN("Light Rain", 20),
}

/** Live weather reading resolved from IP geolocation + Open-Meteo or restored from cache. */
data class LiveWeather(
  val label: String,
  val tempCelsius: Int,
  val city: String = "",
  val fetchedAtMs: Long = 0L,
  val isLive: Boolean = false,
)

data class DeviceTelemetry(
  val batteryPct: Int = 84,
  val isCharging: Boolean = false,
  val availableRamGb: Float = 5.4f,
  val totalRamGb: Float = 8.0f,
  val refreshRateHz: Int = 120,
  val deviceModel: String = "Moto G45 5G",
  val hasUsageAccess: Boolean = false,
)

enum class LauncherAccentPreset(val label: String, val code: String) {
  ICE_CYAN("Arc Reactor", "CYAN"),
  STARK_CRIMSON("Mk LXXXV Crimson", "RED"),
  SOLAR_AMBER("Solar Reactor", "AMBER"),
  VERIDIAN_TAC("Veridian Tac", "TEAL"),
  STEALTH_TITANIUM("Stealth Titanium", "TITAN"),
}

enum class HudIntensityPreset(val label: String, val code: String) {
  STEALTH("Stealth", "LOW"),
  TACTICAL("Tactical", "MID"),
  OVERDRIVE("Overdrive", "MAX"),
}

enum class LauncherFontStyle(
  val label: String,
  val code: String,
  val isProportionalDisplay: Boolean = false,
) {
  IBM_PLEX_MONO("IBM Plex Mono", "PLEX"),
  ORBITRON("Orbitron HUD", "ORBIT", isProportionalDisplay = true),
  HACK_MONO("Hack Terminal", "HACK"),
  PIXELON("Pixelon 8-Bit", "PIXEL", isProportionalDisplay = true),
  KOGNIGEAR("KogniGear Mech", "MECH", isProportionalDisplay = true),
  ESPACION("Espacion Sci-Fi", "ASTRO", isProportionalDisplay = true);

  companion object {
    fun fromPersistedName(name: String?): LauncherFontStyle {
      if (name.isNullOrBlank()) return IBM_PLEX_MONO
      entries.firstOrNull { it.name == name }?.let { return it }
      return when (name) {
        "DEFAULT", "MONOSPACE" -> IBM_PLEX_MONO
        "CLEAN_SANS" -> ORBITRON
        else -> IBM_PLEX_MONO
      }
    }
  }
}

enum class HudFontScope(val label: String, val code: String) {
  ALL_HUD("All HUD", "FULL"),
  HEADERS_CLOCK_ONLY("Headers & Clock", "HEAD"),
}

enum class SettingsStage {
  CLOSED,
  ROOT_BRANCHES,
  LAUNCHER_TREE,
  AGENT_PLACEHOLDER,
  SHIZUKU_PLACEHOLDER,
}

enum class LauncherSettingsSection(val title: String, val tag: String) {
  THEME_COLOR("THEME & COLOR", "ACCENT"),
  FONT_SCALE("FONT & SCALE", "TYPE"),
  CLOCK_DATE("CLOCK & DATE", "TIME"),
  DISPLAY_WEATHER("DISPLAY & WX", "120HZ"),
  PERMS_OTA("PERMS & OTA", "SYSTEM"),
}

enum class JarvisSettingsSection(val title: String, val tag: String) {
  RUNTIME("RUNTIME & SVC", "ENGINE"),
  PERMISSIONS("PERMS & PWR", "SENSES"),
  KEY_VAULT("KEY VAULT", "GEMINI"),
  EVENT_FEED("TRIAGE FEED", "SQLITE"),
  RULEBOOK("RULEBOOK", "RULES"),
}

data class LauncherSettingsConfig(
  val accentPreset: LauncherAccentPreset = LauncherAccentPreset.ICE_CYAN,
  val hudIntensity: HudIntensityPreset = HudIntensityPreset.TACTICAL,
  val fontStyle: LauncherFontStyle = LauncherFontStyle.IBM_PLEX_MONO,
  val fontScope: HudFontScope = HudFontScope.ALL_HUD,
  val fontScale: Float = 1.0f,
  val use24Hour: Boolean = true,
)
