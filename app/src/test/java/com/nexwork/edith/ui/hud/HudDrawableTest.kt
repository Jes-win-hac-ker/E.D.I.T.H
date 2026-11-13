package com.nexwork.edith.ui.hud

import com.nexwork.edith.model.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudDrawableTest {

  @Test
  fun computeScaledDimensions_preservesSmallAndClampsLarge() {
    assertEquals(48 to 48, computeScaledDimensions(48, 48, 96))
    assertEquals(96 to 48, computeScaledDimensions(192, 96, 96))
    assertEquals(48 to 96, computeScaledDimensions(100, 200, 96))
    assertEquals(1 to 1, computeScaledDimensions(0, -5, 96))
  }

  @Test
  fun cachedHudGridBitmap_rejectsInvalidDimensions() {
    assertNull(cachedHudGridBitmap(0, 100, 64f, 1f))
    assertNull(cachedHudGridBitmap(100, 0, 64f, 1f))
    assertNull(cachedHudGridBitmap(100, 100, 4f, 1f))
    assertNull(cachedHudGridBitmap(100, 100, 64f, 0f))
  }

  @Test
  fun warmAppIcons_skipsEntriesWithoutDrawables() {
    warmAppIcons(
      listOf(
        AppEntry("Test", "com.example.test", "com.example.test.Main"),
      ),
    )
  }
}
