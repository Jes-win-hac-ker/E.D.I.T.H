package com.nexwork.edith.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetThemeManagerTest {

  private val expectedStops = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

  @Test
  fun neutralPalettes_arePureZeroChromaGrayscale() {
    val map = MonetThemeManager.buildFabricatedResourceMap()
    for (stop in expectedStops) {
      val n1 = map["android:color/system_neutral1_$stop"] ?: error("Missing neutral1_$stop")
      val n2 = map["android:color/system_neutral2_$stop"] ?: error("Missing neutral2_$stop")
      assertEquals(n1, n2)
      val r = (n1 ushr 16) and 0xFF
      val g = (n1 ushr 8) and 0xFF
      val b = n1 and 0xFF
      assertEquals("Stop $stop must have R == G", r, g)
      assertEquals("Stop $stop must have G == B", g, b)
    }
    assertEquals(0xFFFFFFFF.toInt(), map["android:color/system_neutral1_0"])
    assertEquals(0xFF000000.toInt(), map["android:color/system_neutral1_1000"])
  }

  @Test
  fun accentPalettes_containCrimsonRampWithDc143cCore() {
    val map = MonetThemeManager.buildFabricatedResourceMap()
    for (stop in expectedStops) {
      assertTrue(map.containsKey("android:color/system_accent1_$stop"))
      assertTrue(map.containsKey("android:color/system_accent2_$stop"))
      assertTrue(map.containsKey("android:color/system_accent3_$stop"))
    }
    assertEquals(0xFFDC143C.toInt(), map["android:color/system_accent1_500"])
    assertEquals(0xFFDC143C.toInt(), map["android:color/system_accent2_500"])
    assertEquals(0xFFDC143C.toInt(), map["android:color/system_accent3_500"])
    assertEquals(0xFFDC143C.toInt(), map["android:color/system_shade_active_dark"])
    assertEquals(0xFFDC143C.toInt(), map["android:color/system_primary_container_dark"])
    assertEquals(0xFFDC143C.toInt(), map["android:color/system_control_activated_dark"])
  }

  @Test
  fun buildPreservedMonetSettingsJson_preservesFontAndIconShapeKeys() {
    val existing =
      """{"android.theme.customization.font":"com.android.theme.font.Exo2Regular","android.theme.customization.adaptive_icon_shape":"com.android.theme.icon.round","_applied_timestamp":1794587933913,"android.theme.customization.theme_style":"TONAL_SPOT"}"""
    val result = MonetThemeManager.buildPreservedMonetSettingsJson(existing, 1800000000000L)
    assertTrue(result.contains(""""android.theme.customization.font":"com.android.theme.font.Exo2Regular""""))
    assertTrue(result.contains(""""android.theme.customization.adaptive_icon_shape":"com.android.theme.icon.round""""))
    assertTrue(result.contains(""""android.theme.customization.theme_style":"RAINBOW""""))
    assertTrue(result.contains(""""android.theme.customization.system_palette":"DC143C""""))
    assertTrue(result.contains(""""_applied_timestamp":1800000000000"""))
  }
}
