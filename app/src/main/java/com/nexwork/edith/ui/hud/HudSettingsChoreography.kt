package com.nexwork.edith.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nexwork.edith.model.JarvisSettingsSection
import com.nexwork.edith.model.LauncherSettingsSection
import com.nexwork.edith.model.SettingsStage
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal const val ROOT_COLLAPSED_ALPHA = 0.46f
internal const val ROOT_COLLAPSED_SCALE = 0.94f

internal const val ROOT_ENTER_DURATION_MS = 960
internal const val ROOT_REVERSE_DURATION_MS = 560
internal const val ROOT_COLLAPSE_DURATION_MS = 320
internal const val ROOT_RETURN_DURATION_MS = 380
internal const val BRANCH_ENTER_DURATION_MS = 760
internal const val BRANCH_EXIT_DURATION_MS = 460
internal const val DETAIL_ENTER_DURATION_MS = 640
internal const val DETAIL_EXIT_DURATION_MS = 360

/**
 * Single source of truth for root settings cards and their corresponding trunk laser lines,
 * preventing card focus styling and laser rail color from diverging.
 */
data class RootBranchSpec(
  val index: Int,
  val eyebrow: String,
  val title: String,
  val stage: SettingsStage,
  val isBuilt: Boolean,
)

val ROOT_BRANCH_SPECS: List<RootBranchSpec> = listOf(
  RootBranchSpec(
    index = 0,
    eyebrow = "01 // HUD & UI",
    title = "LAUNCHER",
    stage = SettingsStage.LAUNCHER_TREE,
    isBuilt = true,
  ),
  RootBranchSpec(
    index = 1,
    eyebrow = "02 // AI AGENT",
    title = "JARVIS",
    stage = SettingsStage.AGENT_PLACEHOLDER,
    isBuilt = true,
  ),
  RootBranchSpec(
    index = 2,
    eyebrow = "03 // STANDBY",
    title = "SHIZUKU",
    stage = SettingsStage.SHIZUKU_PLACEHOLDER,
    isBuilt = false,
  ),
)

/**
 * Pure threshold & phase-gate rules verified by unit tests so root cards never rise above the
 * 3D globe before overlay lasers retrace down, and overlay lasers never fire before root sinks.
 */
object HudSettingsGateChecker {
  const val ZERO_EPSILON = 0.005f
  const val ONE_THRESHOLD = 0.995f
  const val DETAIL_ENTRY_THRESHOLD = 0.50f

  fun isOverlayFullyRetracted(treeProgress: Float, detailProgress: Float): Boolean =
    treeProgress <= ZERO_EPSILON && detailProgress <= ZERO_EPSILON

  fun isRootFullyCollapsed(collapseProgress: Float): Boolean =
    collapseProgress >= ONE_THRESHOLD

  fun canLaunchOverlayTree(collapseProgress: Float): Boolean =
    isRootFullyCollapsed(collapseProgress)

  fun canLaunchDetailCard(treeProgress: Float): Boolean =
    treeProgress >= DETAIL_ENTRY_THRESHOLD

  /**
   * Determines whether the Stage 1 root cards sit in front of the globe (`zIndex = 2f`).
   * While any overlay tree or detail line remains visible (`> ZERO_EPSILON`), root stays locked
   * behind the globe (`false`) so Back unwinds retracing lines completely before lifting cards.
   */
  fun shouldPlaceRootAboveGlobe(
    targetStage: SettingsStage,
    retainedOverlayStage: SettingsStage,
    treeProgress: Float,
    detailProgress: Float,
  ): Boolean {
    if (!isOverlayFullyRetracted(treeProgress, detailProgress)) return false
    return when {
      targetStage == SettingsStage.ROOT_BRANCHES -> true
      targetStage == SettingsStage.CLOSED && retainedOverlayStage == SettingsStage.ROOT_BRANCHES -> true
      else -> false
    }
  }

  /**
   * Computes branch trace progress (`0..1`) normalized so shorter center branches arrive at the
   * bottom of their card on the same frame the outer L-branches finish turning vertical.
   */
  fun computeRootBranchProgress(
    splitP: Float,
    branchHorizontalPx: Float,
    sideHorizontalPx: Float,
    verticalPx: Float,
  ): Float {
    val p = splitP.coerceIn(0f, 1f)
    if (branchHorizontalPx > 1f || sideHorizontalPx <= 1f || verticalPx <= 0f) {
      return p
    }
    val sideTotal = (sideHorizontalPx + verticalPx).coerceAtLeast(1f)
    val cornerFraction = (sideHorizontalPx / sideTotal).coerceIn(0f, 0.90f)
    if (p <= cornerFraction) return 0f
    return ((p - cornerFraction) / (1f - cornerFraction).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
  }
}

/**
 * Shared Compose choreography coordinator driving Stage 1 (Root), Stage 2 (Branch Rail),
 * and Stage 3 (Detail HUD) sequentially without racing `LaunchedEffect` timers.
 */
@Stable
class HudSettingsChoreographyController(initialStage: SettingsStage) {
  val rootAnim = Animatable(0f)
  val collapseAnim = Animatable(0f)
  val branchTreeAnim = Animatable(0f)
  val detailCardAnim = Animatable(0f)

  var targetStage by mutableStateOf(initialStage)
    internal set

  var mounted by mutableStateOf(initialStage != SettingsStage.CLOSED)
    internal set

  var retainedOverlayStage by mutableStateOf(initialStage)
    internal set

  var retainedLauncherSection by mutableStateOf<LauncherSettingsSection?>(null)
    internal set

  var retainedJarvisSection by mutableStateOf<JarvisSettingsSection?>(null)
    internal set

  val isRootAboveGlobe: Boolean
    get() = HudSettingsGateChecker.shouldPlaceRootAboveGlobe(
      targetStage = targetStage,
      retainedOverlayStage = retainedOverlayStage,
      treeProgress = branchTreeAnim.value,
      detailProgress = detailCardAnim.value,
    )

  val isOverlayRetracted: Boolean
    get() = HudSettingsGateChecker.isOverlayFullyRetracted(
      treeProgress = branchTreeAnim.value,
      detailProgress = detailCardAnim.value,
    )

  suspend fun driveTo(
    stage: SettingsStage,
    selectedLauncherSection: LauncherSettingsSection?,
    selectedJarvisSection: JarvisSettingsSection?,
    onPrepareAgentStage: () -> Unit,
  ) {
    targetStage = stage
    if (stage == SettingsStage.AGENT_PLACEHOLDER) {
      onPrepareAgentStage()
    }

    if (stage == SettingsStage.CLOSED) {
      if (!mounted) {
        snapResetAll()
        return
      }
      // Step 1: Always unwind detail card -> branch tree lines back down into sunken card first.
      retractOverlaySequentially()
      // Step 2: Reverse-erase root arcs/stem/cards without popping sunken cards back above globe.
      if (rootAnim.value > HudSettingsGateChecker.ZERO_EPSILON) {
        animateDown(rootAnim, ROOT_REVERSE_DURATION_MS)
      }
      snapResetAll()
      mounted = false
      return
    }

    val wasUnmounted = !mounted
    mounted = true

    if (stage == SettingsStage.ROOT_BRANCHES) {
      if (wasUnmounted) {
        branchTreeAnim.snapTo(0f)
        detailCardAnim.snapTo(0f)
        collapseAnim.snapTo(0f)
        retainedLauncherSection = null
        retainedJarvisSection = null
        retainedOverlayStage = SettingsStage.ROOT_BRANCHES
        delay(220L)
        animateUp(rootAnim, ROOT_ENTER_DURATION_MS)
      } else {
        // Explicit reverse gate: root cards stay sunken behind the globe until EVERY detail card
        // and overlay branch laser finishes retracing back down to 0f.
        retractOverlaySequentially()
        if (HudSettingsGateChecker.isOverlayFullyRetracted(branchTreeAnim.value, detailCardAnim.value)) {
          retainedOverlayStage = SettingsStage.ROOT_BRANCHES
          if (rootAnim.value < 0.999f) {
            animateUp(rootAnim, ROOT_ENTER_DURATION_MS / 2)
          }
          if (collapseAnim.value > HudSettingsGateChecker.ZERO_EPSILON) {
            animateDown(collapseAnim, ROOT_RETURN_DURATION_MS)
          }
        }
      }
      return
    }

    // Deep branch stages: LAUNCHER_TREE, AGENT_PLACEHOLDER, SHIZUKU_PLACEHOLDER
    if (rootAnim.value < 0.999f) {
      animateUp(rootAnim, ROOT_ENTER_DURATION_MS / 2)
    }

    val switchingBranch = retainedOverlayStage != SettingsStage.ROOT_BRANCHES &&
      retainedOverlayStage != stage &&
      (branchTreeAnim.value > HudSettingsGateChecker.ZERO_EPSILON || detailCardAnim.value > HudSettingsGateChecker.ZERO_EPSILON)
    if (switchingBranch) {
      retractOverlaySequentially()
    }

    if (!HudSettingsGateChecker.isRootFullyCollapsed(collapseAnim.value)) {
      animateUp(collapseAnim, ROOT_COLLAPSE_DURATION_MS)
    }
    if (!HudSettingsGateChecker.canLaunchOverlayTree(collapseAnim.value)) return

    retainedOverlayStage = stage
    if (stage != SettingsStage.LAUNCHER_TREE) retainedLauncherSection = null
    if (stage != SettingsStage.AGENT_PLACEHOLDER) retainedJarvisSection = null

    val targetLauncher = if (stage == SettingsStage.LAUNCHER_TREE) selectedLauncherSection else null
    val targetJarvis = if (stage == SettingsStage.AGENT_PLACEHOLDER) selectedJarvisSection else null
    val changingDetail = (targetLauncher != retainedLauncherSection) || (targetJarvis != retainedJarvisSection)

    // If user backed out of Stage 3 -> Stage 2 or switched cards, unwind previous detail first.
    if (changingDetail && detailCardAnim.value > HudSettingsGateChecker.ZERO_EPSILON) {
      animateDown(detailCardAnim, DETAIL_EXIT_DURATION_MS)
    }
    if (detailCardAnim.value <= HudSettingsGateChecker.ZERO_EPSILON) {
      if (targetLauncher == null && targetJarvis == null) {
        retainedLauncherSection = null
        retainedJarvisSection = null
      }
    }

    if (branchTreeAnim.value < 0.999f) {
      animateUp(branchTreeAnim, BRANCH_ENTER_DURATION_MS)
    }

    if ((targetLauncher != null || targetJarvis != null) &&
      HudSettingsGateChecker.canLaunchDetailCard(branchTreeAnim.value)
    ) {
      retainedLauncherSection = targetLauncher
      retainedJarvisSection = targetJarvis
      if (detailCardAnim.value < 0.999f) {
        animateUp(detailCardAnim, DETAIL_ENTER_DURATION_MS)
      }
    }
  }

  private suspend fun retractOverlaySequentially() {
    if (detailCardAnim.value > HudSettingsGateChecker.ZERO_EPSILON) {
      animateDown(detailCardAnim, DETAIL_EXIT_DURATION_MS)
    }
    detailCardAnim.snapTo(0f)
    retainedLauncherSection = null
    retainedJarvisSection = null

    if (branchTreeAnim.value > HudSettingsGateChecker.ZERO_EPSILON) {
      animateDown(branchTreeAnim, BRANCH_EXIT_DURATION_MS)
    }
    branchTreeAnim.snapTo(0f)
  }

  private suspend fun snapResetAll() {
    detailCardAnim.snapTo(0f)
    branchTreeAnim.snapTo(0f)
    collapseAnim.snapTo(0f)
    rootAnim.snapTo(0f)
    retainedLauncherSection = null
    retainedJarvisSection = null
    retainedOverlayStage = SettingsStage.CLOSED
  }

  private suspend fun animateUp(anim: Animatable<Float, *>, fullDurationMs: Int) {
    val rem = ((1f - anim.value).coerceIn(0f, 1f) * fullDurationMs).roundToInt().coerceAtLeast(1)
    anim.animateTo(1f, tween(rem, easing = LinearEasing))
  }

  private suspend fun animateDown(anim: Animatable<Float, *>, fullDurationMs: Int) {
    val rem = (anim.value.coerceIn(0f, 1f) * fullDurationMs).roundToInt().coerceAtLeast(1)
    anim.animateTo(0f, tween(rem, easing = LinearEasing))
  }
}

@Composable
fun rememberHudSettingsChoreographer(
  initialStage: SettingsStage = SettingsStage.CLOSED,
): HudSettingsChoreographyController = remember { HudSettingsChoreographyController(initialStage) }

@Composable
fun BindHudSettingsChoreography(
  choreographer: HudSettingsChoreographyController,
  stage: SettingsStage,
  selectedSection: LauncherSettingsSection?,
  selectedJarvisSection: JarvisSettingsSection?,
  onPrepareAgentStage: () -> Unit,
  onMountedChange: (Boolean) -> Unit,
) {
  LaunchedEffect(choreographer.mounted) {
    onMountedChange(choreographer.mounted)
  }
  LaunchedEffect(stage, selectedSection, selectedJarvisSection) {
    choreographer.driveTo(
      stage = stage,
      selectedLauncherSection = selectedSection,
      selectedJarvisSection = selectedJarvisSection,
      onPrepareAgentStage = onPrepareAgentStage,
    )
  }
}
