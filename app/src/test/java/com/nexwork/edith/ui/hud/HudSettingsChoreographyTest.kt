package com.nexwork.edith.ui.hud

import com.nexwork.edith.model.SettingsStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudSettingsChoreographyTest {

  @Test
  fun rootBranchSpecs_launcherBuilt_shizukuStandby() {
    assertEquals(2, ROOT_BRANCH_SPECS.size)

    val left = ROOT_BRANCH_SPECS[0]
    val right = ROOT_BRANCH_SPECS[1]

    assertEquals(SettingsStage.LAUNCHER_TREE, left.stage)
    assertTrue(left.isBuilt)

    assertEquals(SettingsStage.SHIZUKU_PLACEHOLDER, right.stage)
    assertFalse("Standby SHIZUKU branch must remain dim", right.isBuilt)
  }

  @Test
  fun backGate_blocksRootCardsRisingAboveGlobeUntilOverlayLinesRetractToZero() {
    // User pressed Back from Stage 3/2 -> ViewModel stage is already ROOT_BRANCHES,
    // while overlay detail & branch lasers are still retracing downward.
    assertFalse(
      HudSettingsGateChecker.shouldPlaceRootAboveGlobe(
        targetStage = SettingsStage.ROOT_BRANCHES,
        retainedOverlayStage = SettingsStage.LAUNCHER_TREE,
        treeProgress = 1.0f,
        detailProgress = 0.5f,
      ),
    )
    assertFalse(
      HudSettingsGateChecker.shouldPlaceRootAboveGlobe(
        targetStage = SettingsStage.ROOT_BRANCHES,
        retainedOverlayStage = SettingsStage.LAUNCHER_TREE,
        treeProgress = 0.25f,
        detailProgress = 0.0f,
      ),
    )
    assertFalse(
      HudSettingsGateChecker.shouldPlaceRootAboveGlobe(
        targetStage = SettingsStage.ROOT_BRANCHES,
        retainedOverlayStage = SettingsStage.LAUNCHER_TREE,
        treeProgress = 0.01f,
        detailProgress = 0.0f,
      ),
    )

    // Only once BOTH branchTreeAnim and detailCardAnim reach <= ZERO_EPSILON can root lift.
    assertTrue(
      HudSettingsGateChecker.shouldPlaceRootAboveGlobe(
        targetStage = SettingsStage.ROOT_BRANCHES,
        retainedOverlayStage = SettingsStage.ROOT_BRANCHES,
        treeProgress = 0.0f,
        detailProgress = 0.0f,
      ),
    )

    // Closing straight from Stage 2 keeps sunken root behind the globe while overlay unwinds.
    assertFalse(
      HudSettingsGateChecker.shouldPlaceRootAboveGlobe(
        targetStage = SettingsStage.CLOSED,
        retainedOverlayStage = SettingsStage.LAUNCHER_TREE,
        treeProgress = 0.4f,
        detailProgress = 0.0f,
      ),
    )
    assertFalse(
      HudSettingsGateChecker.shouldPlaceRootAboveGlobe(
        targetStage = SettingsStage.CLOSED,
        retainedOverlayStage = SettingsStage.LAUNCHER_TREE,
        treeProgress = 0.0f,
        detailProgress = 0.0f,
      ),
    )

    // Closing from Stage 1 keeps root above globe while arcs reverse-erase cleanly.
    assertTrue(
      HudSettingsGateChecker.shouldPlaceRootAboveGlobe(
        targetStage = SettingsStage.CLOSED,
        retainedOverlayStage = SettingsStage.ROOT_BRANCHES,
        treeProgress = 0.0f,
        detailProgress = 0.0f,
      ),
    )
  }

  @Test
  fun forwardGates_requireRootCollapsedBeforeOverlayAndBranchHalfBeforeDetail() {
    assertFalse(HudSettingsGateChecker.canLaunchOverlayTree(0.0f))
    assertFalse(HudSettingsGateChecker.canLaunchOverlayTree(0.95f))
    assertTrue(HudSettingsGateChecker.canLaunchOverlayTree(1.0f))

    assertFalse(HudSettingsGateChecker.canLaunchDetailCard(0.20f))
    assertTrue(HudSettingsGateChecker.canLaunchDetailCard(0.50f))
    assertTrue(HudSettingsGateChecker.canLaunchDetailCard(1.0f))
  }

  @Test
  fun rootBranchPacing_centerVerticalBranchArrivesAtCardSimultaneouslyWithOuterBranches() {
    val sideHorizPx = 115f
    val verticalPx = 32f
    val cornerFraction = sideHorizPx / (sideHorizPx + verticalPx)

    // While outer L-branches travel horizontally toward left/right cards, center stays at split junction.
    assertEquals(
      0.0f,
      HudSettingsGateChecker.computeRootBranchProgress(
        splitP = cornerFraction * 0.5f,
        branchHorizontalPx = 0f,
        sideHorizontalPx = sideHorizPx,
        verticalPx = verticalPx,
      ),
      0.001f,
    )

    // Halfway through the final vertical ascent, outer arms and center match vertically.
    val midVerticalSplit = cornerFraction + (1f - cornerFraction) * 0.5f
    val centerAtMidVertical = HudSettingsGateChecker.computeRootBranchProgress(
      splitP = midVerticalSplit,
      branchHorizontalPx = 0f,
      sideHorizontalPx = sideHorizPx,
      verticalPx = verticalPx,
    )
    assertEquals(0.5f, centerAtMidVertical, 0.005f)

    // At splitP = 1f, center and outer branches both reach 1f together.
    assertEquals(
      1.0f,
      HudSettingsGateChecker.computeRootBranchProgress(
        splitP = 1.0f,
        branchHorizontalPx = 0f,
        sideHorizontalPx = sideHorizPx,
        verticalPx = verticalPx,
      ),
      0.001f,
    )
    assertEquals(
      1.0f,
      HudSettingsGateChecker.computeRootBranchProgress(
        splitP = 1.0f,
        branchHorizontalPx = sideHorizPx,
        sideHorizontalPx = sideHorizPx,
        verticalPx = verticalPx,
      ),
      0.001f,
    )
  }
}
