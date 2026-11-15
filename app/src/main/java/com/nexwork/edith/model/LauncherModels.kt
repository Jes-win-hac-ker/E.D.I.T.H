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
