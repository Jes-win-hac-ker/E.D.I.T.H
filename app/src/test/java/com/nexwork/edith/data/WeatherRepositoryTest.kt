package com.nexwork.edith.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRepositoryTest {

  @Test
  fun parseGeoJson_parsesIpWhoIsNumericPayload() {
    val json = """
      {
        "ip": "203.0.113.10",
        "success": true,
        "city": "Bengaluru",
        "region": "Karnataka",
        "latitude": 12.9715987,
        "longitude": 77.5945627
      }
    """.trimIndent()

    val geo = WeatherRepository.parseGeoJson(json)
    assertNotNull(geo)
    assertEquals(12.9715987, geo!!.latitude, 1e-6)
    assertEquals(77.5945627, geo.longitude, 1e-6)
    assertEquals("Bengaluru", geo.city)
  }

  @Test
  fun parseGeoJson_parsesGeoJsStringCoordinatesAndRegionFallback() {
    val json = """
      {
        "city": "",
        "region": "Maharashtra",
        "latitude": "19.0760",
        "longitude": "72.8777"
      }
    """.trimIndent()

    val geo = WeatherRepository.parseGeoJson(json)
    assertNotNull(geo)
    assertEquals(19.0760, geo!!.latitude, 1e-4)
    assertEquals(72.8777, geo.longitude, 1e-4)
    assertEquals("Maharashtra", geo.city)
  }

  @Test
  fun parseGeoJson_rejectsFailureOutOfRangeOrMalformed() {
    val failed = """{"success": false, "latitude": 12.9, "longitude": 77.5}"""
    val outOfRange = """{"latitude": 95.0, "longitude": 77.5}"""
    val missingCoords = """{"city": "Bengaluru"}"""
    val malformed = """{"latitude": 12.9,"""

    assertNull(WeatherRepository.parseGeoJson(failed))
    assertNull(WeatherRepository.parseGeoJson(outOfRange))
    assertNull(WeatherRepository.parseGeoJson(missingCoords))
    assertNull(WeatherRepository.parseGeoJson(malformed))
  }

  @Test
  fun parseOpenMeteoJson_parsesCurrentTemperatureAndCode() {
    val json = """
      {
        "latitude": 13.0,
        "longitude": 77.625,
        "current": {
          "time": "2026-11-15T04:00",
          "interval": 900,
          "temperature_2m": 24.6,
          "weather_code": 2
        }
      }
    """.trimIndent()

    val weather = WeatherRepository.parseOpenMeteoJson(
      rawJson = json,
      city = "Bengaluru",
      fetchedAtMs = 1_700_000_000_000L,
    )
    assertNotNull(weather)
    assertEquals("Partly Cloudy", weather!!.label)
    assertEquals(25, weather.tempCelsius)
    assertEquals("Bengaluru", weather.city)
    assertEquals(1_700_000_000_000L, weather.fetchedAtMs)
    assertTrue(weather.isLive)
  }

  @Test
  fun parseOpenMeteoJson_roundsNegativeTemperaturesAndRejectsMissingCurrent() {
    val negativeTemp = """{"current": {"temperature_2m": -3.6, "weather_code": 71}}"""
    val missingCurrent = """{"latitude": 13.0, "longitude": 77.6}"""

    val weather = WeatherRepository.parseOpenMeteoJson(negativeTemp, city = "Oslo", fetchedAtMs = 42L)
    assertNotNull(weather)
    assertEquals(-4, weather!!.tempCelsius)
    assertEquals("Snow", weather.label)
    assertNull(WeatherRepository.parseOpenMeteoJson(missingCurrent))
    assertNull(WeatherRepository.parseOpenMeteoJson("not json"))
  }

  @Test
  fun wmoCodeToLabel_mapsKnownAndUnknownCodes() {
    assertEquals("Clear Sky", WeatherRepository.wmoCodeToLabel(0))
    assertEquals("Mainly Clear", WeatherRepository.wmoCodeToLabel(1))
    assertEquals("Partly Cloudy", WeatherRepository.wmoCodeToLabel(2))
    assertEquals("Overcast", WeatherRepository.wmoCodeToLabel(3))
    assertEquals("Fog", WeatherRepository.wmoCodeToLabel(45))
    assertEquals("Drizzle", WeatherRepository.wmoCodeToLabel(53))
    assertEquals("Freezing Drizzle", WeatherRepository.wmoCodeToLabel(57))
    assertEquals("Light Rain", WeatherRepository.wmoCodeToLabel(61))
    assertEquals("Moderate Rain", WeatherRepository.wmoCodeToLabel(63))
    assertEquals("Heavy Rain", WeatherRepository.wmoCodeToLabel(65))
    assertEquals("Freezing Rain", WeatherRepository.wmoCodeToLabel(66))
    assertEquals("Snow", WeatherRepository.wmoCodeToLabel(75))
    assertEquals("Snow Grains", WeatherRepository.wmoCodeToLabel(77))
    assertEquals("Rain Showers", WeatherRepository.wmoCodeToLabel(81))
    assertEquals("Snow Showers", WeatherRepository.wmoCodeToLabel(86))
    assertEquals("Thunderstorm", WeatherRepository.wmoCodeToLabel(95))
    assertEquals("Thunderstorm Hail", WeatherRepository.wmoCodeToLabel(99))
    assertEquals("Unknown", WeatherRepository.wmoCodeToLabel(999))
  }

  @Test
  fun isCacheFresh_respects30MinuteTtl() {
    val now = 1_700_000_000_000L
    val ttl = WeatherRepository.CACHE_TTL_MS
    assertEquals(30L * 60L * 1000L, ttl)

    assertTrue(WeatherRepository.isCacheFresh(now, now))
    assertTrue(WeatherRepository.isCacheFresh(now - ttl + 1L, now))
    assertFalse(WeatherRepository.isCacheFresh(now - ttl, now))
    assertFalse(WeatherRepository.isCacheFresh(now + 1000L, now))
    assertFalse(WeatherRepository.isCacheFresh(0L, now))
  }
}
