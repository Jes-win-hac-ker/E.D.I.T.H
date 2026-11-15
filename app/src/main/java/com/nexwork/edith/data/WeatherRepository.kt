package com.nexwork.edith.data

import android.content.Context
import android.content.SharedPreferences
import com.nexwork.edith.model.LiveWeather
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Approximate geographic coordinates and city resolved from IP geolocation. */
data class GeoLocation(
  val latitude: Double,
  val longitude: Double,
  val city: String = "",
)

/**
 * Resolves approximate location via IP geolocation (`ipwho.is` with `get.geojs.io` fallback) and
 * fetches current conditions from Open-Meteo with a 30-minute [SharedPreferences] cache.
 */
class WeatherRepository(context: Context? = null) {

  private var appContext: Context? = context?.applicationContext

  fun setContext(ctx: Context) {
    if (appContext == null) {
      appContext = ctx.applicationContext
    }
  }

  private val prefs: SharedPreferences?
    get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** Returns the most recently cached weather snapshot, or null when nothing is stored. */
  fun getCachedWeather(): LiveWeather? {
    val p = prefs ?: return null
    val label = p.getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() } ?: return null
    val fetchedAtMs = p.getLong(KEY_FETCHED_AT_MS, 0L)
    if (fetchedAtMs <= 0L) return null
    val temp = p.getInt(KEY_TEMP_C, Int.MIN_VALUE)
    if (temp == Int.MIN_VALUE) return null
    val city = p.getString(KEY_CITY, "").orEmpty()
    return LiveWeather(
      label = label,
      tempCelsius = temp,
      city = city,
      fetchedAtMs = fetchedAtMs,
      isLive = false,
    )
  }

  /**
   * Returns fresh cached weather within the 30-minute TTL, or fetches live conditions on
   * [Dispatchers.IO]. Falls back gracefully to any existing cached snapshot (or null) when offline.
   */
  suspend fun fetchWeather(forceRefresh: Boolean = false): LiveWeather? =
    withContext(Dispatchers.IO) {
      val cached = getCachedWeather()
      if (!forceRefresh && cached != null && isCacheFresh(cached.fetchedAtMs)) {
        return@withContext cached
      }

      val geo = fetchGeoLocation() ?: return@withContext cached
      val weatherUrl =
        "https://api.open-meteo.com/v1/forecast?latitude=${geo.latitude}&longitude=${geo.longitude}&current=temperature_2m,weather_code"
      val weatherBody = fetchText(weatherUrl) ?: return@withContext cached
      val live = parseOpenMeteoJson(weatherBody, city = geo.city) ?: return@withContext cached
      saveCachedWeather(live)
      live
    }

  private fun fetchGeoLocation(): GeoLocation? {
    for (endpoint in GEO_ENDPOINTS) {
      val body = fetchText(endpoint) ?: continue
      val parsed = parseGeoJson(body)
      if (parsed != null) return parsed
    }
    return null
  }

  private fun fetchText(url: String): String? =
    runCatching {
      val conn =
        (URL(url).openConnection() as HttpURLConnection).apply {
          connectTimeout = TIMEOUT_MS
          readTimeout = TIMEOUT_MS
          instanceFollowRedirects = true
          setRequestProperty("Accept", "application/json")
          setRequestProperty("User-Agent", "EDITH-Launcher")
        }
      try {
        if (conn.responseCode !in 200..299) return@runCatching null
        conn.inputStream.bufferedReader().use { it.readText() }
      } finally {
        conn.disconnect()
      }
    }.getOrNull()

  private fun saveCachedWeather(weather: LiveWeather) {
    prefs
      ?.edit()
      ?.putString(KEY_LABEL, weather.label)
      ?.putInt(KEY_TEMP_C, weather.tempCelsius)
      ?.putString(KEY_CITY, weather.city)
      ?.putLong(KEY_FETCHED_AT_MS, weather.fetchedAtMs)
      ?.apply()
  }

  companion object {
    const val PREFS_NAME = "edith_weather_prefs"
    const val CACHE_TTL_MS = 30L * 60L * 1000L
    const val TIMEOUT_MS = 3_500

    private const val KEY_LABEL = "weather_label"
    private const val KEY_TEMP_C = "weather_temp_c"
    private const val KEY_CITY = "weather_city"
    private const val KEY_FETCHED_AT_MS = "weather_fetched_at_ms"

    private val GEO_ENDPOINTS =
      listOf(
        "https://ipwho.is/",
        "https://get.geojs.io/v1/ip/geo.json",
      )

    /** Returns true when [fetchedAtMs] is within [CACHE_TTL_MS] of [nowMs]. */
    fun isCacheFresh(fetchedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
      if (fetchedAtMs <= 0L) return false
      val age = nowMs - fetchedAtMs
      return age in 0L until CACHE_TTL_MS
    }

    /**
     * Parses geolocation payloads from `ipwho.is` or `get.geojs.io`. Supports both numeric and
     * stringified coordinates and returns null on explicit failure or invalid coordinates.
     */
    fun parseGeoJson(rawJson: String): GeoLocation? {
      val root = MiniJsonParser.parseObject(rawJson) ?: return null
      val success = root["success"]
      if (success == false || (success is String && success.equals("false", ignoreCase = true))) {
        return null
      }
      val lat = asDouble(root["latitude"]) ?: return null
      val lon = asDouble(root["longitude"]) ?: return null
      if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
        return null
      }
      val city =
        (root["city"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
          ?: (root["region"] as? String)?.trim().orEmpty()
      return GeoLocation(latitude = lat, longitude = lon, city = city)
    }

    /**
     * Parses Open-Meteo `current=temperature_2m,weather_code` JSON into a live [LiveWeather].
     */
    @Suppress("UNCHECKED_CAST")
    fun parseOpenMeteoJson(
      rawJson: String,
      city: String = "",
      fetchedAtMs: Long = System.currentTimeMillis(),
    ): LiveWeather? {
      val root = MiniJsonParser.parseObject(rawJson) ?: return null
      val current = root["current"] as? Map<String, Any?> ?: return null
      val temp = asDouble(current["temperature_2m"]) ?: return null
      val code = asDouble(current["weather_code"])?.toInt() ?: return null
      if (!temp.isFinite()) return null
      return LiveWeather(
        label = wmoCodeToLabel(code),
        tempCelsius = temp.roundToInt(),
        city = city.trim(),
        fetchedAtMs = fetchedAtMs,
        isLive = true,
      )
    }

    /** Maps a WMO weather interpretation code to a concise HUD condition label. */
    fun wmoCodeToLabel(code: Int): String =
      when (code) {
        0 -> "Clear Sky"
        1 -> "Mainly Clear"
        2 -> "Partly Cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61 -> "Light Rain"
        63 -> "Moderate Rain"
        65 -> "Heavy Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow Grains"
        80, 81, 82 -> "Rain Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm Hail"
        else -> "Unknown"
      }

    private fun asDouble(value: Any?): Double? =
      when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
      }
  }
}

/**
 * Minimal pure-Kotlin JSON parser so deterministic JSON helpers run identically on Android and on
 * JVM unit tests without `android.jar` `org.json` stubs or external dependencies.
 */
private class MiniJsonParser private constructor(private val src: String) {
  private var pos = 0

  private fun parseValue(): Any? {
    skipWhitespace()
    if (pos >= src.length) error("Unexpected EOF")
    return when (val ch = src[pos]) {
      '{' -> parseObjectInternal()
      '[' -> parseArrayInternal()
      '"' -> parseString()
      't', 'f' -> parseBoolean()
      'n' -> parseNull()
      '-', in '0'..'9' -> parseNumber()
      else -> error("Unexpected char: $ch")
    }
  }

  private fun parseObjectInternal(): Map<String, Any?> {
    pos++ // '{'
    val map = LinkedHashMap<String, Any?>()
    skipWhitespace()
    if (pos < src.length && src[pos] == '}') {
      pos++
      return map
    }
    while (pos < src.length) {
      skipWhitespace()
      if (pos >= src.length || src[pos] != '"') error("Expected string key")
      val key = parseString()
      skipWhitespace()
      if (pos >= src.length || src[pos] != ':') error("Expected ':'")
      pos++
      map[key] = parseValue()
      skipWhitespace()
      if (pos < src.length && src[pos] == ',') {
        pos++
      } else if (pos < src.length && src[pos] == '}') {
        pos++
        return map
      } else {
        error("Expected ',' or '}'")
      }
    }
    error("Unterminated object")
  }

  private fun parseArrayInternal(): List<Any?> {
    pos++ // '['
    val list = ArrayList<Any?>()
    skipWhitespace()
    if (pos < src.length && src[pos] == ']') {
      pos++
      return list
    }
    while (pos < src.length) {
      list.add(parseValue())
      skipWhitespace()
      if (pos < src.length && src[pos] == ',') {
        pos++
      } else if (pos < src.length && src[pos] == ']') {
        pos++
        return list
      } else {
        error("Expected ',' or ']'")
      }
    }
    error("Unterminated array")
  }

  private fun parseString(): String {
    pos++ // '"'
    val sb = StringBuilder()
    while (pos < src.length) {
      val ch = src[pos++]
      when (ch) {
        '"' -> return sb.toString()
        '\\' -> {
          if (pos >= src.length) error("Unterminated escape")
          when (val esc = src[pos++]) {
            '"', '\\', '/' -> sb.append(esc)
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
              if (pos + 4 > src.length) error("Invalid unicode escape")
              sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
              pos += 4
            }
            else -> sb.append(esc)
          }
        }
        else -> sb.append(ch)
      }
    }
    error("Unterminated string")
  }

  private fun parseBoolean(): Boolean =
    if (src.startsWith("true", pos)) {
      pos += 4
      true
    } else if (src.startsWith("false", pos)) {
      pos += 5
      false
    } else {
      error("Invalid boolean")
    }

  private fun parseNull(): Any? {
    if (src.startsWith("null", pos)) {
      pos += 4
      return null
    }
    error("Invalid null")
  }

  private fun parseNumber(): Number {
    val start = pos
    if (src[pos] == '-') pos++
    while (pos < src.length && src[pos].isDigit()) pos++
    var isDecimal = false
    if (pos < src.length && src[pos] == '.') {
      isDecimal = true
      pos++
      if (pos >= src.length || !src[pos].isDigit()) error("Invalid fraction")
      while (pos < src.length && src[pos].isDigit()) pos++
    }
    if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
      isDecimal = true
      pos++
      if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
      if (pos >= src.length || !src[pos].isDigit()) error("Invalid exponent")
      while (pos < src.length && src[pos].isDigit()) pos++
    }
    val raw = src.substring(start, pos)
    return if (isDecimal) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
  }

  private fun skipWhitespace() {
    while (pos < src.length && src[pos].isWhitespace()) pos++
  }

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun parseObject(raw: String): Map<String, Any?>? =
      runCatching {
        val parser = MiniJsonParser(raw)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (parser.pos != raw.length) return@runCatching null
        value as? Map<String, Any?>
      }.getOrNull()
  }
}
