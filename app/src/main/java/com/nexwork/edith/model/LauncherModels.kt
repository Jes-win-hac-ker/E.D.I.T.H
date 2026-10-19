package com.nexwork.edith.model

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
) {
  val initial: Char
    get() = label.trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
}

enum class WeatherCondition(val label: String, val tempCelsius: Int) {
  PARTLY_CLOUDY("Partly Cloudy", 25),
  CLEAR_SKY("Clear Sky", 28),
  OVERCAST("Overcast", 22),
  LIGHT_RAIN("Light Rain", 20),
}

data class DeviceTelemetry(
  val batteryPct: Int = 84,
  val isCharging: Boolean = false,
  val availableRamGb: Float = 5.4f,
  val totalRamGb: Float = 8.0f,
  val refreshRateHz: Int = 120,
  val deviceModel: String = "Moto G45 5G",
  val hasUsageAccess: Boolean = false,
)
