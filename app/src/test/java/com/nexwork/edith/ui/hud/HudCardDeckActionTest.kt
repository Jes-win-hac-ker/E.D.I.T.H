package com.nexwork.edith.ui.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudCardDeckActionTest {

  @Test
  fun cardTrayHitAction_selectsInfoUninstallAndDismissCleanly() {
    val width = 900f
    val height = 300f
    val trayWidth = 300f

    // Tapping shifted foreground card area (left of tray) dismisses tray instead of launching app
    assertEquals(
      CardTrayAction.DISMISS,
      cardTrayHitAction(x = 599f, width = width, localY = 200f, cardHeight = height, trayWidth = trayWidth, isSystemApp = false),
    )

    // Top half of tray opens App Info; bottom half triggers Uninstall for user apps
    assertEquals(
      CardTrayAction.APP_INFO,
      cardTrayHitAction(x = 650f, width = width, localY = 120f, cardHeight = height, trayWidth = trayWidth, isSystemApp = false),
    )
    assertEquals(
      CardTrayAction.UNINSTALL,
      cardTrayHitAction(x = 650f, width = width, localY = 180f, cardHeight = height, trayWidth = trayWidth, isSystemApp = false),
    )

    // Preinstalled system packages route the entire tray safely to App Info
    assertEquals(
      CardTrayAction.APP_INFO,
      cardTrayHitAction(x = 650f, width = width, localY = 220f, cardHeight = height, trayWidth = trayWidth, isSystemApp = true),
    )

    // Out-of-bounds vertical taps safely dismiss
    assertEquals(
      CardTrayAction.DISMISS,
      cardTrayHitAction(x = 650f, width = width, localY = -10f, cardHeight = height, trayWidth = trayWidth, isSystemApp = false),
    )
  }

  @Test
  fun shouldArmCardSwipe_honorsThreshold() {
    assertFalse(shouldArmCardSwipe(0.25f))
    assertFalse(shouldArmCardSwipe(0.479f))
    assertTrue(shouldArmCardSwipe(0.48f))
    assertTrue(shouldArmCardSwipe(1.0f))
  }
}
