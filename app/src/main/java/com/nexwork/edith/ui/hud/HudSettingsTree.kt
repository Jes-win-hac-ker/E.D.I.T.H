package com.nexwork.edith.ui.hud

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.data.OtaState
import com.nexwork.edith.model.DeviceTelemetry
import com.nexwork.edith.model.LauncherAccentPreset
import com.nexwork.edith.model.LauncherFontStyle
import com.nexwork.edith.model.LauncherSettingsConfig
import com.nexwork.edith.model.LauncherSettingsSection
import com.nexwork.edith.model.LiveWeather
import com.nexwork.edith.model.SettingsStage
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.GlassSurface
import com.nexwork.edith.theme.GlassSurfaceElevated
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.ObsidianBgTop
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import com.nexwork.edith.theme.toColor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val TreeRailColor = Color(0xFF64748B)
private val GlassBorderSubtle = Color(0x4D94A3B8)

/**
 * Kinetic 3-branch settings tree spawned from a 1.0s stationary long-press on the globe.
 *
 * Stage 1 (`ROOT_BRANCHES`): bilateral horizon arcs draw inward from left and right screen edges to
 * join dead center, shoot a perpendicular laser stem straight up, and split into 3 non-overlapping
 * root branch boxes (`LAUNCHER`, `AGENT`, `SHIZUKU`).
 *
 * Stage 2 (`LAUNCHER_TREE` / Standby): selecting a branch sinks the root menu down behind the globe
 * horizon while a left-side vertical laser stem rises and makes consecutive 90-degree right turns
 * into stacked non-overlapping cards, preserving open right-hand negative space.
 *
 * Stage 3 (Active detail card): tapping any left card draws an orthogonal 90-degree connector into
 * one single detail card on the right side.
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
  onCloseAll: () -> Unit,
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
) {
  val style = LocalLauncherStyle.current
  val accent = style.accent
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current

  val isOpen = stage != SettingsStage.CLOSED
  val isRootVisibleTarget = stage == SettingsStage.ROOT_BRANCHES
  val isRootReceding = isOpen && !isRootVisibleTarget

  val rootAnim = remember { Animatable(0f) }
  val rootCollapseAnim = remember { Animatable(0f) }
  val branchTreeAnim = remember { Animatable(0f) }
  val detailCardAnim = remember { Animatable(0f) }

  var mounted by remember { mutableStateOf(false) }

  LaunchedEffect(stage) {
    if (stage == SettingsStage.CLOSED) {
      detailCardAnim.animateTo(0f, tween(180, easing = LinearEasing))
      branchTreeAnim.animateTo(0f, tween(220, easing = LinearEasing))
      rootAnim.animateTo(0f, tween(260, easing = LinearEasing))
      rootCollapseAnim.snapTo(0f)
      mounted = false
      return@LaunchedEffect
    }

    mounted = true
    if (stage == SettingsStage.ROOT_BRANCHES) {
      branchTreeAnim.animateTo(0f, tween(180, easing = LinearEasing))
      detailCardAnim.snapTo(0f)
      rootCollapseAnim.animateTo(0f, tween(260, easing = LinearEasing))
      // Overlap slightly while HudClock finishes its 500ms unwind
      if (rootAnim.value < 0.05f) {
        delay(180L)
      }
      val remMs = ((1f - rootAnim.value).coerceIn(0f, 1f) * 680f).roundToInt().coerceAtLeast(1)
      rootAnim.animateTo(1f, tween(remMs, easing = LinearEasing))
    } else {
      // Collapse root menu smoothly downward behind globe while tracing deep branch
      if (rootAnim.value < 0.99f) {
        rootAnim.snapTo(1f)
      }
      rootCollapseAnim.animateTo(1f, tween(340, easing = LinearEasing))
      branchTreeAnim.snapTo(0f)
      branchTreeAnim.animateTo(1f, tween(640, easing = LinearEasing))
    }
  }

  LaunchedEffect(stage, selectedSection) {
    if (stage == SettingsStage.LAUNCHER_TREE && selectedSection != null) {
      detailCardAnim.snapTo(0f)
      detailCardAnim.animateTo(1f, tween(380, easing = LinearEasing))
    } else {
      detailCardAnim.animateTo(0f, tween(180, easing = LinearEasing))
    }
  }

  if (!mounted) return

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val widthDp = maxWidth
    val heightDp = maxHeight
    val widthPx = with(density) { widthDp.toPx() }
    val horizonY = (heightDp * 0.64f) - 10.dp
    val horizonYPx = with(density) { horizonY.toPx() }

    // ------------------------------------------------------------------
    // STAGE 1: Bilateral Horizon Arc -> Center Perpendicular -> 3 Cards
    // Recedes downward and clips behind the globe horizon when entering Stage 2.
    // ------------------------------------------------------------------
    val collapseP = hudEaseInCubic(rootCollapseAnim.value)
    val rootAlpha = (1f - collapseP * 1.15f).coerceIn(0f, 1f)
    val rootDropPx = collapseP * with(density) { 128.dp.toPx() }

    val sideMarginDp = 14.dp
    val boxGapDp = 10.dp
    val rootBoxW = ((widthDp - sideMarginDp * 2 - boxGapDp * 2) / 3f).coerceAtLeast(88.dp)
    val rootBoxH = 82.dp
    val rootRowTopDp = (horizonY - 196.dp).coerceAtLeast(88.dp)
    val rootJoinY = with(density) { (horizonY - 20.dp).toPx() }
    val rootStemJoinTopY = with(density) { (rootRowTopDp + rootBoxH + 28.dp).toPx() }
    val rootRowBottomY = with(density) { (rootRowTopDp + rootBoxH).toPx() }

    if (rootAlpha > 0.01f && (!isRootReceding || collapseP < 0.99f)) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
            alpha = rootAlpha
          }
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val p = rootAnim.value
          if (p <= 0.005f) return@Canvas

          clipRect(left = 0f, top = 0f, right = size.width, bottom = horizonYPx) {
            val drop = rootDropPx
            val cx = size.width * 0.5f

            // Phase 1 (0.00..0.38): bilateral horizon arcs form from left & right edges inward to dead center
            val arcP = hudEaseOutCubic(phase(p, 0.00f, 0.38f))
            val edgeY = (horizonYPx + 18.dp.toPx()) + drop
            val centerY = rootJoinY + drop

            if (arcP > 0.005f) {
              val arcStroke = Stroke(width = 1.2f.dp.toPx(), cap = StrokeCap.Round)
              val leftArc = Path().apply {
                moveTo(0f, edgeY)
                quadraticTo(cx * 0.45f, centerY - 16.dp.toPx(), cx, centerY)
              }
              val rightArc = Path().apply {
                moveTo(size.width, edgeY)
                quadraticTo(size.width - cx * 0.45f, centerY - 16.dp.toPx(), cx, centerY)
              }
              val lPt = traceQuadPath(leftArc, arcP, this, accent.copy(alpha = 0.72f), arcStroke)
              val rPt = traceQuadPath(rightArc, arcP, this, accent.copy(alpha = 0.72f), arcStroke)
              if (arcP in 0.03f..0.97f) {
                drawLaserTip(lPt, accent)
                drawLaserTip(rPt, accent)
              }
            }

            // Center join ignition spark
            if (p in 0.35f..0.52f) {
              val sparkP = 1f - phase(p, 0.36f, 0.52f)
              drawCircle(accent.copy(alpha = 0.42f * sparkP), 6.dp.toPx(), Offset(cx, centerY))
              drawCircle(Color.White.copy(alpha = 0.95f * sparkP), 2.2f.dp.toPx(), Offset(cx, centerY))
            }

            // Phase 2 (0.38..0.58): perpendicular laser line rises straight up from join point
            val stemP = hudEaseOutCubic(phase(p, 0.38f, 0.58f))
            val splitY = rootStemJoinTopY + drop
            if (stemP > 0.005f) {
              val curY = centerY + (splitY - centerY) * stemP
              drawLine(
                color = accent.copy(alpha = 0.85f),
                start = Offset(cx, centerY),
                end = Offset(cx, curY),
                strokeWidth = 1.2f.dp.toPx(),
                cap = StrokeCap.Round,
              )
              if (stemP < 0.98f) {
                drawLaserTip(Offset(cx, curY), accent)
              }
            }

            // Phase 3 (0.56..0.82): split into 3 orthogonal branches to left / middle / right cards
            val splitP = hudEaseOutCubic(phase(p, 0.56f, 0.82f))
            if (splitP > 0.005f) {
              val cBottomY = rootRowBottomY + drop
              val strokeW = 1.1f.dp.toPx()
              val marginPx = sideMarginDp.toPx()
              val gapPx = boxGapDp.toPx()
              val boxWPx = rootBoxW.toPx()
              val centers = listOf(
                marginPx + boxWPx * 0.5f,
                marginPx + boxWPx + gapPx + boxWPx * 0.5f,
                marginPx + (boxWPx + gapPx) * 2f + boxWPx * 0.5f,
              )
              for (tx in centers) {
                val tip = drawOrthogonalPolyline(
                  points = listOf(
                    Offset(cx, splitY),
                    Offset(tx, splitY),
                    Offset(tx, cBottomY),
                  ),
                  progress = splitP,
                  color = TreeRailColor.copy(alpha = 0.85f),
                  strokeWidth = strokeW,
                )
                if (splitP < 0.97f) {
                  drawLaserTip(tip, accent)
                }
              }
            }

            // Phase 4 (0.74..1.00): trace each root card outline clockwise
            val boxFrameP = hudEaseOutCubic(phase(p, 0.74f, 1.00f))
            if (boxFrameP > 0.005f) {
              val marginPx = sideMarginDp.toPx()
              val gapPx = boxGapDp.toPx()
              val boxWPx = rootBoxW.toPx()
              val boxHPx = rootBoxH.toPx()
              val topPx = rootRowTopDp.toPx() + drop
              for (idx in 0 until 3) {
                val left = marginPx + idx * (boxWPx + gapPx)
                drawRectLaserOutline(
                  rect = Rect(left, topPx, left + boxWPx, topPx + boxHPx),
                  progress = boxFrameP,
                  borderColor = accent.copy(alpha = if (idx == 0) 0.78f else 0.42f),
                  tipColor = accent,
                  cornerRadiusPx = 6.dp.toPx(),
                )
              }
            }
          }
        }

        // Interactive Stage 1 Cards (clipped above horizon during Stage 2 collapse)
        val cardsReveal = hudEaseOutCubic(phase(rootAnim.value, 0.80f, 1.00f))
        if (cardsReveal > 0.02f) {
          Row(
            modifier = Modifier
              .offset { IntOffset(0, rootDropPx.roundToInt()) }
              .padding(start = sideMarginDp, end = sideMarginDp, top = rootRowTopDp)
              .fillMaxWidth()
              .graphicsLayer { alpha = cardsReveal },
            horizontalArrangement = Arrangement.spacedBy(boxGapDp),
          ) {
            RootBranchCard(
              indexTag = "01",
              title = "LAUNCHER",
              subtitle = "HUD & UI",
              isActiveBranch = true,
              accent = accent,
              modifier = Modifier.width(rootBoxW).height(rootBoxH),
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelectBranch(SettingsStage.LAUNCHER_TREE)
              },
            )
            RootBranchCard(
              indexTag = "02",
              title = "AGENT",
              subtitle = "AI STANDBY",
              isActiveBranch = false,
              accent = accent,
              modifier = Modifier.width(rootBoxW).height(rootBoxH),
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelectBranch(SettingsStage.AGENT_PLACEHOLDER)
              },
            )
            RootBranchCard(
              indexTag = "03",
              title = "SHIZUKU",
              subtitle = "OS STANDBY",
              isActiveBranch = false,
              accent = accent,
              modifier = Modifier.width(rootBoxW).height(rootBoxH),
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelectBranch(SettingsStage.SHIZUKU_PLACEHOLDER)
              },
            )
          }
        }
      }
    }

    // ------------------------------------------------------------------
    // Top Header Bar (Breadcrumb + Back / Close) when settings is open
    // ------------------------------------------------------------------
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val headerTitle = when (stage) {
        SettingsStage.CLOSED -> ""
        SettingsStage.ROOT_BRANCHES -> "E.D.I.T.H // ROOT MATRIX"
        SettingsStage.LAUNCHER_TREE -> "SYS.01 // LAUNCHER"
        SettingsStage.AGENT_PLACEHOLDER -> "SYS.02 // AI AGENT"
        SettingsStage.SHIZUKU_PLACEHOLDER -> "SYS.03 // SHIZUKU OS"
      }
      HeaderChip(
        text = if (stage == SettingsStage.ROOT_BRANCHES) headerTitle else "<  $headerTitle",
        accent = accent,
        highlighted = stage != SettingsStage.ROOT_BRANCHES,
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          if (stage == SettingsStage.ROOT_BRANCHES) onCloseAll() else onBackStep()
        },
      )
      HeaderChip(
        text = "CLOSE",
        accent = accent,
        highlighted = false,
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          onCloseAll()
        },
      )
    }

    // ------------------------------------------------------------------
    // STAGE 2 & 3: LAUNCHER Deep Tree (Left 90-deg List + Right Detail Card)
    // ------------------------------------------------------------------
    if (stage == SettingsStage.LAUNCHER_TREE) {
      val treeP = branchTreeAnim.value
      val detailP = detailCardAnim.value
      val sections = LauncherSettingsSection.entries

      val trunkX = 18.dp
      val leftCardX = 32.dp
      val leftCardW = 118.dp
      val cardHeightDp = 52.dp
      val cardGapDp = 10.dp
      val topRowY = 90.dp
      val rightConnectorElbowX = (leftCardX + leftCardW + 10.dp)
      val rightCardLeftDp = rightConnectorElbowX + 10.dp
      val rightCardWidthDp = (widthDp - rightCardLeftDp - 14.dp).coerceAtLeast(150.dp)
      val rightCardTopDp = topRowY
      val rightCardHeightDp = (cardHeightDp * 5 + cardGapDp * 4).coerceAtLeast(280.dp)

      val trunkXPx = with(density) { trunkX.toPx() }
      val leftCardXPx = with(density) { leftCardX.toPx() }
      val leftCardRightPx = with(density) { (leftCardX + leftCardW).toPx() }
      val cardHeightPx = with(density) { cardHeightDp.toPx() }
      val cardGapPx = with(density) { cardGapDp.toPx() }
      val topRowYPx = with(density) { topRowY.toPx() }
      val elbowXPx = with(density) { rightConnectorElbowX.toPx() }
      val rightCardLeftPx = with(density) { rightCardLeftDp.toPx() }
      val rightCardTopPx = with(density) { rightCardTopDp.toPx() }
      val rightCardWPx = with(density) { rightCardWidthDp.toPx() }
      val rightCardHPx = with(density) { rightCardHeightDp.toPx() }

      val rowMidY: (Int) -> Float = { idx ->
        topRowYPx + idx * (cardHeightPx + cardGapPx) + cardHeightPx * 0.5f
      }
      val rowTopY: (Int) -> Float = { idx ->
        topRowYPx + idx * (cardHeightPx + cardGapPx)
      }

      Canvas(modifier = Modifier.fillMaxSize()) {
        if (treeP <= 0.005f) return@Canvas

        // 1. Vertical stem rises from horizon up to row 0 midpoint (0.00..0.34)
        val riseP = hudEaseOutCubic(phase(treeP, 0.00f, 0.34f))
        val topMidY = rowMidY(0)
        if (riseP > 0.005f) {
          val yCur = horizonYPx + (topMidY - horizonYPx) * riseP
          drawLine(
            color = accent.copy(alpha = 0.85f),
            start = Offset(trunkXPx, horizonYPx),
            end = Offset(trunkXPx, yCur),
            strokeWidth = 1.2f.dp.toPx(),
            cap = StrokeCap.Round,
          )
          if (riseP < 0.97f) drawLaserTip(Offset(trunkXPx, yCur), accent)
        }

        // 2. Sequential downward steps + 90-degree right turns + left card outlines
        sections.forEachIndexed { idx, section ->
          val startT = 0.24f + idx * 0.11f
          val branchP = hudEaseOutCubic(phase(treeP, startT, startT + 0.24f))
          val frameP = hudEaseOutCubic(phase(treeP, startT + 0.08f, (startT + 0.36f).coerceAtMost(1f)))
          val midY = rowMidY(idx)
          val selected = section == selectedSection

          if (branchP > 0.005f) {
            val prevY = if (idx == 0) topMidY else rowMidY(idx - 1)
            val points = listOf(
              Offset(trunkXPx, prevY),
              Offset(trunkXPx, midY),
              Offset(leftCardXPx, midY),
            )
            val tip = drawOrthogonalPolyline(
              points = points,
              progress = branchP,
              color = if (selected) accent else TreeRailColor.copy(alpha = 0.8f),
              strokeWidth = 1.1f.dp.toPx(),
            )
            if (branchP < 0.96f) drawLaserTip(tip, accent)
          }

          if (frameP > 0.005f) {
            val top = rowTopY(idx)
            drawRectLaserOutline(
              rect = Rect(leftCardXPx, top, leftCardRightPx, top + cardHeightPx),
              progress = frameP,
              borderColor = if (selected) accent else GlassBorderSubtle,
              tipColor = accent,
              cornerRadiusPx = 6.dp.toPx(),
            )
          }
        }

        // 3. Stage 3 orthogonal 90-degree connector -> Single large right detail card
        val activeIdx = selectedSection?.let { sections.indexOf(it) } ?: -1
        if (activeIdx >= 0 && detailP > 0.005f) {
          val linkP = hudEaseOutCubic(phase(detailP, 0.00f, 0.44f))
          val boxP = hudEaseOutCubic(phase(detailP, 0.34f, 1.00f))
          val sourceY = rowMidY(activeIdx)
          val targetY = rightCardTopPx + 42.dp.toPx()

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
            drawRectLaserOutline(
              rect = Rect(
                rightCardLeftPx,
                rightCardTopPx,
                rightCardLeftPx + rightCardWPx,
                rightCardTopPx + rightCardHPx,
              ),
              progress = boxP,
              borderColor = accent.copy(alpha = 0.82f),
              tipColor = accent,
              cornerRadiusPx = 8.dp.toPx(),
            )
          }
        }
      }

      // Left column clickable boxes
      Column(
        modifier = Modifier
          .padding(start = leftCardX, top = topRowY)
          .width(leftCardW),
        verticalArrangement = Arrangement.spacedBy(cardGapDp),
      ) {
        sections.forEachIndexed { idx, section ->
          val itemAlpha = hudEaseOutCubic(phase(treeP, 0.34f + idx * 0.11f, (0.56f + idx * 0.11f).coerceAtMost(1f)))
          val isSelected = section == selectedSection
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(cardHeightDp)
              .graphicsLayer { alpha = itemAlpha }
              .clip(RoundedCornerShape(6.dp))
              .background(if (isSelected) GlassSurfaceElevated else GlassSurface.copy(alpha = 0.88f))
              .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = itemAlpha > 0.3f,
              ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelectSection(if (isSelected) null else section)
              }
              .padding(horizontal = 10.dp, vertical = 7.dp),
          ) {
            Column(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = section.tag,
                color = if (isSelected) accent else TextSilver.copy(alpha = 0.68f),
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
              )
              Text(
                text = section.title,
                color = TextPureWhite,
                fontSize = 11.sp,
                fontFamily = style.fontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }

      // Single right-hand Detail Card inside the open negative space
      if (selectedSection != null && detailP > 0.18f) {
        val contentAlpha = hudEaseOutCubic(phase(detailP, 0.42f, 1.00f))
        Box(
          modifier = Modifier
            .padding(start = rightCardLeftDp, top = rightCardTopDp)
            .width(rightCardWidthDp)
            .height(rightCardHeightDp)
            .graphicsLayer { alpha = contentAlpha }
            .clip(RoundedCornerShape(8.dp))
            .background(GlassSurface.copy(alpha = 0.94f))
            .padding(12.dp),
        ) {
          LauncherDetailCardContent(
            section = selectedSection,
            settings = settings,
            telemetry = telemetry,
            liveWeather = liveWeather,
            isDefaultLauncher = isDefaultLauncher,
            hasNotificationAccess = hasNotificationAccess,
            otaState = otaState,
            accent = accent,
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
          )
        }
      }
    } else if (stage == SettingsStage.AGENT_PLACEHOLDER || stage == SettingsStage.SHIZUKU_PLACEHOLDER) {
      // Blank Standby Branch (as instructed for Agent & Shizuku)
      val treeP = branchTreeAnim.value
      val isAgent = stage == SettingsStage.AGENT_PLACEHOLDER
      val cardW = (widthDp - 64.dp).coerceAtLeast(220.dp)
      val cardH = 148.dp
      val cardLeftDp = (widthDp - cardW) * 0.5f
      val cardTopDp = 156.dp

      val trunkX = widthPx * 0.5f
      val cardLeftPx = with(density) { cardLeftDp.toPx() }
      val cardTopPx = with(density) { cardTopDp.toPx() }
      val cardWPx = with(density) { cardW.toPx() }
      val cardHPx = with(density) { cardH.toPx() }
      val elbowTurnY = cardTopPx + cardHPx + with(density) { 28.dp.toPx() }

      Canvas(modifier = Modifier.fillMaxSize()) {
        if (treeP <= 0.005f) return@Canvas
        val stemP = hudEaseOutCubic(phase(treeP, 0.00f, 0.55f))
        val boxP = hudEaseOutCubic(phase(treeP, 0.45f, 1.00f))
        if (stemP > 0.005f) {
          val tip = drawOrthogonalPolyline(
            points = listOf(
              Offset(trunkX, horizonYPx),
              Offset(trunkX, cardTopPx + cardHPx),
            ),
            progress = stemP,
            color = accent.copy(alpha = 0.72f),
            strokeWidth = 1.2f.dp.toPx(),
          )
          if (stemP < 0.97f) drawLaserTip(tip, accent)
          // Crossbar subtle tick
          if (stemP > 0.6f) {
            drawCircle(accent.copy(alpha = 0.7f), 2.5f.dp.toPx(), Offset(trunkX, elbowTurnY.coerceAtMost(horizonYPx - 10f)))
          }
        }
        if (boxP > 0.005f) {
          drawRectLaserOutline(
            rect = Rect(cardLeftPx, cardTopPx, cardLeftPx + cardWPx, cardTopPx + cardHPx),
            progress = boxP,
            borderColor = accent.copy(alpha = 0.56f),
            tipColor = accent,
            cornerRadiusPx = 8.dp.toPx(),
          )
        }
      }

      val bodyAlpha = hudEaseOutCubic(phase(treeP, 0.65f, 1.00f))
      Box(
        modifier = Modifier
          .padding(start = cardLeftDp, top = cardTopDp)
          .width(cardW)
          .height(cardH)
          .graphicsLayer { alpha = bodyAlpha }
          .clip(RoundedCornerShape(8.dp))
          .background(GlassSurface.copy(alpha = 0.90f))
          .padding(16.dp),
      ) {
        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = if (isAgent) "SYS.02 // AI AGENT MODULE" else "SYS.03 // SHIZUKU OS BRIDGE",
              color = accent,
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp,
            )
            Text(
              text = if (isAgent) "STANDBY // SLOT RESERVED" else "STANDBY // OS MONET & VOICE",
              color = TextPureWhite,
              fontSize = 14.sp,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = if (isAgent) {
                "Personalized agent controls will mount in this branch."
              } else {
                "Reserved for OS-wide Monet palette & voice styling (separate from Launcher theme)."
              },
              color = TextSilver,
              fontSize = 12.sp,
              lineHeight = 16.sp,
            )
          }
          HeaderChip(
            text = "< BACK TO BRANCHES",
            accent = accent,
            highlighted = true,
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onBackStep()
            },
          )
        }
      }
    }
  }
}

@Composable
private fun RootBranchCard(
  indexTag: String,
  title: String,
  subtitle: String,
  isActiveBranch: Boolean,
  accent: Color,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(6.dp))
      .background(GlassSurface.copy(alpha = 0.88f))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
      )
      .padding(horizontal = 9.dp, vertical = 10.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = indexTag,
          color = if (isActiveBranch) accent else TextSilver.copy(alpha = 0.7f),
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
        )
        Box(
          modifier = Modifier
            .size(6.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(if (isActiveBranch) accent else TreeRailColor.copy(alpha = 0.5f))
        )
      }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = title,
          color = TextPureWhite,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = subtitle,
          color = if (isActiveBranch) accent.copy(alpha = 0.9f) else TextSilver.copy(alpha = 0.72f),
          fontFamily = FontFamily.Monospace,
          fontSize = 8.5.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun HeaderChip(
  text: String,
  accent: Color,
  highlighted: Boolean,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(ObsidianBgTop.copy(alpha = 0.82f))
      .border(
        width = 0.8.dp,
        color = if (highlighted) accent.copy(alpha = 0.7f) else GlassBorderSubtle,
        shape = RoundedCornerShape(4.dp),
      )
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
      )
      .padding(horizontal = 10.dp, vertical = 7.dp),
  ) {
    Text(
      text = text,
      color = if (highlighted) accent else TextSilver,
      fontFamily = FontFamily.Monospace,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 0.8.sp,
    )
  }
}

@Composable
private fun LauncherDetailCardContent(
  section: LauncherSettingsSection,
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
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "NODE // ${section.tag}",
        color = accent,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
      )
      Text(
        text = section.title,
        color = TextPureWhite,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
      )
    }

    when (section) {
      LauncherSettingsSection.THEME_COLOR -> {
        Text(
          text = "Launcher UI accent only (independent from OS Shizuku Monet).",
          color = TextSilver,
          fontSize = 11.5.sp,
          lineHeight = 15.sp,
        )
        LauncherAccentPreset.entries.forEach { preset ->
          val presetColor = preset.toColor()
          val selected = settings.accentPreset == preset
          DetailOptionButton(
            label = preset.label,
            badge = if (selected) "ACTIVE" else preset.code,
            accent = presetColor,
            selected = selected,
            leadingSwatch = presetColor,
            onClick = { onAccentChange(preset) },
          )
        }
      }

      LauncherSettingsSection.FONT_SCALE -> {
        Text(
          text = "TYPEFACE",
          color = TextSilver,
          fontFamily = FontFamily.Monospace,
          fontSize = 9.5.sp,
          letterSpacing = 0.8.sp,
        )
        LauncherFontStyle.entries.forEach { styleItem ->
          val selected = settings.fontStyle == styleItem
          DetailOptionButton(
            label = styleItem.label,
            badge = if (selected) "ACTIVE" else styleItem.code,
            accent = accent,
            selected = selected,
            onClick = { onFontChange(styleItem) },
          )
        }
        Spacer(Modifier.height(2.dp))
        Text(
          text = "TEXT SCALE",
          color = TextSilver,
          fontFamily = FontFamily.Monospace,
          fontSize = 9.5.sp,
          letterSpacing = 0.8.sp,
        )
        val scaleOptions = listOf(0.90f to "0.9x", 1.00f to "1.0x", 1.12f to "1.12x")
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          scaleOptions.forEach { (scaleVal, tag) ->
            val selected = kotlin.math.abs(settings.fontScale - scaleVal) < 0.03f
            CompactChoiceButton(
              text = tag,
              accent = accent,
              selected = selected,
              modifier = Modifier.weight(1f),
              onClick = { onFontScaleChange(scaleVal) },
            )
          }
        }
      }

      LauncherSettingsSection.CLOCK_DATE -> {
        Text(
          text = "TIME FORMAT",
          color = TextSilver,
          fontFamily = FontFamily.Monospace,
          fontSize = 9.5.sp,
          letterSpacing = 0.8.sp,
        )
        DetailOptionButton(
          label = "24-Hour Military",
          badge = if (settings.use24Hour) "ON" else "24H",
          accent = accent,
          selected = settings.use24Hour,
          onClick = { onUse24HourChange(true) },
        )
        DetailOptionButton(
          label = "12-Hour (AM / PM)",
          badge = if (!settings.use24Hour) "ON" else "12H",
          accent = accent,
          selected = !settings.use24Hour,
          onClick = { onUse24HourChange(false) },
        )
        DetailActionButton(
          label = "Open Clock & Alarms",
          badge = "SYSTEM",
          accent = accent,
          onClick = onOpenAlarms,
        )
      }

      LauncherSettingsSection.DISPLAY_WEATHER -> {
        Text(
          text = "MOTO G45 REFRESH (${telemetry.refreshRateHz}Hz)",
          color = TextSilver,
          fontFamily = FontFamily.Monospace,
          fontSize = 9.5.sp,
          letterSpacing = 0.8.sp,
        )
        val hzChoices = listOf<Pair<Int?, String>>(null to "AUTO", 60 to "60", 90 to "90", 120 to "120")
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          hzChoices.forEach { (hzVal, title) ->
            val selected = hzVal != null && telemetry.refreshRateHz == hzVal
            CompactChoiceButton(
              text = title,
              accent = accent,
              selected = selected,
              modifier = Modifier.weight(1f),
              onClick = { onRefreshRateSelect(hzVal) },
            )
          }
        }
        Spacer(Modifier.height(2.dp))
        Text(
          text = if (liveWeather != null) {
            "WX: ${liveWeather.tempCelsius}°C · ${liveWeather.label} ${liveWeather.city}"
          } else {
            "WX: Pending IP telemetry"
          },
          color = TextSilver,
          fontSize = 11.5.sp,
        )
        DetailActionButton(
          label = "Refresh Live Weather",
          badge = "SYNC",
          accent = accent,
          onClick = onRefreshWeather,
        )
      }

      LauncherSettingsSection.PERMS_OTA -> {
        DetailOptionButton(
          label = "Default Launcher",
          badge = if (isDefaultLauncher) "ACTIVE" else "GRANT",
          accent = accent,
          selected = isDefaultLauncher,
          onClick = onOpenHomeSettings,
        )
        DetailOptionButton(
          label = "14d Usage Access",
          badge = if (telemetry.hasUsageAccess) "OK" else "GRANT",
          accent = accent,
          selected = telemetry.hasUsageAccess,
          onClick = onOpenUsageSettings,
        )
        DetailOptionButton(
          label = "Notification Access",
          badge = if (hasNotificationAccess) "OK" else "GRANT",
          accent = accent,
          selected = hasNotificationAccess,
          onClick = onOpenNotificationSettings,
        )
        Spacer(Modifier.height(2.dp))
        val (otaLabel, otaBadge, otaHighlight) = when (otaState) {
          OtaState.Idle -> Triple("Check & Install OTA", "UPDATE", true)
          OtaState.Checking -> Triple("Scanning Release...", "WAIT", true)
          is OtaState.Available -> Triple("Download v${otaState.release.versionName}", "GET", true)
          is OtaState.Downloading -> Triple("Downloading Package...", "${otaState.progress}%", true)
          is OtaState.ReadyToInstall -> Triple("Install v${otaState.release.versionName}", "INSTALL", true)
          is OtaState.Installing -> Triple("Installing Update...", "WAIT", true)
          is OtaState.Installed -> Triple("Update Applied", "DONE", true)
          OtaState.UpToDate -> Triple("Already Up To Date", "LATEST", false)
          is OtaState.Error -> Triple("Retry OTA (${otaState.message.take(14)})", "RETRY", true)
        }
        DetailActionButton(
          label = otaLabel,
          badge = otaBadge,
          accent = if (otaState is OtaState.Error) CrimsonBright else accent,
          emphasized = otaHighlight,
          onClick = onCheckOta,
        )
      }
    }
  }
}

@Composable
private fun DetailOptionButton(
  label: String,
  badge: String,
  accent: Color,
  selected: Boolean,
  leadingSwatch: Color? = null,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(if (selected) GlassSurfaceElevated else ObsidianBgTop.copy(alpha = 0.65f))
      .border(
        width = if (selected) 1.dp else 0.7.dp,
        color = if (selected) accent else GlassBorderSubtle,
        shape = RoundedCornerShape(6.dp),
      )
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
      )
      .padding(horizontal = 10.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.weight(1f),
    ) {
      if (leadingSwatch != null) {
        Box(
          modifier = Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(leadingSwatch)
        )
      }
      Text(
        text = label,
        color = TextPureWhite,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(Modifier.width(6.dp))
    Text(
      text = badge,
      color = if (selected) accent else TextSilver.copy(alpha = 0.75f),
      fontFamily = FontFamily.Monospace,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun CompactChoiceButton(
  text: String,
  accent: Color,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(5.dp))
      .background(if (selected) GlassSurfaceElevated else ObsidianBgTop.copy(alpha = 0.65f))
      .border(
        width = if (selected) 1.dp else 0.7.dp,
        color = if (selected) accent else GlassBorderSubtle,
        shape = RoundedCornerShape(5.dp),
      )
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
      )
      .padding(vertical = 9.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = if (selected) accent else TextPureWhite,
      fontFamily = FontFamily.Monospace,
      fontSize = 10.5.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun DetailActionButton(
  label: String,
  badge: String,
  accent: Color,
  emphasized: Boolean = false,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(if (emphasized) GlassSurfaceElevated else ObsidianBgTop.copy(alpha = 0.72f))
      .border(
        width = if (emphasized) 1.dp else 0.8.dp,
        color = if (emphasized) accent else GlassBorderSubtle,
        shape = RoundedCornerShape(6.dp),
      )
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
      )
      .padding(horizontal = 10.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      color = TextPureWhite,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(6.dp))
    Text(
      text = badge,
      color = accent,
      fontFamily = FontFamily.Monospace,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

// ------------------------------------------------------------------
// Canvas Kinetic Laser-Trace Helpers (Shared, Zero Allocations / Frame)
// ------------------------------------------------------------------

private fun DrawScope.drawLaserTip(pos: Offset, accent: Color) {
  drawCircle(accent.copy(alpha = 0.36f), 4.2f.dp.toPx(), pos)
  drawCircle(Color.White.copy(alpha = 0.95f), 1.5f.dp.toPx(), pos)
}

private fun traceQuadPath(
  path: Path,
  progress: Float,
  scope: DrawScope,
  color: Color,
  stroke: Stroke,
): Offset {
  val composePm = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
  val total = composePm.length
  val dst = Path()
  composePm.getSegment(0f, (total * progress).coerceIn(0f, total), dst, true)
  with(scope) {
    drawPath(dst, color = color, style = stroke)
  }
  val tanPos = composePm.getPosition((total * progress).coerceIn(0f, total))
  return if (tanPos == Offset.Unspecified) Offset.Zero else tanPos
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
      val f = (remaining / len).coerceIn(0f, 1f)
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

private fun DrawScope.drawRectLaserOutline(
  rect: Rect,
  progress: Float,
  borderColor: Color,
  tipColor: Color,
  cornerRadiusPx: Float,
) {
  val p = progress.coerceIn(0f, 1f)
  if (p <= 0.005f) return
  if (p >= 0.995f) {
    drawRoundRect(
      color = borderColor,
      topLeft = rect.topLeft,
      size = rect.size,
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
      style = Stroke(width = 1.dp.toPx()),
    )
    return
  }
  val pts = listOf(
    Offset(rect.left, rect.top),
    Offset(rect.right, rect.top),
    Offset(rect.right, rect.bottom),
    Offset(rect.left, rect.bottom),
    Offset(rect.left, rect.top),
  )
  val tip = drawOrthogonalPolyline(pts, p, borderColor, 1.dp.toPx())
  drawLaserTip(tip, tipColor)
}
