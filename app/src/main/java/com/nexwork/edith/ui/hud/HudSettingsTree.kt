package com.nexwork.edith.ui.hud

import android.Manifest
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexwork.edith.data.OtaState
import com.nexwork.edith.jarvis.core.JarvisEventRecord
import com.nexwork.edith.jarvis.core.JarvisRuntime
import com.nexwork.edith.jarvis.core.PlaceTag
import com.nexwork.edith.jarvis.core.TriageStatus
import com.nexwork.edith.jarvis.security.BiometricPromptHelper
import com.nexwork.edith.model.DeviceTelemetry
import com.nexwork.edith.model.JarvisSettingsSection
import com.nexwork.edith.model.LauncherAccentPreset
import com.nexwork.edith.model.LauncherFontStyle
import com.nexwork.edith.model.LauncherSettingsConfig
import com.nexwork.edith.model.LauncherSettingsSection
import com.nexwork.edith.model.LiveWeather
import com.nexwork.edith.model.SettingsStage
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.ObsidianBgTop
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import com.nexwork.edith.theme.WarmSunAccent
import com.nexwork.edith.theme.toColor
import java.util.Date
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

private val TreeRailColor = Color(0xFF64748B)
private val FocusedPlateBrush = Brush.verticalGradient(listOf(Color(0xD9050505), Color(0xD9111111)))
private val UnfocusedPlateBrush = Brush.verticalGradient(listOf(Color(0xB8050505), Color(0xB8111111)))
private val ContainerPlateBrush = Brush.verticalGradient(listOf(Color(0xE0050505), Color(0xE0111111)))

/**
 * Shared pixel-exact geometry derived from screen dimensions and the orbital globe horizon
 * projection in `HudClock.kt`, ensuring Stage 1 sunken root cards and Stage 2/3 laser origins
 * align to the pixel across both layers.
 */
private class SettingsTreeGeometry(
  screenWidth: Dp,
  screenHeight: Dp,
  density: Density,
) {
  val widthDp: Dp = screenWidth
  val fullW: Float
  val fullH: Float
  val cx: Float

  val arcZenithY: Float
  val arcRadius: Float
  val arcCenterY: Float
  val maxThetaRad: Float
  val maxThetaDeg: Float

  val sideMarginDp: Dp = 14.dp
  val boxGapDp: Dp = 10.dp
  val rootBoxWDp: Dp = ((screenWidth - sideMarginDp * 2 - boxGapDp * 2) / 3f).coerceAtLeast(88.dp)
  val rootBoxHDp: Dp = 78.dp
  val rootRowTopDp: Dp

  val rootRowTopY: Float
  val rootRowBottomY: Float
  val rootRowCenterY: Float
  val rootBoxW: Float
  val rootBoxH: Float
  val sideMarginPx: Float
  val boxGapPx: Float
  val rootStemSplitY: Float
  val rootDropPx: Float

  init {
    with(density) {
      fullW = screenWidth.toPx().coerceAtLeast(1f)
      fullH = screenHeight.toPx().coerceAtLeast(1f)
      cx = fullW * 0.5f

      val globeApexDp = screenHeight * 0.64f
      val clockTopDp = (globeApexDp - 82.dp).coerceAtLeast(66.dp)
      val arcZenithDp = clockTopDp + 72.dp
      arcZenithY = arcZenithDp.toPx()

      val aspect = fullW / fullH
      val tanHalfFov = tan(Math.toRadians(22.5)).toFloat()
      val halfH = 280f * tanHalfFov
      val apexWorldY = -10f - 0.30f * halfH
      val horizonScale = (aspect * 2.18f).coerceIn(0.86f, 1.22f)
      val globeWorldRadius = 75f * horizonScale
      val globeWorldY = apexWorldY - globeWorldRadius
      val dyCam = globeWorldY - 15f
      val zCam = -dyCam * 0.08893f + 280f * 0.99604f
      val globeScreenRadius = (globeWorldRadius / zCam) * (fullH * 0.5f / tanHalfFov)

      arcRadius = globeScreenRadius + 10.dp.toPx()
      val arcHalfSpan = cx.coerceAtMost(arcRadius * 0.985f)
      arcCenterY = arcZenithY + arcRadius
      maxThetaRad = asin((arcHalfSpan / arcRadius).coerceIn(-1f, 1f))
      maxThetaDeg = Math.toDegrees(maxThetaRad.toDouble()).toFloat()

      rootRowTopDp = (arcZenithDp - 196.dp).coerceAtLeast(88.dp)
      rootRowTopY = rootRowTopDp.toPx()
      rootBoxW = rootBoxWDp.toPx()
      rootBoxH = rootBoxHDp.toPx()
      sideMarginPx = sideMarginDp.toPx()
      boxGapPx = boxGapDp.toPx()
      rootRowBottomY = rootRowTopY + rootBoxH
      rootRowCenterY = rootRowTopY + rootBoxH * 0.5f
      rootStemSplitY = rootRowBottomY + 32.dp.toPx()
      rootDropPx = 135.dp.toPx()
    }
  }

  fun rootCardCenterX(index: Int): Float =
    sideMarginPx + index * (rootBoxW + boxGapPx) + rootBoxW * 0.5f

  fun sunkenCardTopCenter(index: Int): Offset {
    val rawCx = rootCardCenterX(index)
    val mappedX = cx + (rawCx - cx) * ROOT_COLLAPSED_SCALE
    val mappedY = rootRowCenterY + rootDropPx + (rootRowTopY - rootRowCenterY) * ROOT_COLLAPSED_SCALE
    return Offset(mappedX, mappedY)
  }
}

@Composable
private fun rememberSettingsGeometry(
  screenWidth: Dp,
  screenHeight: Dp,
): SettingsTreeGeometry {
  val density = LocalDensity.current
  return remember(screenWidth, screenHeight, density) {
    SettingsTreeGeometry(screenWidth, screenHeight, density)
  }
}

/**
 * Backward-compatible composite wrapper delegating to [HudSettingsRootLayer] and
 * [HudSettingsOverlayLayer]. Prefer layering those two composables directly around the 3D globe
 * in `MainScreen.kt` so Stage 1 sinks behind the globe sphere.
 */
@Composable
fun HudSettingsTree(
  stage: SettingsStage,
  selectedSection: LauncherSettingsSection?,
  settings: LauncherSettingsConfig,
  telemetry: DeviceTelemetry,
  liveWeather: LiveWeather?,
  isDefaultLauncher: Boolean,
  hasNotificationAccess: Boolean,
  otaState: OtaState,
  onSelectBranch: (SettingsStage) -> Unit,
  onSelectSection: (LauncherSettingsSection?) -> Unit,
  onBackStep: () -> Unit,
  onAccentChange: (LauncherAccentPreset) -> Unit,
  onFontChange: (LauncherFontStyle) -> Unit,
  onFontScaleChange: (Float) -> Unit,
  onUse24HourChange: (Boolean) -> Unit,
  onRefreshRateSelect: (Int?) -> Unit,
  onRefreshWeather: () -> Unit,
  onOpenAlarms: () -> Unit,
  onOpenHomeSettings: () -> Unit,
  onOpenUsageSettings: () -> Unit,
  onOpenNotificationSettings: () -> Unit,
  onCheckOta: () -> Unit,
  modifier: Modifier = Modifier,
  selectedJarvisSection: JarvisSettingsSection? = null,
  onSelectJarvisSection: (JarvisSettingsSection?) -> Unit = {},
  onOpenJarvisDrawer: () -> Unit = {},
  onMountedChange: (Boolean) -> Unit = {},
) {
  val context = LocalContext.current
  val choreographer = rememberHudSettingsChoreographer(stage)
  BindHudSettingsChoreography(
    choreographer = choreographer,
    stage = stage,
    selectedSection = selectedSection,
    selectedJarvisSection = selectedJarvisSection,
    onPrepareAgentStage = {
      JarvisRuntime.ensureInitialized(context)
      JarvisRuntime.refreshEnvironmentStatus(context)
    },
    onMountedChange = onMountedChange,
  )

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    HudSettingsRootLayer(
      stage = stage,
      screenWidth = maxWidth,
      screenHeight = maxHeight,
      onSelectBranch = onSelectBranch,
      choreographer = choreographer,
      modifier = Modifier.fillMaxSize(),
    )
    HudSettingsOverlayLayer(
      stage = stage,
      selectedSection = selectedSection,
      selectedJarvisSection = selectedJarvisSection,
      settings = settings,
      telemetry = telemetry,
      liveWeather = liveWeather,
      isDefaultLauncher = isDefaultLauncher,
      hasNotificationAccess = hasNotificationAccess,
      otaState = otaState,
      screenWidth = maxWidth,
      screenHeight = maxHeight,
      onSelectSection = onSelectSection,
      onSelectJarvisSection = onSelectJarvisSection,
      onOpenJarvisDrawer = onOpenJarvisDrawer,
      onBackStep = onBackStep,
      onAccentChange = onAccentChange,
      onFontChange = onFontChange,
      onFontScaleChange = onFontScaleChange,
      onUse24HourChange = onUse24HourChange,
      onRefreshRateSelect = onRefreshRateSelect,
      onRefreshWeather = onRefreshWeather,
      onOpenAlarms = onOpenAlarms,
      onOpenHomeSettings = onOpenHomeSettings,
      onOpenUsageSettings = onOpenUsageSettings,
      onOpenNotificationSettings = onOpenNotificationSettings,
      onCheckOta = onCheckOta,
      choreographer = choreographer,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

/**
 * Stage 1 settings root layer sitting below the transparent 3D WebGL globe (`zIndex = 0f`) while
 * sunken or while overlay lasers retract, and above the globe (`zIndex = 2f`) only once
 * [HudSettingsGateChecker.shouldPlaceRootAboveGlobe] confirms all overlay lines finished erasing.
 */
@Composable
fun HudSettingsRootLayer(
  stage: SettingsStage,
  screenWidth: Dp,
  screenHeight: Dp,
  onSelectBranch: (SettingsStage) -> Unit,
  choreographer: HudSettingsChoreographyController = rememberHudSettingsChoreographer(stage),
  modifier: Modifier = Modifier,
) {
  val accent = LocalLauncherStyle.current.accent
  val haptic = LocalHapticFeedback.current
  val geo = rememberSettingsGeometry(screenWidth, screenHeight)

  if (!choreographer.mounted) return

  val collapseP = hudEaseOutCubic(choreographer.collapseAnim.value.coerceIn(0f, 1f))
  val rootScale = 1f - (1f - ROOT_COLLAPSED_SCALE) * collapseP
  val rootDrop = geo.rootDropPx * collapseP
  val layerAlpha = 1f - (1f - ROOT_COLLAPSED_ALPHA) * collapseP
  val effectiveP = choreographer.rootAnim.value.coerceIn(0f, 1f)
  val cardsMaster = hudEaseOutCubic(phase(effectiveP, 0.54f, 1.00f))
  val canClickRoot = stage == SettingsStage.ROOT_BRANCHES &&
    choreographer.isRootAboveGlobe &&
    effectiveP > 0.65f &&
    collapseP < 0.05f

  Box(
    modifier = modifier
      .fillMaxSize()
      .graphicsLayer {
        alpha = layerAlpha
        scaleX = rootScale
        scaleY = rootScale
        translationY = rootDrop
        transformOrigin = TransformOrigin(0.5f, (geo.rootRowCenterY / size.height.coerceAtLeast(1f)))
      },
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val p = effectiveP
      if (p <= 0.005f) return@Canvas

      // Phase 1 (0.00..0.32): bilateral orbital horizon arcs sweep inward from both screen edges
      val arcP = hudEaseOutCubic(phase(p, 0.00f, 0.32f))
      val arcAlpha = (1f - 0.70f * collapseP).coerceIn(0f, 1f)
      if (arcP > 0.005f && arcAlpha > 0.01f) {
        val arcTopLeft = Offset(geo.cx - geo.arcRadius, geo.arcCenterY - geo.arcRadius)
        val arcDiameter = Size(geo.arcRadius * 2f, geo.arcRadius * 2f)
        val arcStroke = Stroke(width = 1.15f.dp.toPx(), cap = StrokeCap.Round)
        val sweepDeg = geo.maxThetaDeg * arcP

        drawArc(
          color = accent.copy(alpha = 0.76f * arcAlpha),
          startAngle = -90f - geo.maxThetaDeg,
          sweepAngle = sweepDeg,
          useCenter = false,
          topLeft = arcTopLeft,
          size = arcDiameter,
          style = arcStroke,
        )
        drawArc(
          color = accent.copy(alpha = 0.76f * arcAlpha),
          startAngle = -90f + geo.maxThetaDeg,
          sweepAngle = -sweepDeg,
          useCenter = false,
          topLeft = arcTopLeft,
          size = arcDiameter,
          style = arcStroke,
        )

        if (arcP in 0.02f..0.97f) {
          val theta = geo.maxThetaRad * (1f - arcP)
          val sinT = sin(theta)
          val cosT = cos(theta)
          drawLaserTip(
            Offset(geo.cx - geo.arcRadius * sinT, geo.arcCenterY - geo.arcRadius * cosT),
            accent.copy(alpha = arcAlpha),
          )
          drawLaserTip(
            Offset(geo.cx + geo.arcRadius * sinT, geo.arcCenterY - geo.arcRadius * cosT),
            accent.copy(alpha = arcAlpha),
          )
        }
      }

      // Center join ignition spark at (cx, arcZenithY)
      if (p in 0.28f..0.44f && collapseP < 0.5f) {
        val sparkP = 1f - phase(p, 0.30f, 0.44f)
        val join = Offset(geo.cx, geo.arcZenithY)
        drawCircle(accent.copy(alpha = 0.44f * sparkP), 6.dp.toPx(), join)
        drawCircle(Color.White.copy(alpha = 0.95f * sparkP), 2.1f.dp.toPx(), join)
      }

      // Phase 2 (0.28..0.44): perpendicular laser stem rises straight up from arc zenith
      val stemP = hudEaseOutCubic(phase(p, 0.28f, 0.44f))
      if (stemP > 0.005f) {
        val curY = geo.arcZenithY + (geo.rootStemSplitY - geo.arcZenithY) * stemP
        drawLine(
          color = accent.copy(alpha = 0.85f),
          start = Offset(geo.cx, geo.arcZenithY),
          end = Offset(geo.cx, curY),
          strokeWidth = 1.2f.dp.toPx(),
          cap = StrokeCap.Round,
        )
        if (stemP < 0.97f) {
          drawLaserTip(Offset(geo.cx, curY), accent)
        }
      }

      // Phase 3 (0.40..0.60): split orthogonally into all 3 cards; draw built red lines on top
      val splitP = hudEaseOutCubic(phase(p, 0.40f, 0.60f))
      if (splitP > 0.005f) {
        val strokeW = 1.15f.dp.toPx()
        val sideHorizPx = abs(geo.rootCardCenterX(0) - geo.cx)
        val verticalPx = abs(geo.rootStemSplitY - geo.rootRowBottomY)
        for (spec in ROOT_BRANCH_SPECS.sortedBy { it.isBuilt }) {
          val tx = geo.rootCardCenterX(spec.index)
          val branchP = HudSettingsGateChecker.computeRootBranchProgress(
            splitP = splitP,
            branchHorizontalPx = abs(tx - geo.cx),
            sideHorizontalPx = sideHorizPx,
            verticalPx = verticalPx,
          )
          if (branchP > 0.005f) {
            val lineColor = if (spec.isBuilt) {
              accent.copy(alpha = 0.86f)
            } else {
              TreeRailColor.copy(alpha = 0.72f)
            }
            val tip = drawOrthogonalPolyline(
              points = listOf(
                Offset(geo.cx, geo.rootStemSplitY),
                Offset(tx, geo.rootStemSplitY),
                Offset(tx, geo.rootRowBottomY),
              ),
              progress = branchP,
              color = lineColor,
              strokeWidth = strokeW,
            )
            if (branchP < 0.97f && spec.isBuilt) {
              drawLaserTip(tip, accent)
            }
          }
        }
      }
    }

    if (cardsMaster > 0.005f) {
      Row(
        modifier = Modifier
          .padding(start = geo.sideMarginDp, end = geo.sideMarginDp, top = geo.rootRowTopDp)
          .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(geo.boxGapDp),
      ) {
        for (spec in ROOT_BRANCH_SPECS) {
          CompactHudCard(
            eyebrow = spec.eyebrow,
            title = spec.title,
            pipColor = if (spec.isBuilt) accent else TreeRailColor.copy(alpha = 0.65f),
            focused = spec.isBuilt,
            accent = accent,
            titleSizeSp = 11.5f,
            reveal = { staggeredReveal(cardsMaster, spec.index, ROOT_BRANCH_SPECS.size, stagger = 0.14f) },
            enabled = canClickRoot,
            modifier = Modifier.width(geo.rootBoxWDp).height(geo.rootBoxHDp),
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onSelectBranch(spec.stage)
            },
          )
        }
      }
    }
  }
}

/**
 * Stage 2 & Stage 3 settings overlay layer sitting above the 3D WebGL globe (`zIndex = 3f`).
 * Originates laser lines directly from the sunken Stage 1 card's top center once
 * [HudSettingsChoreographyController] confirms Stage 1 collapse completed, and unwinds Stage 3 ->
 * Stage 2 beams strictly before Stage 1 cards rise back above the globe.
 */
@Composable
fun HudSettingsOverlayLayer(
  stage: SettingsStage,
  selectedSection: LauncherSettingsSection?,
  settings: LauncherSettingsConfig,
  telemetry: DeviceTelemetry,
  liveWeather: LiveWeather?,
  isDefaultLauncher: Boolean,
  hasNotificationAccess: Boolean,
  otaState: OtaState,
  screenWidth: Dp,
  screenHeight: Dp,
  onSelectSection: (LauncherSettingsSection?) -> Unit,
  onBackStep: () -> Unit,
  onAccentChange: (LauncherAccentPreset) -> Unit,
  onFontChange: (LauncherFontStyle) -> Unit,
  onFontScaleChange: (Float) -> Unit,
  onUse24HourChange: (Boolean) -> Unit,
  onRefreshRateSelect: (Int?) -> Unit,
  onRefreshWeather: () -> Unit,
  onOpenAlarms: () -> Unit,
  onOpenHomeSettings: () -> Unit,
  onOpenUsageSettings: () -> Unit,
  onOpenNotificationSettings: () -> Unit,
  onCheckOta: () -> Unit,
  choreographer: HudSettingsChoreographyController = rememberHudSettingsChoreographer(stage),
  modifier: Modifier = Modifier,
  selectedJarvisSection: JarvisSettingsSection? = null,
  onSelectJarvisSection: (JarvisSettingsSection?) -> Unit = {},
  onOpenJarvisDrawer: () -> Unit = {},
) {
  val accent = LocalLauncherStyle.current.accent
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current
  val geo = rememberSettingsGeometry(screenWidth, screenHeight)

  val effectiveTreeP = choreographer.branchTreeAnim.value.coerceIn(0f, 1f)
  val effectiveDetailP = choreographer.detailCardAnim.value.coerceIn(0f, 1f)
  if (!choreographer.mounted || (effectiveTreeP <= 0.005f && effectiveDetailP <= 0.005f)) return

  val activeStage = choreographer.retainedOverlayStage
  if (activeStage == SettingsStage.ROOT_BRANCHES || activeStage == SettingsStage.CLOSED) return

  val isInteractive = stage == activeStage && effectiveTreeP > 0.55f
  val activeSection = if (activeStage == SettingsStage.LAUNCHER_TREE) choreographer.retainedLauncherSection else null
  val activeJarvisSection = if (activeStage == SettingsStage.AGENT_PLACEHOLDER) choreographer.retainedJarvisSection else null

  Box(
    modifier = modifier.fillMaxSize(),
  ) {
    if (activeStage == SettingsStage.LAUNCHER_TREE) {
      // STAGE 2 & 3: Branch 01 LAUNCHER Deep Tree (Sunken Card 01 -> Left Tree -> Right Detail HUD)
      OrthogonalBranchTreeStage(
        origin = geo.sunkenCardTopCenter(0),
        geo = geo,
        sections = LauncherSettingsSection.entries,
        activeSection = activeSection,
        tagOf = { it.tag },
        titleOf = { it.title },
        effectiveTreeP = effectiveTreeP,
        effectiveDetailP = effectiveDetailP,
        isInteractive = isInteractive,
        accent = accent,
        onSelectSection = onSelectSection,
      ) { section, innerReveal, innerEnabled ->
        LauncherDetailCardContent(
          section = section,
          reveal = innerReveal,
          enabled = innerEnabled,
          settings = settings,
          telemetry = telemetry,
          liveWeather = liveWeather,
          isDefaultLauncher = isDefaultLauncher,
          hasNotificationAccess = hasNotificationAccess,
          otaState = otaState,
          accent = accent,
          onAccentChange = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onAccentChange(it)
          },
          onFontChange = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onFontChange(it)
          },
          onFontScaleChange = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onFontScaleChange(it)
          },
          onUse24HourChange = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onUse24HourChange(it)
          },
          onRefreshRateSelect = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onRefreshRateSelect(it)
          },
          onRefreshWeather = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onRefreshWeather()
          },
          onOpenAlarms = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpenAlarms()
          },
          onOpenHomeSettings = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpenHomeSettings()
          },
          onOpenUsageSettings = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpenUsageSettings()
          },
          onOpenNotificationSettings = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpenNotificationSettings()
          },
          onCheckOta = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCheckOta()
          },
        )
      }
    } else if (activeStage == SettingsStage.AGENT_PLACEHOLDER) {
      // STAGE 2 & 3: Branch 02 JARVIS Deep Tree (Sunken Card 02 -> Left Tree -> Right Detail HUD)
      val jarvisState by JarvisRuntime.uiState.collectAsStateWithLifecycle()
      OrthogonalBranchTreeStage(
        origin = geo.sunkenCardTopCenter(1),
        geo = geo,
        sections = JarvisSettingsSection.entries,
        activeSection = activeJarvisSection,
        tagOf = { it.tag },
        titleOf = { it.title },
        effectiveTreeP = effectiveTreeP,
        effectiveDetailP = effectiveDetailP,
        isInteractive = isInteractive,
        accent = accent,
        onSelectSection = onSelectJarvisSection,
      ) { section, innerReveal, innerEnabled ->
        JarvisDetailCardContent(
          section = section,
          reveal = innerReveal,
          enabled = innerEnabled,
          uiState = jarvisState,
          accent = accent,
          onOpenJarvisDrawer = onOpenJarvisDrawer,
        )
      }
    } else if (activeStage == SettingsStage.SHIZUKU_PLACEHOLDER) {
      // Standby Branch 03 (SHIZUKU): laser starts from exact sunken Card 03 top center
      val origin = geo.sunkenCardTopCenter(2)
      val cardWDp = (geo.widthDp - 56.dp).coerceIn(220.dp, 312.dp)
      val cardHDp = 128.dp
      val cardLeftDp = (geo.widthDp - cardWDp) * 0.5f
      val cardTopDp = 154.dp

      val cardLeftPx = with(density) { cardLeftDp.toPx() }
      val cardTopPx = with(density) { cardTopDp.toPx() }
      val cardWPx = with(density) { cardWDp.toPx() }
      val cardHPx = with(density) { cardHDp.toPx() }
      val cardBottomPx = cardTopPx + cardHPx
      val cardCenterX = geo.cx
      val elbowY = (origin.y + cardBottomPx) * 0.5f

      Canvas(modifier = Modifier.fillMaxSize()) {
        if (effectiveTreeP <= 0.005f) return@Canvas
        val stemP = hudEaseOutCubic(phase(effectiveTreeP, 0.00f, 0.36f))
        val boxP = hudEaseOutCubic(phase(effectiveTreeP, 0.24f, 0.96f))

        if (stemP > 0.005f) {
          val pts = listOf(
            origin,
            Offset(origin.x, elbowY),
            Offset(cardCenterX, elbowY),
            Offset(cardCenterX, cardBottomPx),
          )
          val tip = drawOrthogonalPolyline(
            points = pts,
            progress = stemP,
            color = accent.copy(alpha = 0.82f),
            strokeWidth = 1.2f.dp.toPx(),
          )
          if (stemP < 0.97f) drawLaserTip(tip, accent)
        }

        if (boxP > 0.005f) {
          drawHudContainerFrame(
            left = cardLeftPx,
            top = cardTopPx,
            width = cardWPx,
            height = cardHPx,
            progress = boxP,
            accent = accent,
          )
        }
      }

      val bodyReveal = hudEaseOutCubic(phase(effectiveTreeP, 0.30f, 0.84f))
      if (bodyReveal > 0.01f) {
        Box(
          modifier = Modifier
            .padding(start = cardLeftDp, top = cardTopDp)
            .width(cardWDp)
            .height(cardHDp)
            .padding(14.dp),
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudHeaderDecryptBlock(
              eyebrow = "SYS.03 // SHIZUKU BRIDGE",
              title = "STANDBY // OS MONET",
              reveal = { phase(bodyReveal, 0f, 0.72f) },
              accent = accent,
            )
            Text(
              text = "Reserved for OS-wide Monet palette & voice styling.",
              color = TextSilver.copy(alpha = phase(bodyReveal, 0.35f, 1.0f)),
              fontSize = 11.5.sp,
              lineHeight = 15.sp,
            )
          }
        }
      }
    }
  }
}

/**
 * Shared two-column orthogonal HUD tree stage used identically by Branch 01 (`LAUNCHER`) and
 * Branch 02 (`JARVIS`), originating laser geometry from the sunken root card's top center into
 * 5 left-rail [CompactHudCard]s and extending a 90-degree elbow into a dual-traced right panel.
 */
@Composable
private fun <T> OrthogonalBranchTreeStage(
  origin: Offset,
  geo: SettingsTreeGeometry,
  sections: List<T>,
  activeSection: T?,
  tagOf: (T) -> String,
  titleOf: (T) -> String,
  effectiveTreeP: Float,
  effectiveDetailP: Float,
  isInteractive: Boolean,
  accent: Color,
  onSelectSection: (T?) -> Unit,
  detailContent: @Composable (section: T, reveal: () -> Float, enabled: Boolean) -> Unit,
) {
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current

  val trunkXDp = 16.dp
  val leftCardXDp = 28.dp
  val leftCardWDp = 116.dp
  val cardHeightDp = 52.dp
  val cardGapDp = 10.dp
  val topRowDp = 92.dp
  val rightElbowXDp = leftCardXDp + leftCardWDp + 10.dp
  val rightCardLeftDp = rightElbowXDp + 10.dp
  val rightCardWDp = (geo.widthDp - rightCardLeftDp - 14.dp).coerceAtLeast(150.dp)
  val rightCardHDp = cardHeightDp * sections.size + cardGapDp * (sections.size - 1).coerceAtLeast(0)

  val trunkXPx = with(density) { trunkXDp.toPx() }
  val leftCardXPx = with(density) { leftCardXDp.toPx() }
  val leftCardRightPx = with(density) { (leftCardXDp + leftCardWDp).toPx() }
  val cardHeightPx = with(density) { cardHeightDp.toPx() }
  val cardGapPx = with(density) { cardGapDp.toPx() }
  val topRowPx = with(density) { topRowDp.toPx() }
  val elbowXPx = with(density) { rightElbowXDp.toPx() }
  val rightCardLeftPx = with(density) { rightCardLeftDp.toPx() }
  val rightCardWPx = with(density) { rightCardWDp.toPx() }
  val rightCardHPx = with(density) { rightCardHDp.toPx() }

  val rowMidY: (Int) -> Float = { idx -> topRowPx + idx * (cardHeightPx + cardGapPx) + cardHeightPx * 0.5f }
  val lastRowIdx = (sections.size - 1).coerceAtLeast(0)
  val rowBottomPx = topRowPx + sections.size * cardHeightPx + lastRowIdx * cardGapPx
  val elbowTurnY = ((rowBottomPx + origin.y) * 0.5f)
    .coerceAtMost(origin.y - with(density) { 10.dp.toPx() })
    .coerceAtLeast(rowMidY(lastRowIdx) + with(density) { 8.dp.toPx() })

  Box(modifier = Modifier.fillMaxSize()) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      if (effectiveTreeP > 0.005f) {
        val stemP = hudEaseOutCubic(phase(effectiveTreeP, 0.00f, 0.28f))
        if (stemP > 0.005f) {
          val tip = drawOrthogonalPolyline(
            points = listOf(
              origin,
              Offset(origin.x, elbowTurnY),
              Offset(trunkXPx, elbowTurnY),
              Offset(trunkXPx, rowMidY(0)),
            ),
            progress = stemP,
            color = accent.copy(alpha = 0.88f),
            strokeWidth = 1.2f.dp.toPx(),
          )
          if (stemP < 0.97f) drawLaserTip(tip, accent)
        }

        sections.forEachIndexed { idx, section ->
          val branchStart = 0.16f + idx * 0.09f
          val branchP = hudEaseOutCubic(phase(effectiveTreeP, branchStart, (branchStart + 0.18f).coerceAtMost(1f)))
          val midY = rowMidY(idx)
          val isSelected = section == activeSection
          if (branchP > 0.005f) {
            val tip = drawOrthogonalPolyline(
              points = listOf(
                Offset(trunkXPx, midY),
                Offset(leftCardXPx, midY),
              ),
              progress = branchP,
              color = if (isSelected) accent else TreeRailColor.copy(alpha = 0.84f),
              strokeWidth = 1.15f.dp.toPx(),
            )
            if (branchP < 0.96f) drawLaserTip(tip, accent)
          }
        }
      }

      val activeIdx = activeSection?.let { sections.indexOf(it) } ?: -1
      if (activeIdx >= 0 && effectiveDetailP > 0.005f) {
        val linkP = hudEaseOutCubic(phase(effectiveDetailP, 0.00f, 0.30f))
        val boxP = hudEaseOutCubic(phase(effectiveDetailP, 0.14f, 0.96f))
        val sourceY = rowMidY(activeIdx)
        val targetY = topRowPx + 32.dp.toPx()

        if (linkP > 0.005f) {
          val tip = drawOrthogonalPolyline(
            points = listOf(
              Offset(leftCardRightPx, sourceY),
              Offset(elbowXPx, sourceY),
              Offset(elbowXPx, targetY),
              Offset(rightCardLeftPx, targetY),
            ),
            progress = linkP,
            color = accent.copy(alpha = 0.92f),
            strokeWidth = 1.25f.dp.toPx(),
          )
          if (linkP < 0.97f) drawLaserTip(tip, accent)
        }

        if (boxP > 0.005f) {
          drawHudContainerFrame(
            left = rightCardLeftPx,
            top = topRowPx,
            width = rightCardWPx,
            height = rightCardHPx,
            progress = boxP,
            accent = accent,
          )
        }
      }
    }

    Column(
      modifier = Modifier
        .padding(start = leftCardXDp, top = topRowDp)
        .width(leftCardWDp),
      verticalArrangement = Arrangement.spacedBy(cardGapDp),
    ) {
      sections.forEachIndexed { idx, section ->
        val cardReveal = hudEaseOutCubic(
          phase(effectiveTreeP, 0.22f + idx * 0.09f, (0.76f + idx * 0.06f).coerceAtMost(1f)),
        )
        val isSelected = section == activeSection
        CompactHudCard(
          eyebrow = tagOf(section),
          title = titleOf(section),
          pipColor = if (isSelected) accent else TreeRailColor.copy(alpha = 0.55f),
          focused = isSelected,
          accent = accent,
          titleSizeSp = 10.6f,
          reveal = { cardReveal },
          enabled = isInteractive && cardReveal > 0.30f,
          modifier = Modifier.fillMaxWidth().height(cardHeightDp),
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelectSection(if (isSelected) null else section)
          },
        )
      }
    }

    if (activeSection != null && effectiveDetailP > 0.06f) {
      val innerReveal = hudEaseOutCubic(phase(effectiveDetailP, 0.16f, 0.86f))
      Box(
        modifier = Modifier
          .padding(start = rightCardLeftDp, top = topRowDp)
          .width(rightCardWDp)
          .height(rightCardHDp)
          .padding(10.dp),
      ) {
        detailContent(activeSection, { innerReveal }, isInteractive && innerReveal > 0.35f)
      }
    }
  }
}

@Composable
private fun LauncherDetailCardContent(
  section: LauncherSettingsSection,
  reveal: () -> Float,
  enabled: Boolean,
  settings: LauncherSettingsConfig,
  telemetry: DeviceTelemetry,
  liveWeather: LiveWeather?,
  isDefaultLauncher: Boolean,
  hasNotificationAccess: Boolean,
  otaState: OtaState,
  accent: Color,
  onAccentChange: (LauncherAccentPreset) -> Unit,
  onFontChange: (LauncherFontStyle) -> Unit,
  onFontScaleChange: (Float) -> Unit,
  onUse24HourChange: (Boolean) -> Unit,
  onRefreshRateSelect: (Int?) -> Unit,
  onRefreshWeather: () -> Unit,
  onOpenAlarms: () -> Unit,
  onOpenHomeSettings: () -> Unit,
  onOpenUsageSettings: () -> Unit,
  onOpenNotificationSettings: () -> Unit,
  onCheckOta: () -> Unit,
) {
  val scrollState = rememberScrollState()
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(scrollState),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HudHeaderDecryptBlock(
      eyebrow = "NODE // ${section.tag}",
      title = section.title,
      reveal = { staggeredReveal(reveal(), 0, 5, stagger = 0.14f) },
      accent = accent,
    )

    when (section) {
      LauncherSettingsSection.THEME_COLOR -> {
        val presets = LauncherAccentPreset.entries
        val totalSteps = presets.size + 1
        presets.forEachIndexed { idx, preset ->
          val presetColor = preset.toColor()
          val selected = settings.accentPreset == preset
          CompactHudCard(
            title = preset.label.uppercase(),
            badge = if (selected) "ACTIVE" else preset.code,
            swatchColor = presetColor,
            focused = selected,
            accent = presetColor,
            titleSizeSp = 10.8f,
            reveal = { staggeredReveal(reveal(), idx + 1, totalSteps, stagger = 0.16f) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            onClick = { onAccentChange(preset) },
          )
        }
      }

      LauncherSettingsSection.FONT_SCALE -> {
        val styles = LauncherFontStyle.entries
        val scaleOptions = listOf(0.90f to "0.9X", 1.00f to "1.0X", 1.12f to "1.12X")
        val totalSteps = styles.size + 2
        styles.forEachIndexed { idx, styleItem ->
          val selected = settings.fontStyle == styleItem
          CompactHudCard(
            title = styleItem.label.uppercase(),
            badge = if (selected) "ACTIVE" else styleItem.code,
            focused = selected,
            accent = accent,
            titleSizeSp = 10.5f,
            reveal = { staggeredReveal(reveal(), idx + 1, totalSteps, stagger = 0.14f) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            onClick = { onFontChange(styleItem) },
          )
        }
        Spacer(Modifier.height(1.dp))
        HudMicroLabel(
          text = "TEXT SCALE",
          reveal = { staggeredReveal(reveal(), styles.size + 1, totalSteps, stagger = 0.14f) },
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          scaleOptions.forEachIndexed { sIdx, (scaleVal, tag) ->
            val selected = abs(settings.fontScale - scaleVal) < 0.03f
            CompactHudCard(
              title = tag,
              focused = selected,
              accent = accent,
              titleSizeSp = 10f,
              centerText = true,
              reveal = { staggeredReveal(reveal(), styles.size + 1, totalSteps, stagger = 0.14f) },
              enabled = enabled,
              modifier = Modifier.weight(1f).height(34.dp),
              onClick = { onFontScaleChange(scaleVal) },
            )
          }
        }
      }

      LauncherSettingsSection.CLOCK_DATE -> {
        val totalSteps = 4
        CompactHudCard(
          title = "24H MILITARY",
          badge = if (settings.use24Hour) "ON" else "24H",
          focused = settings.use24Hour,
          accent = accent,
          titleSizeSp = 10.8f,
          reveal = { staggeredReveal(reveal(), 1, totalSteps, stagger = 0.16f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(46.dp),
          onClick = { onUse24HourChange(true) },
        )
        CompactHudCard(
          title = "12H AM / PM",
          badge = if (!settings.use24Hour) "ON" else "12H",
          focused = !settings.use24Hour,
          accent = accent,
          titleSizeSp = 10.8f,
          reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.16f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(46.dp),
          onClick = { onUse24HourChange(false) },
        )
        CompactHudCard(
          eyebrow = "SYSTEM",
          title = "CLOCK & ALARMS",
          badge = "OPEN",
          focused = false,
          accent = accent,
          titleSizeSp = 10.5f,
          reveal = { staggeredReveal(reveal(), 3, totalSteps, stagger = 0.16f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(48.dp),
          onClick = onOpenAlarms,
        )
      }

      LauncherSettingsSection.DISPLAY_WEATHER -> {
        val hzChoices = listOf<Pair<Int?, String>>(null to "AUTO", 60 to "60HZ", 90 to "90HZ", 120 to "120HZ")
        val totalSteps = 4
        HudMicroLabel(
          text = "MOTO G45 // ${telemetry.refreshRateHz}HZ",
          reveal = { staggeredReveal(reveal(), 1, totalSteps, stagger = 0.14f) },
        )
        for (rowIdx in 0 until 2) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            for (colIdx in 0 until 2) {
              val choiceIdx = rowIdx * 2 + colIdx
              val (hzVal, title) = hzChoices[choiceIdx]
              val selected = hzVal != null && telemetry.refreshRateHz == hzVal
              CompactHudCard(
                title = title,
                focused = selected,
                accent = accent,
                titleSizeSp = 10.2f,
                centerText = true,
                reveal = { staggeredReveal(reveal(), 1 + rowIdx, totalSteps, stagger = 0.14f) },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(34.dp),
                onClick = { onRefreshRateSelect(hzVal) },
              )
            }
          }
        }
        val wxSubtitle = if (liveWeather != null) {
          "${liveWeather.tempCelsius}C · ${liveWeather.label.uppercase()}"
        } else {
          "PENDING TELEMETRY"
        }
        CompactHudCard(
          eyebrow = "WX // ${liveWeather?.city?.uppercase() ?: "LOCAL"}",
          title = wxSubtitle,
          badge = "SYNC",
          focused = liveWeather != null,
          accent = accent,
          titleSizeSp = 10.2f,
          reveal = { staggeredReveal(reveal(), 3, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(50.dp),
          onClick = onRefreshWeather,
        )
      }

      LauncherSettingsSection.PERMS_OTA -> {
        val totalSteps = 5
        CompactHudCard(
          title = "DEFAULT HOME",
          badge = if (isDefaultLauncher) "ACTIVE" else "GRANT",
          focused = isDefaultLauncher,
          accent = accent,
          titleSizeSp = 10.4f,
          reveal = { staggeredReveal(reveal(), 1, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(40.dp),
          onClick = onOpenHomeSettings,
        )
        CompactHudCard(
          title = "14D USAGE",
          badge = if (telemetry.hasUsageAccess) "OK" else "GRANT",
          focused = telemetry.hasUsageAccess,
          accent = accent,
          titleSizeSp = 10.4f,
          reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(40.dp),
          onClick = onOpenUsageSettings,
        )
        CompactHudCard(
          title = "NOTIFICATIONS",
          badge = if (hasNotificationAccess) "OK" else "GRANT",
          focused = hasNotificationAccess,
          accent = accent,
          titleSizeSp = 10.4f,
          reveal = { staggeredReveal(reveal(), 3, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(40.dp),
          onClick = onOpenNotificationSettings,
        )
        val (otaLabel, otaBadge, otaHighlight) = when (otaState) {
          OtaState.Idle -> Triple("CHECK & INSTALL", "OTA", true)
          OtaState.Checking -> Triple("SCANNING...", "WAIT", true)
          is OtaState.Available -> Triple("GET v${otaState.release.versionName}", "GET", true)
          is OtaState.Downloading -> Triple("DOWNLOADING...", "${otaState.progress}%", true)
          is OtaState.ReadyToInstall -> Triple("INSTALL v${otaState.release.versionName}", "RUN", true)
          is OtaState.Installing -> Triple("INSTALLING...", "WAIT", true)
          is OtaState.Installed -> Triple("UPDATE APPLIED", "DONE", true)
          OtaState.UpToDate -> Triple("UP TO DATE", "LATEST", false)
          is OtaState.Error -> Triple("RETRY (${otaState.message.take(10).uppercase()})", "ERR", true)
        }
        CompactHudCard(
          eyebrow = "OTA // FIRMWARE",
          title = otaLabel,
          badge = otaBadge,
          focused = otaHighlight,
          accent = if (otaState is OtaState.Error) CrimsonBright else accent,
          titleSizeSp = 10.2f,
          reveal = { staggeredReveal(reveal(), 4, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(50.dp),
          onClick = onCheckOta,
        )
      }
    }
  }
}

@Composable
private fun JarvisDetailCardContent(
  section: JarvisSettingsSection,
  reveal: () -> Float,
  enabled: Boolean,
  uiState: com.nexwork.edith.jarvis.core.JarvisUiState,
  accent: Color,
  onOpenJarvisDrawer: () -> Unit,
) {
  val context = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val scrollState = rememberScrollState()
  var apiKeyInput by remember { mutableStateOf("") }
  var revealApiKey by remember { mutableStateOf(false) }
  var commandDraft by remember { mutableStateOf("") }
  var contactDraft by remember { mutableStateOf("") }

  val bgLocLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    JarvisRuntime.refreshSensorsAfterPermissions(context)
    if (!granted) {
      JarvisRuntime.openAppDetailsSettings(context)
    }
  }
  val fgPermLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
  ) {
    JarvisRuntime.refreshSensorsAfterPermissions(context)
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
      JarvisRuntime.hasFineOrCoarseLocation(context) &&
      !JarvisRuntime.hasBackgroundLocation(context)
    ) {
      bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
  }
  val requestAmbientPermissions: () -> Unit = {
    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    if (!uiState.hasLocationPermission || !uiState.hasActivityRecognitionPermission || !uiState.hasBluetoothConnectPermission) {
      fgPermLauncher.launch(JarvisRuntime.foregroundContextPermissions())
    } else if (!uiState.hasBackgroundLocationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
      JarvisRuntime.refreshSensorsAfterPermissions(context)
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .imePadding()
      .verticalScroll(scrollState),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HudHeaderDecryptBlock(
      eyebrow = "SYS.02 // ${section.tag}",
      title = section.title,
      reveal = { staggeredReveal(reveal(), 0, 5, stagger = 0.14f) },
      accent = accent,
    )

    val pendingTier3 = uiState.pendingTier3Approval
    if (pendingTier3 != null) {
      CompactHudCard(
        eyebrow = pendingTier3.actionTitle,
        title = pendingTier3.actionSummary.uppercase(),
        badge = "BIO",
        pipColor = WarmSunAccent,
        focused = true,
        accent = WarmSunAccent,
        titleSizeSp = 9.4f,
        reveal = { staggeredReveal(reveal(), 1, 5, stagger = 0.14f) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          BiometricPromptHelper.authenticate(
            context = context,
            title = pendingTier3.actionTitle,
            subtitle = pendingTier3.actionSummary,
            description = pendingTier3.reason,
          ) { approved, _ ->
            JarvisRuntime.resolveTier3Approval(pendingTier3.requestId, approved)
          }
        },
      )
      CompactHudCard(
        title = "DENY TIER 3",
        focused = false,
        accent = CrimsonBright,
        titleSizeSp = 9.2f,
        centerText = true,
        reveal = { staggeredReveal(reveal(), 1, 5, stagger = 0.14f) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(34.dp),
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          JarvisRuntime.resolveTier3Approval(pendingTier3.requestId, false)
        },
      )
    }

    val bannerText = when {
      uiState.isExecutingCommand -> "EXECUTING // VOL- OR TAP TO STOP"
      uiState.isProcessing -> "TRIAGING // TAP TO STOP"
      uiState.pendingTier3Approval != null -> "TIER 3 GATE // TAP TO ABORT"
      !uiState.lastError.isNullOrBlank() -> uiState.lastError
      !uiState.lastStatusBanner.isNullOrBlank() -> uiState.lastStatusBanner
      else -> null
    }
    if (bannerText != null) {
      val busy = uiState.isProcessing || uiState.isExecutingCommand || uiState.pendingTier3Approval != null
      val isErr = !busy && !uiState.lastError.isNullOrBlank()
      CompactHudCard(
        eyebrow = when {
          busy -> "EMERGENCY STOP"
          isErr -> "FAULT"
          else -> "STATUS"
        },
        title = bannerText.uppercase(),
        badge = if (busy) "STOP" else "CLR",
        focused = true,
        accent = if (busy || isErr) CrimsonBright else accent,
        pipColor = if (busy || isErr) CrimsonBright else accent,
        titleSizeSp = 9.4f,
        reveal = { staggeredReveal(reveal(), 1, 5, stagger = 0.14f) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(42.dp),
        onClick = {
          if (busy) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            JarvisRuntime.emergencyStop(context, "Branch 02 STOP tapped")
          } else {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.clearBanner()
          }
        },
      )
    }

    when (section) {
      JarvisSettingsSection.RUNTIME -> {
        val totalSteps = 8
        val fgsRunning = uiState.serviceStatus.isRunning
        val ambient = uiState.ambientContext
        val currentSsid = ambient.wifiSsid?.trim().orEmpty()
        CompactHudCard(
          eyebrow = "AMBIENT // ${ambient.placeTag.name}${if (ambient.focusQuietActive) " · QUIET" else ""}",
          title = "${ambient.motionState.name} · ${currentSsid.ifEmpty { "NO WIFI" }.uppercase()}",
          badge = "${uiState.upcomingDestinations.size}D",
          pipColor = if (ambient.focusQuietActive) WarmSunAccent else accent,
          focused = ambient.focusQuietActive,
          accent = if (ambient.focusQuietActive) WarmSunAccent else accent,
          titleSizeSp = 9.6f,
          reveal = { staggeredReveal(reveal(), 1, totalSteps, stagger = 0.12f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(42.dp),
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpenJarvisDrawer()
          },
        )
        if (currentSsid.isNotEmpty()) {
          CompactHudCard(
            eyebrow = "SAVED PLACE PROFILE",
            title = "TAG '$currentSsid' HOME",
            badge = "HOME",
            focused = ambient.placeTag == PlaceTag.HOME,
            accent = accent,
            titleSizeSp = 9.5f,
            reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.12f) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.savePlaceProfile(context, currentSsid, PlaceTag.HOME, autoFocusQuiet = true)
            },
          )
        }
        CompactHudCard(
          eyebrow = "PROACTIVE DEBUG",
          title = "SIM STALL -> MAPS",
          badge = "SIM",
          focused = false,
          accent = accent,
          titleSizeSp = 9.5f,
          reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.12f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(40.dp),
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.simulateTrafficStallTrigger(context)
          },
        )
        CompactHudCard(
          eyebrow = if (fgsRunning) "STICKY FGS · TAP TO STOP" else "STICKY FGS",
          title = if (fgsRunning) "ACTIVE · ${uiState.serviceStatus.processedCount}" else "STOPPED",
          badge = if (fgsRunning) "STOP" else "START",
          pipColor = if (fgsRunning) accent else CrimsonBright,
          focused = fgsRunning,
          accent = if (fgsRunning) CrimsonBright else accent,
          titleSizeSp = 10.2f,
          reveal = { staggeredReveal(reveal(), 3, totalSteps, stagger = 0.12f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(44.dp),
          onClick = {
            if (fgsRunning) {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              JarvisRuntime.stopForegroundService(context)
            } else {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.startServiceSafely(context)
            }
          },
        )
        CompactHudCard(
          eyebrow = "TIER 3 GATE",
          title = "BIOMETRIC CHECK",
          badge = "TEST",
          focused = uiState.pendingTier3Approval != null,
          accent = WarmSunAccent,
          titleSizeSp = 9.8f,
          reveal = { staggeredReveal(reveal(), 4, totalSteps, stagger = 0.12f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(42.dp),
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.triggerTier3BiometricTest(context)
          },
        )
        HudMicroLabel(
          text = "CMD // ${uiState.playbooks.size} PLAYBOOKS",
          reveal = { staggeredReveal(reveal(), 5, totalSteps, stagger = 0.12f) },
        )
        HudSharpTextInput(
          value = commandDraft,
          onValueChange = { commandDraft = it },
          placeholder = "search app for ...",
          reveal = { staggeredReveal(reveal(), 5, totalSteps, stagger = 0.12f) },
          accent = accent,
          onDone = {
            if (commandDraft.isNotBlank() && !uiState.isExecutingCommand) {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              val cmd = commandDraft
              commandDraft = ""
              keyboardController?.hide()
              focusManager.clearFocus()
              JarvisRuntime.executeUiCommand(context, cmd)
            }
          },
        )
        CompactHudCard(
          title = if (uiState.isExecutingCommand) "■ STOP EXECUTION (VOL-)" else "RUN UI COMMAND",
          focused = uiState.isExecutingCommand || commandDraft.isNotBlank(),
          accent = if (uiState.isExecutingCommand) CrimsonBright else accent,
          pipColor = if (uiState.isExecutingCommand) CrimsonBright else accent,
          titleSizeSp = 9.6f,
          centerText = true,
          reveal = { staggeredReveal(reveal(), 6, totalSteps, stagger = 0.12f) },
          enabled = enabled && (uiState.isExecutingCommand || commandDraft.isNotBlank()),
          modifier = Modifier.fillMaxWidth().height(36.dp),
          onClick = {
            if (uiState.isExecutingCommand) {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              JarvisRuntime.stopCommandExecution(context, "Branch 02 STOP tapped")
            } else {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              val cmd = commandDraft
              commandDraft = ""
              keyboardController?.hide()
              focusManager.clearFocus()
              JarvisRuntime.executeUiCommand(context, cmd)
            }
          },
        )
        if (uiState.lastExecutionLog.isNotEmpty()) {
          Text(
            text = uiState.lastExecutionLog.takeLast(3).joinToString("\n"),
            color = TextSilver,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            lineHeight = 11.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      JarvisSettingsSection.PERMISSIONS -> {
        val totalSteps = 6
        val listenerOk = uiState.notificationListenerEnabled && uiState.notificationListenerConnected
        val listenerTag = when {
          listenerOk -> "LIVE"
          uiState.notificationListenerEnabled -> "BIND"
          else -> "GRANT"
        }
        val listenerTint = when {
          listenerOk -> accent
          uiState.notificationListenerEnabled -> WarmSunAccent
          else -> CrimsonBright
        }
        CompactHudCard(
          eyebrow = "SBN STREAM",
          title = "LISTENER",
          badge = listenerTag,
          pipColor = listenerTint,
          focused = listenerOk,
          accent = listenerTint,
          titleSizeSp = 10.4f,
          reveal = { staggeredReveal(reveal(), 1, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(44.dp),
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.openNotificationListenerSettings(context)
          },
        )
        val a11yOk = uiState.accessibilityEnabled && uiState.accessibilityConnected
        val a11yTag = when {
          a11yOk -> "LIVE"
          uiState.accessibilityEnabled -> "BIND"
          else -> "GRANT"
        }
        val a11yTint = when {
          a11yOk -> accent
          uiState.accessibilityEnabled -> WarmSunAccent
          else -> CrimsonBright
        }
        CompactHudCard(
          eyebrow = "UI HANDS",
          title = "ACCESSIBILITY",
          badge = a11yTag,
          pipColor = a11yTint,
          focused = a11yOk,
          accent = a11yTint,
          titleSizeSp = 10.2f,
          reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(44.dp),
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.openAccessibilitySettings(context)
          },
        )
        val ctxAllOk = uiState.hasLocationPermission &&
          uiState.hasBackgroundLocationPermission &&
          uiState.hasActivityRecognitionPermission &&
          uiState.hasBluetoothConnectPermission
        val ctxAnyOk = uiState.hasLocationPermission || uiState.hasActivityRecognitionPermission
        val ctxBadge = when {
          ctxAllOk -> "LIVE"
          ctxAnyOk -> "PART"
          else -> "GRANT"
        }
        val ctxTint = when {
          ctxAllOk -> accent
          ctxAnyOk -> WarmSunAccent
          else -> CrimsonBright
        }
        CompactHudCard(
          eyebrow = "LOC · BG · ACT · BT",
          title = "CONTEXT SENSORS",
          badge = ctxBadge,
          pipColor = ctxTint,
          focused = ctxAllOk,
          accent = ctxTint,
          titleSizeSp = 9.8f,
          reveal = { staggeredReveal(reveal(), 3, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(44.dp),
          onClick = requestAmbientPermissions,
        )
        CompactHudCard(
          eyebrow = "DOZE POLICY",
          title = if (uiState.batteryUnrestricted) "UNRESTRICTED" else "OPTIMIZED",
          badge = if (uiState.batteryUnrestricted) "OK" else "ALLOW",
          pipColor = if (uiState.batteryUnrestricted) accent else WarmSunAccent,
          focused = uiState.batteryUnrestricted,
          accent = if (uiState.batteryUnrestricted) accent else WarmSunAccent,
          titleSizeSp = 10.2f,
          reveal = { staggeredReveal(reveal(), 4, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(44.dp),
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.requestBatteryUnrestricted(context)
          },
        )
        CompactHudCard(
          eyebrow = "HUD CONSOLE",
          title = "FULL DRAWER",
          badge = "!JARVIS",
          focused = false,
          accent = accent,
          titleSizeSp = 10.0f,
          reveal = { staggeredReveal(reveal(), 5, totalSteps, stagger = 0.14f) },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(42.dp),
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpenJarvisDrawer()
          },
        )
      }

      JarvisSettingsSection.KEY_VAULT -> {
        val totalSteps = 5
        CompactHudCard(
          eyebrow = "AES-256 VAULT",
          title = if (uiState.hasApiKey) uiState.maskedApiKey else "UNCONFIGURED",
          badge = if (uiState.hasApiKey) "SEALED" else "UNSET",
          pipColor = if (uiState.hasApiKey) accent else WarmSunAccent,
          focused = uiState.hasApiKey,
          accent = if (uiState.hasApiKey) accent else WarmSunAccent,
          titleSizeSp = 10.0f,
          reveal = { staggeredReveal(reveal(), 1, totalSteps, stagger = 0.14f) },
          enabled = false,
          modifier = Modifier.fillMaxWidth().height(46.dp),
        )
        HudMicroLabel(
          text = if (uiState.hasApiKey) "REPLACE AIza... KEY" else "PASTE GEMINI KEY",
          reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.14f) },
        )
        HudSharpTextInput(
          value = apiKeyInput,
          onValueChange = { apiKeyInput = it },
          placeholder = if (uiState.hasApiKey) "Replace AIza... key" else "Paste API key",
          reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.14f) },
          accent = accent,
          passwordMask = !revealApiKey,
          onDone = {
            if (apiKeyInput.isNotBlank()) {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.saveApiKey(context, apiKeyInput)
              apiKeyInput = ""
              keyboardController?.hide()
              focusManager.clearFocus()
            }
          },
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          CompactHudCard(
            title = if (revealApiKey) "HIDE" else "SHOW",
            focused = revealApiKey,
            accent = accent,
            titleSizeSp = 9.5f,
            centerText = true,
            reveal = { staggeredReveal(reveal(), 3, totalSteps, stagger = 0.14f) },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(36.dp),
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              revealApiKey = !revealApiKey
            },
          )
          CompactHudCard(
            title = "SAVE",
            focused = apiKeyInput.isNotBlank(),
            accent = accent,
            titleSizeSp = 9.5f,
            centerText = true,
            reveal = { staggeredReveal(reveal(), 3, totalSteps, stagger = 0.14f) },
            enabled = enabled && apiKeyInput.isNotBlank(),
            modifier = Modifier.weight(1f).height(36.dp),
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.saveApiKey(context, apiKeyInput)
              apiKeyInput = ""
              keyboardController?.hide()
              focusManager.clearFocus()
            },
          )
          if (uiState.hasApiKey) {
            CompactHudCard(
              title = "CLR",
              focused = false,
              accent = CrimsonBright,
              titleSizeSp = 9.5f,
              centerText = true,
              reveal = { staggeredReveal(reveal(), 4, totalSteps, stagger = 0.14f) },
              enabled = enabled,
              modifier = Modifier.weight(0.85f).height(36.dp),
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                JarvisRuntime.clearApiKey(context)
                apiKeyInput = ""
              },
            )
          }
        }
      }

      JarvisSettingsSection.EVENT_FEED -> {
        val visibleEvents = uiState.events.take(12)
        val totalSteps = (visibleEvents.size + 2).coerceAtLeast(3)
        HudMicroLabel(
          text = "EVENTS // ${uiState.events.size} LOGGED",
          reveal = { staggeredReveal(reveal(), 1, totalSteps, stagger = 0.12f) },
        )
        if (visibleEvents.isEmpty()) {
          CompactHudCard(
            eyebrow = "SQLITE WAL",
            title = "NO EVENTS YET",
            badge = "IDLE",
            focused = false,
            accent = accent,
            titleSizeSp = 10.0f,
            reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.14f) },
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(46.dp),
          )
          CompactHudCard(
            eyebrow = "FULL CONSOLE",
            title = "OPEN !JARVIS",
            badge = "UI",
            focused = true,
            accent = accent,
            titleSizeSp = 10.0f,
            reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.14f) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onOpenJarvisDrawer()
            },
          )
        } else {
          visibleEvents.forEachIndexed { idx, event ->
            val cardReveal = { staggeredReveal(reveal(), (idx + 2).coerceAtMost(totalSteps - 1), totalSteps, stagger = 0.12f) }
            HudJarvisEventCard(
              event = event,
              reveal = cardReveal,
              enabled = enabled,
              accent = accent,
              onSendTestReply = { replyText ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                keyboardController?.hide()
                focusManager.clearFocus()
                JarvisRuntime.sendTestReply(context, event, replyText)
              },
            )
          }
        }
      }

      JarvisSettingsSection.RULEBOOK -> {
        val visibleContacts = uiState.trustedContacts.take(6)
        val visiblePlaybooks = uiState.playbooks.take(6)
        val totalSteps = (visibleContacts.size + visiblePlaybooks.size + 4).coerceAtLeast(5)
        HudMicroLabel(
          text = "TRUSTED CONTACTS (${uiState.trustedContacts.size})",
          reveal = { staggeredReveal(reveal(), 1, totalSteps, stagger = 0.12f) },
        )
        HudSharpTextInput(
          value = contactDraft,
          onValueChange = { contactDraft = it },
          placeholder = "Add trusted contact",
          reveal = { staggeredReveal(reveal(), 1, totalSteps, stagger = 0.12f) },
          accent = accent,
          onDone = {
            if (contactDraft.isNotBlank()) {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.addOrUpdateTrustedContact(context, contactDraft)
              contactDraft = ""
              keyboardController?.hide()
              focusManager.clearFocus()
            }
          },
        )
        if (contactDraft.isNotBlank()) {
          CompactHudCard(
            title = "+ TRUST ${contactDraft.trim().uppercase()}",
            focused = true,
            accent = accent,
            titleSizeSp = 9.5f,
            centerText = true,
            reveal = { staggeredReveal(reveal(), 2, totalSteps, stagger = 0.12f) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(34.dp),
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.addOrUpdateTrustedContact(context, contactDraft)
              contactDraft = ""
              keyboardController?.hide()
              focusManager.clearFocus()
            },
          )
        }
        visibleContacts.forEachIndexed { idx, contact ->
          CompactHudCard(
            eyebrow = contact.packageName.substringAfterLast('.').ifBlank { "ANY APP" }.uppercase(),
            title = contact.displayName.uppercase(),
            badge = if (contact.autoReplyEnabled) "TRUST" else "OFF",
            pipColor = if (contact.autoReplyEnabled) accent else WarmSunAccent,
            focused = contact.autoReplyEnabled,
            accent = if (contact.autoReplyEnabled) accent else WarmSunAccent,
            titleSizeSp = 9.8f,
            reveal = { staggeredReveal(reveal(), (idx + 2).coerceAtMost(totalSteps - 1), totalSteps, stagger = 0.12f) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.toggleTrustedContact(context, contact.contactKey, !contact.autoReplyEnabled)
            },
          )
        }
        HudMicroLabel(
          text = "UI PLAYBOOKS (${uiState.playbooks.size})",
          reveal = { staggeredReveal(reveal(), (visibleContacts.size + 2).coerceAtMost(totalSteps - 1), totalSteps, stagger = 0.12f) },
        )
        if (visiblePlaybooks.isEmpty()) {
          CompactHudCard(
            eyebrow = "SQLITE SKILL STORE",
            title = "NO PLAYBOOKS YET",
            badge = "0",
            focused = false,
            accent = accent,
            titleSizeSp = 9.8f,
            reveal = { staggeredReveal(reveal(), (visibleContacts.size + 3).coerceAtMost(totalSteps - 1), totalSteps, stagger = 0.12f) },
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(42.dp),
          )
        } else {
          visiblePlaybooks.forEachIndexed { idx, pb ->
            CompactHudCard(
              eyebrow = "${pb.steps.size} STEPS · ${pb.targetPackage.substringAfterLast('.')}",
              title = pb.triggerIntent.uppercase(),
              badge = "${pb.successCount}X",
              focused = pb.successCount > 0,
              accent = accent,
              titleSizeSp = 9.6f,
              reveal = {
                staggeredReveal(
                  reveal(),
                  (visibleContacts.size + idx + 3).coerceAtMost(totalSteps - 1),
                  totalSteps,
                  stagger = 0.10f,
                )
              },
              enabled = enabled,
              modifier = Modifier.fillMaxWidth().height(42.dp),
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                JarvisRuntime.executeUiCommand(context, pb.triggerIntent)
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun HudSharpTextInput(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  reveal: () -> Float,
  accent: Color,
  passwordMask: Boolean = false,
  onDone: (() -> Unit)? = null,
) {
  val p = reveal().coerceIn(0f, 1f)
  if (p <= 0.01f) return
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { alpha = phase(p, 0.08f, 0.45f) }
      .background(ObsidianBgTop.copy(alpha = 0.90f))
      .border(1.dp, accent.copy(alpha = 0.48f))
      .drawWithCache {
        val bar = 1.dp.toPx()
        val halfPerim = (size.width + size.height).coerceAtLeast(1f)
        val cornerAt = floatArrayOf(0f, size.width / halfPerim, 0f, size.width / halfPerim)
        onDrawBehind {
          drawCompactHudBrackets(
            trace = p,
            cornerAt = cornerAt,
            barPx = bar,
            scale = 0.42f,
            tint = Color.White.copy(alpha = 0.72f),
            trTint = accent.copy(alpha = 0.85f),
            blTint = accent.copy(alpha = 0.55f),
          )
        }
      }
      .padding(horizontal = 8.dp, vertical = 7.dp),
  ) {
    if (value.isEmpty()) {
      Text(
        text = placeholder,
        color = TextSilver.copy(alpha = 0.48f),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = true,
      textStyle = TextStyle(
        color = TextPureWhite,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.5.sp,
      ),
      cursorBrush = SolidColor(accent),
      visualTransformation = if (passwordMask) PasswordVisualTransformation() else VisualTransformation.None,
      keyboardOptions = KeyboardOptions(
        keyboardType = if (passwordMask) KeyboardType.Password else KeyboardType.Text,
        imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
      ),
      keyboardActions = KeyboardActions(
        onDone = { onDone?.invoke() },
      ),
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun HudJarvisEventCard(
  event: JarvisEventRecord,
  reveal: () -> Float,
  enabled: Boolean,
  accent: Color,
  onSendTestReply: (String) -> Unit,
) {
  val p = reveal().coerceIn(0f, 1f)
  if (p <= 0.01f) return

  val suggested = event.triage?.suggestedReply?.takeIf { it.isNotBlank() } ?: "Test reply from Jarvis"
  var replyDraft by remember(event.id, suggested) { mutableStateOf(suggested) }

  val urgencyLabel = when (event.triageStatus) {
    TriageStatus.PENDING -> "PENDING"
    TriageStatus.SKIPPED_NO_KEY -> "NO KEY"
    TriageStatus.FAILED -> "FAULT"
    TriageStatus.TRIAGED -> event.triage?.urgency?.uppercase() ?: "LOW"
  }
  val urgencyColor = when (urgencyLabel) {
    "CRITICAL", "FAULT" -> CrimsonBright
    "HIGH", "NO KEY" -> WarmSunAccent
    "MEDIUM" -> accent
    else -> TextSilver
  }
  val timeFormatted = remember(event.createdAtMs) {
    DateFormat.format("HH:mm", Date(event.createdAtMs)).toString()
  }
  val senderName = event.triage?.sender?.ifBlank { event.sender } ?: event.sender

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .drawWithCache {
        val stroke = 0.9f.dp.toPx()
        val bar = 1.dp.toPx()
        val halfPerim = (size.width + size.height).coerceAtLeast(1f)
        val cornerAt = floatArrayOf(0f, size.width / halfPerim, 0f, size.width / halfPerim)
        onDrawBehind {
          val pr = reveal().coerceIn(0f, 1f)
          if (pr <= 0.005f) return@onDrawBehind
          val clip = hudEaseOutCubic(phase(pr, 0f, 0.30f))
          if (clip < 0.999f) {
            clipRect(right = size.width * clip) { drawRect(brush = UnfocusedPlateBrush) }
          } else {
            drawRect(brush = UnfocusedPlateBrush)
          }
          drawCompactHudBrackets(
            trace = pr,
            cornerAt = cornerAt,
            barPx = bar,
            scale = 0.45f,
            tint = Color.White.copy(alpha = 0.50f),
            trTint = urgencyColor.copy(alpha = 0.85f),
            blTint = urgencyColor.copy(alpha = 0.45f),
          )
          drawDualTracedRect(
            width = (size.width - stroke).coerceAtLeast(1f),
            height = (size.height - stroke).coerceAtLeast(1f),
            progress = pr,
            strokeWidth = stroke,
            color = urgencyColor.copy(alpha = 0.48f),
            head = if (pr in 0.02f..0.98f) 1f else 0f,
          )
        }
      }
      .graphicsLayer { alpha = phase(p, 0.08f, 0.48f) }
      .padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "[$urgencyLabel] ${event.appLabel}",
        color = urgencyColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = timeFormatted,
        color = TextSilver.copy(alpha = 0.72f),
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
      )
    }

    Text(
      text = senderName,
      color = TextPureWhite,
      fontFamily = FontFamily.Monospace,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )

    if (event.body.isNotBlank()) {
      Text(
        text = event.body,
        color = TextSilver,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    event.triage?.let { triage ->
      Text(
        text = "${triage.intent.uppercase()} · ${triage.suggestedReply.ifBlank { "(NONE)" }}",
        color = accent.copy(alpha = 0.90f),
        fontFamily = FontFamily.Monospace,
        fontSize = 8.8.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (!event.errorMessage.isNullOrBlank()) {
      Text(
        text = event.errorMessage,
        color = CrimsonBright,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (!event.replySent.isNullOrBlank()) {
      Text(
        text = "SENT: \"${event.replySent}\"",
        color = accent,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (event.canReplyNow) {
      HudSharpTextInput(
        value = replyDraft,
        onValueChange = { replyDraft = it },
        placeholder = "Draft reply",
        reveal = reveal,
        accent = accent,
      )
      CompactHudCard(
        title = "SEND REPLY",
        focused = true,
        accent = accent,
        titleSizeSp = 9.2f,
        centerText = true,
        reveal = reveal,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(30.dp),
        onClick = { onSendTestReply(replyDraft) },
      )
    }
  }
}

/**
 * Compact HUD card primitive inheriting `HudAppCard`'s visual and kinetic DNA:
 * flat sharp dark-glass gradient fill wiped behind the trace, clockwise `drawTracedRect` perimeter
 * with laser head, scaled asymmetric `drawHudCornerBrackets` snapping onto the 4 corners via
 * `cornerAt`, and `monoPaint` + `drawDecrypt` cipher noise text reveal.
 */
@Composable
private fun CompactHudCard(
  title: String,
  reveal: () -> Float,
  accent: Color,
  modifier: Modifier = Modifier,
  eyebrow: String? = null,
  badge: String? = null,
  swatchColor: Color? = null,
  pipColor: Color? = null,
  focused: Boolean = false,
  centerText: Boolean = false,
  titleSizeSp: Float = 11.2f,
  enabled: Boolean = true,
  onClick: (() -> Unit)? = null,
) {
  val density = LocalDensity.current
  val curAccent = rememberUpdatedState(accent)
  val curFocused = rememberUpdatedState(focused)
  val curSwatch = rememberUpdatedState(swatchColor)
  val curPip = rememberUpdatedState(pipColor)
  val curTitle = rememberUpdatedState(title)
  val curEyebrow = rememberUpdatedState(eyebrow)
  val curBadge = rememberUpdatedState(badge)
  val curCenter = rememberUpdatedState(centerText)

  val eyebrowPaint = remember(density) { monoPaint(density, 7.8f, 0.9f, Paint.Align.LEFT, 165) }
  val titlePaint = remember(density, titleSizeSp) {
    monoPaint(density, titleSizeSp, 0.9f, Paint.Align.LEFT, 255).apply {
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
  }
  val badgePaint = remember(density) {
    monoPaint(density, 8.2f, 0.8f, Paint.Align.LEFT, 220).apply {
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
  }

  val clickMod = if (onClick != null) {
    Modifier.clickable(
      interactionSource = remember { MutableInteractionSource() },
      indication = null,
      enabled = enabled,
      onClick = onClick,
    )
  } else {
    Modifier
  }

  Box(
    modifier = modifier
      .then(clickMod)
      .drawWithCache {
        val tText = curTitle.value
        val eText = curEyebrow.value
        val bText = curBadge.value
        val centered = curCenter.value
        val hasSwatch = curSwatch.value != null
        val hasPip = curPip.value != null

        val stroke = 1.dp.toPx()
        val bar = 1.15f.dp.toPx()
        val keylineW = (size.width - stroke).coerceAtLeast(1f)
        val keylineH = (size.height - stroke).coerceAtLeast(1f)
        val halfPerim = (size.width + size.height).coerceAtLeast(1f)
        val midCorner = size.width / halfPerim
        val cornerAt = floatArrayOf(0f, midCorner, 0f, midCorner)
        val bracketScale = minOf(
          size.width / 150f.dp.toPx(),
          size.height / 72f.dp.toPx(),
        ).coerceIn(0.30f, 0.86f)

        val padX = if (size.width < 88.dp.toPx()) 6.dp.toPx() else 9.dp.toPx()
        val swatchSize = 7.dp.toPx()
        val pipSize = 5.dp.toPx()

        val textLeft = if (hasSwatch) padX + swatchSize + 6.dp.toPx() else padX
        val badgeWidth = if (bText != null) badgePaint.measureText(bText) else 0f
        val badgeX = size.width - padX - badgeWidth

        val titleRight = when {
          bText != null && eText == null -> (badgeX - 5.dp.toPx()).coerceAtLeast(textLeft + 8f)
          else -> (size.width - padX).coerceAtLeast(textLeft + 8f)
        }
        val eyebrowRight = when {
          bText != null && eText != null -> (badgeX - 5.dp.toPx()).coerceAtLeast(textLeft + 8f)
          hasPip && eText != null -> (size.width - padX - pipSize - 4.dp.toPx()).coerceAtLeast(textLeft + 8f)
          else -> (size.width - padX).coerceAtLeast(textLeft + 8f)
        }

        val titleAdvance = (titlePaint.measureText("MM") - titlePaint.measureText("M")).coerceAtLeast(1f)
        val maxTitleChars = ((titleRight - textLeft) / titleAdvance).toInt().coerceAtLeast(1)
        val shownTitle = if (tText.length <= maxTitleChars) tText else {
          if (maxTitleChars <= 1) "…" else tText.take(maxTitleChars - 1) + "…"
        }

        val shownEyebrow = eText?.let { raw ->
          val eAdvance = (eyebrowPaint.measureText("MM") - eyebrowPaint.measureText("M")).coerceAtLeast(1f)
          val maxE = ((eyebrowRight - textLeft) / eAdvance).toInt().coerceAtLeast(1)
          if (raw.length <= maxE) raw else if (maxE <= 1) "…" else raw.take(maxE - 1) + "…"
        }

        val titleX = if (centered) {
          ((size.width - titlePaint.measureText(shownTitle)) * 0.5f).coerceAtLeast(padX)
        } else {
          textLeft
        }
        val eyebrowBaseline = centerBaseline(eyebrowPaint, size.height * 0.30f)
        val titleBaseline = centerBaseline(
          titlePaint,
          if (shownEyebrow != null) size.height * 0.67f else size.height * 0.52f,
        )
        val badgeBaseline = centerBaseline(
          badgePaint,
          if (shownEyebrow != null) size.height * 0.30f else size.height * 0.52f,
        )
        val swatchTop = (size.height - swatchSize) * 0.5f
        val pipTop = if (shownEyebrow != null) 8.dp.toPx() else (size.height - pipSize) * 0.5f
        val seed = (tText.hashCode() * 31) + (eText?.hashCode() ?: 0)

        onDrawBehind {
          val p = reveal().coerceIn(0f, 1f)
          if (p <= 0.001f) return@onDrawBehind

          val isFocused = curFocused.value
          val activeAccent = curAccent.value
          val trace = phase(p, 0f, 1.00f)
          val plateClip = hudEaseOutCubic(phase(p, 0f, 0.30f))
          val eyebrowP = phase(p, 0.08f, 0.46f)
          val titleP = phase(p, 0.12f, 0.58f)
          val badgeP = phase(p, 0.18f, 0.64f)
          val pipP = phase(p, 0.06f, 0.38f)

          val plateBrush = if (isFocused) FocusedPlateBrush else UnfocusedPlateBrush
          if (plateClip < 0.999f) {
            clipRect(right = size.width * plateClip) {
              drawRect(brush = plateBrush)
            }
          } else {
            drawRect(brush = plateBrush)
          }

          curSwatch.value?.let { sw ->
            if (pipP > 0f) {
              drawRect(
                color = sw.copy(alpha = (if (isFocused) 1f else 0.78f) * pipP),
                topLeft = Offset(padX, swatchTop),
                size = Size(swatchSize, swatchSize),
              )
            }
          }

          curPip.value?.let { pip ->
            if (pipP > 0f) {
              drawRect(
                color = pip.copy(alpha = pip.alpha * pipP),
                topLeft = Offset(size.width - padX - pipSize, pipTop),
                size = Size(pipSize, pipSize),
              )
            }
          }

          if (shownEyebrow != null && eyebrowP > 0f) {
            val eColor = if (isFocused) activeAccent else TextSilver.copy(alpha = 0.68f)
            eyebrowPaint.color = android.graphics.Color.argb(
              (255 * eColor.alpha).roundToInt(),
              (eColor.red * 255).roundToInt(),
              (eColor.green * 255).roundToInt(),
              (eColor.blue * 255).roundToInt(),
            )
            drawDecrypt(shownEyebrow, eyebrowP, seed + 17, eyebrowPaint, textLeft, eyebrowBaseline)
          }

          if (titleP > 0f) {
            titlePaint.color = if (centered && isFocused) {
              android.graphics.Color.argb(
                255,
                (activeAccent.red * 255).roundToInt(),
                (activeAccent.green * 255).roundToInt(),
                (activeAccent.blue * 255).roundToInt(),
              )
            } else {
              android.graphics.Color.argb(if (isFocused) 255 else 235, 248, 250, 252)
            }
            drawDecrypt(
              text = shownTitle,
              progress = titleP,
              seed = seed,
              paint = titlePaint,
              x = titleX,
              baseline = titleBaseline,
              jitter = titlePaint.textSize * 0.05f,
            )
          }

          if (bText != null && badgeP > 0f) {
            val bColor = if (isFocused) activeAccent else TextSilver.copy(alpha = 0.72f)
            badgePaint.color = android.graphics.Color.argb(
              (255 * bColor.alpha).roundToInt(),
              (bColor.red * 255).roundToInt(),
              (bColor.green * 255).roundToInt(),
              (bColor.blue * 255).roundToInt(),
            )
            drawDecrypt(bText, badgeP, seed + 43, badgePaint, badgeX, badgeBaseline)
          }

          val bracketTint = Color.White.copy(alpha = if (isFocused) 0.96f else 0.44f)
          drawCompactHudBrackets(
            trace = trace,
            cornerAt = cornerAt,
            barPx = bar,
            scale = bracketScale,
            tint = bracketTint,
            trTint = if (isFocused) activeAccent.copy(alpha = 0.90f) else bracketTint,
            blTint = if (isFocused) activeAccent.copy(alpha = 0.48f) else bracketTint,
          )

          drawDualTracedRect(
            width = keylineW,
            height = keylineH,
            progress = trace,
            strokeWidth = stroke,
            color = if (isFocused) activeAccent.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.30f),
            head = if (trace in 0.02f..0.98f) 1f else 0f,
          )
        }
      },
  )
}

@Composable
private fun HudHeaderDecryptBlock(
  eyebrow: String,
  title: String,
  reveal: () -> Float,
  accent: Color,
) {
  val density = LocalDensity.current
  val curAccent = rememberUpdatedState(accent)
  val curEyebrow = rememberUpdatedState(eyebrow)
  val curTitle = rememberUpdatedState(title)
  val eyebrowPaint = remember(density) {
    monoPaint(density, 8.5f, 1.0f, Paint.Align.LEFT, 255).apply {
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
  }
  val titlePaint = remember(density) {
    monoPaint(density, 13.5f, 1.1f, Paint.Align.LEFT, 255).apply {
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(34.dp)
      .drawWithCache {
        val eText = curEyebrow.value
        val tText = curTitle.value
        val eBase = centerBaseline(eyebrowPaint, size.height * 0.28f)
        val tBase = centerBaseline(titlePaint, size.height * 0.74f)
        val advance = (titlePaint.measureText("MM") - titlePaint.measureText("M")).coerceAtLeast(1f)
        val maxChars = (size.width / advance).toInt().coerceAtLeast(1)
        val shownTitle = if (tText.length <= maxChars) tText else tText.take((maxChars - 1).coerceAtLeast(1)) + "…"
        val seed = eText.hashCode() + tText.hashCode()

        onDrawBehind {
          val p = reveal().coerceIn(0f, 1f)
          if (p <= 0.001f) return@onDrawBehind
          val ac = curAccent.value
          eyebrowPaint.color = android.graphics.Color.argb(
            255,
            (ac.red * 255).roundToInt(),
            (ac.green * 255).roundToInt(),
            (ac.blue * 255).roundToInt(),
          )
          val eP = phase(p, 0f, 0.70f)
          val tP = phase(p, 0.18f, 1.0f)
          if (eP > 0f) drawDecrypt(eText, eP, seed, eyebrowPaint, 0f, eBase)
          if (tP > 0f) drawDecrypt(shownTitle, tP, seed + 13, titlePaint, 0f, tBase, jitter = titlePaint.textSize * 0.04f)
        }
      },
  )
}

@Composable
private fun HudMicroLabel(
  text: String,
  reveal: () -> Float,
) {
  val density = LocalDensity.current
  val curText = rememberUpdatedState(text)
  val paint = remember(density) { monoPaint(density, 8.2f, 0.9f, Paint.Align.LEFT, 168) }
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(14.dp)
      .drawWithCache {
        val label = curText.value
        val base = centerBaseline(paint, size.height * 0.5f)
        val seed = label.hashCode()
        onDrawBehind {
          val p = reveal().coerceIn(0f, 1f)
          if (p > 0.01f) drawDecrypt(label, p, seed, paint, 0f, base)
        }
      },
  )
}

// ------------------------------------------------------------------
// Canvas Kinetic Laser-Trace & Bracket Helpers
// ------------------------------------------------------------------

private fun DrawScope.drawHudContainerFrame(
  left: Float,
  top: Float,
  width: Float,
  height: Float,
  progress: Float,
  accent: Color,
) {
  if (width <= 2f || height <= 2f || progress <= 0.005f) return
  val stroke = 1.dp.toPx()
  val bar = 1.2f.dp.toPx()
  val halfPerim = (width + height).coerceAtLeast(1f)
  val midCorner = width / halfPerim
  val cornerAt = floatArrayOf(0f, midCorner, 0f, midCorner)
  val plateClip = hudEaseOutCubic(phase(progress, 0f, 0.28f))

  withTransform({ translate(left, top) }) {
    if (plateClip < 0.999f) {
      clipRect(right = width * plateClip) {
        drawRect(brush = ContainerPlateBrush, size = Size(width, height))
      }
    } else {
      drawRect(brush = ContainerPlateBrush, size = Size(width, height))
    }
    drawCompactHudBrackets(
      trace = progress,
      cornerAt = cornerAt,
      barPx = bar,
      scale = 0.85f,
      tint = Color.White.copy(alpha = 0.92f),
      trTint = accent.copy(alpha = 0.88f),
      blTint = accent.copy(alpha = 0.45f),
      wOverride = width,
      hOverride = height,
    )
    drawDualTracedRect(
      width = (width - stroke).coerceAtLeast(1f),
      height = (height - stroke).coerceAtLeast(1f),
      progress = progress,
      strokeWidth = stroke,
      color = accent.copy(alpha = 0.82f),
      head = if (progress in 0.02f..0.98f) 1f else 0f,
    )
  }
}

private fun DrawScope.drawDualTracedRect(
  width: Float,
  height: Float,
  progress: Float,
  strokeWidth: Float,
  color: Color,
  head: Float = 0f,
) {
  if (width <= 0f || height <= 0f || progress <= 0f) return
  val inset = strokeWidth * 0.5f
  val x0 = inset
  val y0 = inset
  val remain = (width + height) * progress.coerceIn(0f, 1f)

  // Beam 1: Top-Left -> Top-Right -> Bottom-Right
  val topLen = minOf(remain, width)
  val topEnd = Offset(x0 + topLen, y0)
  drawLine(color, Offset(x0, y0), topEnd, strokeWidth, cap = StrokeCap.Round)
  var tip1 = topEnd
  val rem1 = remain - topLen
  if (rem1 > 0f) {
    val rightLen = minOf(rem1, height)
    tip1 = Offset(x0 + width, y0 + rightLen)
    drawLine(color, Offset(x0 + width, y0), tip1, strokeWidth, cap = StrokeCap.Round)
  }

  // Beam 2: Bottom-Right -> Bottom-Left -> Top-Left
  val bottomLen = minOf(remain, width)
  val bottomEnd = Offset(x0 + width - bottomLen, y0 + height)
  drawLine(color, Offset(x0 + width, y0 + height), bottomEnd, strokeWidth, cap = StrokeCap.Round)
  var tip2 = bottomEnd
  val rem2 = remain - bottomLen
  if (rem2 > 0f) {
    val leftLen = minOf(rem2, height)
    tip2 = Offset(x0, y0 + height - leftLen)
    drawLine(color, Offset(x0, y0 + height), tip2, strokeWidth, cap = StrokeCap.Round)
  }

  if (head > 0f && progress < 1f) {
    drawCircle(Color.White.copy(alpha = 0.20f * head), strokeWidth * 4.5f, tip1)
    drawCircle(Color.White.copy(alpha = 0.92f * head), strokeWidth * 1.4f, tip1)
    drawCircle(Color.White.copy(alpha = 0.20f * head), strokeWidth * 4.5f, tip2)
    drawCircle(Color.White.copy(alpha = 0.92f * head), strokeWidth * 1.4f, tip2)
  }
}

private fun DrawScope.drawLaserTip(pos: Offset, accent: Color) {
  drawCircle(accent.copy(alpha = 0.36f), 4.2f.dp.toPx(), pos)
  drawCircle(Color.White.copy(alpha = 0.95f), 1.5f.dp.toPx(), pos)
}

private fun DrawScope.drawOrthogonalPolyline(
  points: List<Offset>,
  progress: Float,
  color: Color,
  strokeWidth: Float,
): Offset {
  if (points.size < 2 || progress <= 0f) return points.firstOrNull() ?: Offset.Zero
  var totalLen = 0f
  val segLens = FloatArray(points.size - 1)
  for (i in 0 until points.size - 1) {
    val len = hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
    segLens[i] = len
    totalLen += len
  }
  if (totalLen <= 0.01f) return points.last()

  var remaining = totalLen * progress.coerceIn(0f, 1f)
  val drawPath = Path().apply { moveTo(points[0].x, points[0].y) }
  var tip = points[0]
  for (i in 0 until points.size - 1) {
    val len = segLens[i]
    val p1 = points[i]
    val p2 = points[i + 1]
    if (remaining >= len) {
      drawPath.lineTo(p2.x, p2.y)
      tip = p2
      remaining -= len
    } else {
      val f = if (len > 0f) (remaining / len).coerceIn(0f, 1f) else 0f
      tip = Offset(p1.x + (p2.x - p1.x) * f, p1.y + (p2.y - p1.y) * f)
      drawPath.lineTo(tip.x, tip.y)
      break
    }
  }
  drawPath(
    path = drawPath,
    color = color,
    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
  return tip
}
