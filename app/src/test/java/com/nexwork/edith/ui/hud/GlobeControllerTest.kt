package com.nexwork.edith.ui.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeControllerTest {

  @Test
  fun startupGateHoldsHudUntilGlobeFinishesAndResetsOnWake() {
    val controller = GlobeController()
    assertFalse(controller.isStartupComplete.value)

    controller.markStartupComplete()
    assertTrue(controller.isStartupComplete.value)

    controller.playStartupAnimation()
    assertFalse(controller.isStartupComplete.value)

    controller.finishStartupAnimation()
    assertTrue(controller.isStartupComplete.value)
  }

  @Test
  fun horizonSettleGateBlocksDuringSearchAndUnlocksOnSettle() {
    val controller = GlobeController()
    assertTrue(controller.isGlobeAtHorizon.value)

    controller.setSearchActive(true)
    assertFalse(controller.isGlobeAtHorizon.value)

    controller.setSearchActive(false)
    assertFalse(controller.isGlobeAtHorizon.value)

    controller.markHorizonSettled()
    assertTrue(controller.isGlobeAtHorizon.value)
  }
}
