package com.nexwork.edith.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuControlManagerTest {

  @Test
  fun sanitizeComponentToken_acceptsValidPackagesAndComponents() {
    assertTrue(ShizukuControlManager.sanitizeComponentToken("com.nexwork.edith"))
    assertTrue(
      ShizukuControlManager.sanitizeComponentToken(
        "com.nexwork.edith/com.nexwork.edith.notifications.EdithNotificationListener",
      ),
    )
    assertTrue(
      ShizukuControlManager.sanitizeComponentToken(
        "com.nexwork.edith/.notifications.EdithNotificationListener\$Companion",
      ),
    )
  }

  @Test
  fun sanitizeComponentToken_rejectsUnsafeOrMalformedTokens() {
    assertFalse(ShizukuControlManager.sanitizeComponentToken(""))
    assertFalse(ShizukuControlManager.sanitizeComponentToken("   "))
    assertFalse(ShizukuControlManager.sanitizeComponentToken("-rf"))
    assertFalse(ShizukuControlManager.sanitizeComponentToken("com.nexwork.edith; rm -rf /"))
    assertFalse(ShizukuControlManager.sanitizeComponentToken("com.nexwork.edith && id"))
    assertFalse(ShizukuControlManager.sanitizeComponentToken("com.nexwork.edith | cat"))
    assertFalse(ShizukuControlManager.sanitizeComponentToken("com.nexwork/../edith"))
    assertFalse(ShizukuControlManager.sanitizeComponentToken("com.nexwork.edith\nwhoami"))
    assertFalse(ShizukuControlManager.sanitizeComponentToken("a".repeat(257)))
  }

  @Test
  fun parseRefreshRatePreset_clampsAndNormalizesToSupportedPresets() {
    assertEquals(120, ShizukuControlManager.parseRefreshRatePreset(null))
    assertEquals(60, ShizukuControlManager.parseRefreshRatePreset(-10))
    assertEquals(60, ShizukuControlManager.parseRefreshRatePreset(0))
    assertEquals(60, ShizukuControlManager.parseRefreshRatePreset(60))
    assertEquals(60, ShizukuControlManager.parseRefreshRatePreset(74))
    assertEquals(90, ShizukuControlManager.parseRefreshRatePreset(75))
    assertEquals(90, ShizukuControlManager.parseRefreshRatePreset(90))
    assertEquals(90, ShizukuControlManager.parseRefreshRatePreset(104))
    assertEquals(120, ShizukuControlManager.parseRefreshRatePreset(105))
    assertEquals(120, ShizukuControlManager.parseRefreshRatePreset(120))
    assertEquals(120, ShizukuControlManager.parseRefreshRatePreset(144))
  }

  @Test
  fun shizukuActionResult_formatsStatusMessagePredictably() {
    val okShizuku = ShizukuActionResult(
      ok = true,
      message = "Wi-Fi enabled",
      usedShizuku = true,
    )
    assertEquals("[SHIZUKU // OK] Wi-Fi enabled", okShizuku.formatStatusMessage())

    val errShizuku = ShizukuActionResult(
      ok = false,
      message = "Failed to set Wi-Fi enabled",
      usedShizuku = true,
    )
    assertEquals("[SHIZUKU // ERR] Failed to set Wi-Fi enabled", errShizuku.formatStatusMessage())

    val errLocal = ShizukuActionResult(
      ok = false,
      message = "Shizuku service unavailable",
      usedShizuku = false,
    )
    assertEquals("[LOCAL // ERR] Shizuku service unavailable", errLocal.formatStatusMessage())
  }
}
