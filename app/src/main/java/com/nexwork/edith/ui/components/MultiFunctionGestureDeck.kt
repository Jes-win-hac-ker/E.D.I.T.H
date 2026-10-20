package com.nexwork.edith.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.theme.GlassBorderHighlight
import com.nexwork.edith.theme.GlassBorderSubtle
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Deck state enum for the continuous single-gesture multi-function button.
 */
enum class MfbDeckState {
  IDLE,
  HOLDING,
  CARD_EXPANDED,
  TAB_EXPANDED,
}

/**
 * Continuous Gesture Multi-Function Deck:
 * 1. Press and Hold MFB in the bottom corner.
 * 2. "App List" card slides out to the left from directly beneath the MFB icon.
 * 3. User slides finger onto the card (without lifting finger from the screen).
 * 4. Card activates with a reactive liquid glass shimmer sweep and haptic tick.
 * 5. Compact App List Tab reveals smoothly anchored above the card/MFB.
 * 6. User slides finger up/down inside the tab to scrub through all installed apps.
 * 7. When sliding pauses on an app for 1.0 second (dwell countdown), it auto-launches!
 * 8. Lifting finger before 1.0s smoothly cancels and collapses the deck back under MFB.
 */
@Composable
fun MultiFunctionGestureDeck(
  allApps: List<AppEntry>,
  onLaunchApp: (AppEntry) -> Unit,
  modifier: Modifier = Modifier,
  buttonSize: Dp = 50.dp,
) {
  val view = LocalView.current
  val density = LocalDensity.current
  val coroutineScope = rememberCoroutineScope()

  val sortedApps = remember(allApps) {
    allApps.sortedBy { it.label.lowercase() }
  }

  var deckState by remember { mutableStateOf(MfbDeckState.IDLE) }
  var isCardHovered by remember { mutableStateOf(false) }
  var hoveredAppIndex by remember { mutableIntStateOf(-1) }
  var dwellProgress by remember { mutableFloatStateOf(0f) }
  var stationaryStartTime by remember { mutableLongStateOf(0L) }
  var currentScrollPx by remember { mutableFloatStateOf(0f) }

  // Drag coordinates relative to MFB center
  var dragOffsetX by remember { mutableFloatStateOf(0f) }
  var dragOffsetY by remember { mutableFloatStateOf(0f) }

  // Animations
  val cardSlideAnim = remember { Animatable(0f) } // 0f = under icon, 1f = fully slid out
  val cardShimmerAnim = remember { Animatable(0f) } // 0f..1f reactive glass shimmer sweep
  val tabExpandAnim = remember { Animatable(0f) } // 0f..1f tab height expansion

  // 1.0s Dwell Auto-Launch continuous countdown loop
  LaunchedEffect(hoveredAppIndex, stationaryStartTime, deckState) {
    if (deckState == MfbDeckState.TAB_EXPANDED && hoveredAppIndex in sortedApps.indices && stationaryStartTime > 0L) {
      while (true) {
        val now = System.currentTimeMillis()
        val elapsed = now - stationaryStartTime
        val prog = (elapsed / 1000f).coerceIn(0f, 1f)
        dwellProgress = prog

        if (prog >= 1.0f) {
          view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
          val targetApp = sortedApps[hoveredAppIndex]
          onLaunchApp(targetApp)

          // Reset deck after launch
          deckState = MfbDeckState.IDLE
          hoveredAppIndex = -1
          dwellProgress = 0f
          stationaryStartTime = 0L
          break
        }
        delay(16L)
      }
    } else {
      dwellProgress = 0f
    }
  }

  // Target Unified Panel Geometry
  val panelWidth = 232.dp
  val panelWidthPx = with(density) { panelWidth.toPx() }
  val baseHeight = buttonSize // 50.dp
  val expandedHeight = 356.dp
  val baseHeightPx = with(density) { baseHeight.toPx() }
  val expandedHeightPx = with(density) { expandedHeight.toPx() }
  val itemHeightPx = with(density) { 44.dp.toPx() }

  // Horizontal slide geometry:
  // Slid out: right edge is at +10dp (tucked under the MFB right edge at +25dp).
  // Left edge = -222dp.
  // Center X = (-222dp + 10dp) / 2 = -106dp.
  val targetCenterXPx = with(density) { -106.dp.toPx() }
  val curCenterXPx = targetCenterXPx * cardSlideAnim.value

  // Vertical expansion geometry:
  // Bottom edge stays anchored at +baseHeight / 2 (+25dp).
  // Top edge expands UPWARDS from -25dp to -331dp.
  val curHeightPx = baseHeightPx + (expandedHeightPx - baseHeightPx) * tabExpandAnim.value
  val curCenterYPx = (baseHeightPx / 2f) - (curHeightPx / 2f)

  // Animate Card Slide
  LaunchedEffect(deckState) {
    if (deckState >= MfbDeckState.CARD_EXPANDED) {
      cardSlideAnim.animateTo(
        targetValue = 1f,
        animationSpec = spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMediumLow,
        ),
      )
    } else {
      cardSlideAnim.animateTo(
        targetValue = 0f,
        animationSpec = spring(
          dampingRatio = Spring.DampingRatioNoBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      )
    }
  }

  // Animate Tab Expand
  LaunchedEffect(deckState) {
    if (deckState == MfbDeckState.TAB_EXPANDED) {
      tabExpandAnim.animateTo(
        targetValue = 1f,
        animationSpec = spring(
          dampingRatio = 0.82f,
          stiffness = Spring.StiffnessMediumLow,
        ),
      )
    } else {
      tabExpandAnim.animateTo(
        targetValue = 0f,
        animationSpec = spring(
          dampingRatio = Spring.DampingRatioNoBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      )
    }
  }

  // Reactive Glass Shimmer on Card
  LaunchedEffect(isCardHovered) {
    if (isCardHovered) {
      view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
      cardShimmerAnim.snapTo(0f)
      cardShimmerAnim.animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 380),
      )
    } else {
      cardShimmerAnim.snapTo(0f)
    }
  }

  Box(
    modifier = modifier.size(buttonSize),
    contentAlignment = Alignment.Center,
  ) {
    // 1. Unified Sliding & Expanding Glass Panel (Base Card + App List Tab)
    if (cardSlideAnim.value > 0.01f) {
      Box(
        modifier =
          Modifier
            .offset { IntOffset(curCenterXPx.roundToInt(), curCenterYPx.roundToInt()) }
            .width(panelWidth)
            .height(with(density) { curHeightPx.toDp() })
            .alpha(cardSlideAnim.value)
            .scale(0.85f + 0.15f * cardSlideAnim.value)
            .zIndex(1f) // Drawn beneath MFB icon (zIndex 2f) so it emerges from under it
            .shadow(16.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(
              Brush.linearGradient(
                colors =
                  listOf(
                    Color(0xF20B0D12),
                    Color(0xEA121620),
                    Color(0xF20B0D12),
                  ),
                start = Offset(0f, 0f),
                end = Offset(panelWidthPx, curHeightPx),
              )
            )
            .border(
              width = 1.0.dp,
              brush =
                Brush.linearGradient(
                  colors =
                    if (isCardHovered || tabExpandAnim.value > 0.1f) listOf(
                      Color(0x90FFFFFF),
                      Color(0x4038BDF8),
                      Color(0x18FFFFFF),
                      Color(0x70FFFFFF),
                    ) else listOf(
                      Color(0x70FFFFFF),
                      Color(0x20FFFFFF),
                      Color(0x10FFFFFF),
                      Color(0x50FFFFFF),
                    ),
                  start = Offset(0f, 0f),
                  end = Offset(panelWidthPx, curHeightPx),
                ),
              shape = RoundedCornerShape(18.dp),
            ),
      ) {
        // Specular meniscus highlight along top edge of panel
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(24.dp)
              .align(Alignment.TopCenter)
              .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
              .background(
                Brush.verticalGradient(
                  colors =
                    listOf(
                      Color(0x45FFFFFF),
                      Color(0x12FFFFFF),
                      Color.Transparent,
                    )
                )
              )
        )

        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.SpaceBetween,
        ) {
          // A. Header (Fades in when tab expands)
          if (tabExpandAnim.value > 0.05f) {
            Column(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .alpha(tabExpandAnim.value)
            ) {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = "APPLICATIONS",
                  color = TextSilver,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  letterSpacing = 1.2.sp,
                  fontFamily = FontFamily.SansSerif,
                )

                Box(
                  modifier =
                    Modifier
                      .clip(RoundedCornerShape(6.dp))
                      .background(Color(0x22FFFFFF))
                      .border(0.5.dp, Color(0x30FFFFFF), RoundedCornerShape(6.dp))
                      .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                  Text(
                    text = "${sortedApps.size}",
                    color = IceGlowAccent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }

              // Hairline separator
              Box(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .height(0.6.dp)
                    .background(Color(0x1AFFFFFF))
              )
            }
          }

          // B. App List Items (Occupies scrollable middle space)
          if (tabExpandAnim.value > 0.05f) {
            val visibleCount = 6
            val firstVisibleIdx = (currentScrollPx / itemHeightPx).toInt().coerceIn(0, (sortedApps.size - visibleCount).coerceAtLeast(0))
            val lastVisibleIdx = (firstVisibleIdx + visibleCount).coerceAtMost(sortedApps.size)

            Column(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .weight(1f)
                  .alpha(tabExpandAnim.value)
                  .padding(horizontal = 8.dp, vertical = 2.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              for (idx in firstVisibleIdx until lastVisibleIdx) {
                val app = sortedApps[idx]
                val isHovered = (idx == hoveredAppIndex)

                CompactTabAppItem(
                  app = app,
                  isHovered = isHovered,
                  dwellProgress = if (isHovered) dwellProgress else 0f,
                )
              }
            }
          } else {
            Spacer(modifier = Modifier.weight(1f))
          }

          // C. Card Base Footer ("App List" Card, always anchored at bottom of container)
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(baseHeight)
                .background(
                  if (isCardHovered || tabExpandAnim.value > 0.1f) Color(0x2538BDF8) else Color(0x0AFFFFFF)
                ),
            contentAlignment = Alignment.Center,
          ) {
            // Hairline top border for footer when tab is open
            if (tabExpandAnim.value > 0.05f) {
              Box(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .height(0.6.dp)
                    .align(Alignment.TopCenter)
                    .background(Color(0x20FFFFFF))
              )
            }

            // Reactive Glass Shimmer Sweep Canvas
            if (cardShimmerAnim.value > 0.01f && cardShimmerAnim.value < 0.99f) {
              val sweepPos = cardShimmerAnim.value * (panelWidthPx + 100f) - 50f
              Canvas(modifier = Modifier.matchParentSize()) {
                drawRect(
                  brush =
                    Brush.linearGradient(
                      colors =
                        listOf(
                          Color.Transparent,
                          Color(0x30FFFFFF),
                          Color(0x85FFFFFF),
                          Color(0x30FFFFFF),
                          Color.Transparent,
                        ),
                      start = Offset(sweepPos - 40.dp.toPx(), 0f),
                      end = Offset(sweepPos + 40.dp.toPx(), size.height),
                    )
                )
              }
            }

            // Card Content: App grid glyph + "App List" label + indicator pip
            Row(
              modifier = Modifier.padding(horizontal = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              // App Grid Glyph
              Canvas(modifier = Modifier.size(17.dp)) {
                val stroke = 1.0.dp.toPx()
                val s = 6.0.dp.toPx()
                val gap = 2.4.dp.toPx()
                val c = if (isCardHovered || tabExpandAnim.value > 0.1f) IceGlowAccent else Color.White

                drawRoundRect(color = c, topLeft = Offset(0f, 0f), size = Size(s, s), style = Stroke(stroke))
                drawRoundRect(color = c, topLeft = Offset(s + gap, 0f), size = Size(s, s), style = Stroke(stroke))
                drawRoundRect(color = c, topLeft = Offset(0f, s + gap), size = Size(s, s), style = Stroke(stroke))
                drawRoundRect(color = c, topLeft = Offset(s + gap, s + gap), size = Size(s, s), style = Stroke(stroke))
              }

              Text(
                text = "App List",
                color = if (isCardHovered || tabExpandAnim.value > 0.1f) IceGlowAccent else TextPureWhite,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.4.sp,
              )

              Spacer(modifier = Modifier.weight(1f))

              // Orbital pip
              Text(
                text = if (isCardHovered || tabExpandAnim.value > 0.1f) "◈" else "›",
                color = if (isCardHovered || tabExpandAnim.value > 0.1f) IceGlowAccent else TextSilver,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }
    }

    // 3. Corner Multi-Function Button (MFB) Icon with Continuous Pointer Tracking Engine
    var isFingerTouching by remember { mutableStateOf(false) }

    Box(
      modifier =
        Modifier
          .zIndex(2f) // Higher than card so card emerges from beneath
          .size(buttonSize)
          .scale(if (isFingerTouching) 0.94f else 1f)
          .clip(RoundedCornerShape(buttonSize * 0.308f))
          .background(
            Brush.linearGradient(
              colors =
                listOf(
                  Color(0x38FFFFFF),
                  Color(0x14FFFFFF),
                  Color(0x0E0F172A),
                  if (deckState >= MfbDeckState.HOLDING) Color(0x4538BDF8) else Color(0x2838BDF8),
                ),
              start = Offset(0f, 0f),
              end = Offset.Infinite,
            )
          )
          .border(
            width = if (deckState >= MfbDeckState.HOLDING) 1.2.dp else 1.0.dp,
            brush =
              Brush.linearGradient(
                colors =
                  if (deckState >= MfbDeckState.HOLDING) listOf(
                    Color(0xFF38BDF8),
                    Color(0x8038BDF8),
                    Color(0xFF38BDF8),
                  ) else listOf(
                    Color(0x99FFFFFF),
                    Color(0x4038BDF8),
                    Color(0x18FFFFFF),
                    Color(0x3538BDF8),
                    Color(0x70FFFFFF),
                  ),
                start = Offset(0f, 0f),
                end = Offset.Infinite,
              ),
            shape = RoundedCornerShape(buttonSize * 0.308f),
          )
          .pointerInput(sortedApps) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              isFingerTouching = true
              deckState = MfbDeckState.HOLDING
              isCardHovered = false
              hoveredAppIndex = -1
              dwellProgress = 0f

              var lastMoveTime = System.currentTimeMillis()
              stationaryStartTime = 0L
              var lastPos = down.position
              var isHoldTriggered = false

              // Hold timer job
              val holdJob = coroutineScope.launch {
                delay(220L)
                if (deckState == MfbDeckState.HOLDING) {
                  isHoldTriggered = true
                  deckState = MfbDeckState.CARD_EXPANDED
                  view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
              }

              // Continuous pointer tracking loop (Single unbroken touch session)
              while (true) {
                if (deckState == MfbDeckState.IDLE) break

                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                if (!change.pressed) {
                  // Finger lifted off screen!
                  break
                }

                change.consume()
                val curPos = change.position
                val now = System.currentTimeMillis()

                // Calculate displacement from MFB center
                val dx = curPos.x - (size.width / 2f)
                val dy = curPos.y - (size.height / 2f)
                dragOffsetX = dx
                dragOffsetY = dy

                // Check movement velocity to detect if stationary
                val movedDist = hypot(curPos.x - lastPos.x, curPos.y - lastPos.y)
                if (movedDist > 4.dp.toPx()) {
                  lastPos = curPos
                  lastMoveTime = now
                  stationaryStartTime = 0L
                  dwellProgress = 0f
                } else if (stationaryStartTime == 0L && (now - lastMoveTime) > 60L) {
                  stationaryStartTime = now
                }

                // If hold triggered, evaluate card and tab interactions
                if (deckState >= MfbDeckState.CARD_EXPANDED) {
                  // 1. Hit-test Card Base Footer: dx in [-232dp..-20dp], dy in [-32dp..32dp]
                  val isOverCardBase = dx in (-232.dp.toPx()..-20.dp.toPx()) && abs(dy) < 32.dp.toPx()

                  if (isOverCardBase && !isCardHovered) {
                    isCardHovered = true
                    deckState = MfbDeckState.TAB_EXPANDED
                  }

                  // 2. If Tab is open, hit-test Tab items & handle scrubbing
                  if (deckState == MfbDeckState.TAB_EXPANDED) {
                    // List spans dy from -295dp (top of list) to -25dp (bottom of list, above card base)
                    val listTopY = with(density) { -295.dp.toPx() }
                    val listBottomY = with(density) { -25.dp.toPx() }
                    val listHeightPx = listBottomY - listTopY // ~270dp

                    if (dy in listTopY..listBottomY && sortedApps.isNotEmpty()) {
                      val relY = dy - listTopY

                      // Edge auto-scroll
                      val edgeThreshold = with(density) { 36.dp.toPx() }
                      if (relY < edgeThreshold && currentScrollPx > 0f) {
                        currentScrollPx = (currentScrollPx - 4.dp.toPx()).coerceAtLeast(0f)
                      } else if (relY > listHeightPx - edgeThreshold) {
                        val maxScroll = (sortedApps.size - 6).coerceAtLeast(0) * itemHeightPx
                        currentScrollPx = (currentScrollPx + 4.dp.toPx()).coerceAtMost(maxScroll)
                      }

                      // Compute hovered index
                      val yInsideList = relY + currentScrollPx
                      val newHoverIdx = (yInsideList / itemHeightPx).toInt().coerceIn(0, sortedApps.lastIndex)

                      if (newHoverIdx != hoveredAppIndex) {
                        hoveredAppIndex = newHoverIdx
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        stationaryStartTime = now
                        dwellProgress = 0f
                      }
                    } else if (dy > listBottomY) {
                      // Finger moved down onto the Card Base Footer
                      if (hoveredAppIndex != -1) {
                        hoveredAppIndex = -1
                        dwellProgress = 0f
                        stationaryStartTime = 0L
                      }
                    }
                  }
                }
              }

              // On Release / Touch UP
              holdJob.cancel()
              isFingerTouching = false
              deckState = MfbDeckState.IDLE
              isCardHovered = false
              hoveredAppIndex = -1
              dwellProgress = 0f
              stationaryStartTime = 0L
            }
          },
      contentAlignment = Alignment.Center,
    ) {
      // Specular meniscus highlight at top of MFB
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(buttonSize * 0.31f)
            .align(Alignment.TopCenter)
            .clip(RoundedCornerShape(topStart = buttonSize * 0.308f, topEnd = buttonSize * 0.308f))
            .background(
              Brush.verticalGradient(
                colors =
                  listOf(
                    Color(0x55FFFFFF),
                    Color(0x18FFFFFF),
                    Color.Transparent,
                  )
              )
            )
      )

      // Inner liquid ambient radial bloom
      Box(
        modifier =
          Modifier
            .size(buttonSize * 0.69f)
            .clip(RoundedCornerShape(buttonSize * 0.22f))
            .background(
              Brush.radialGradient(
                colors =
                  listOf(
                    if (deckState >= MfbDeckState.HOLDING) Color(0x6038BDF8) else Color(0x3338BDF8),
                    Color.Transparent,
                  )
              )
            )
      )

      // Precision HUD Reticle Glyph
      Canvas(modifier = Modifier.size(buttonSize * 0.44f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val strokeW = 1.25.dp.toPx()

        // Center ring
        val ringRadius = 4.5.dp.toPx()
        drawCircle(
          color = Color.White.copy(alpha = 0.92f),
          radius = ringRadius,
          center = Offset(cx, cy),
          style = Stroke(width = strokeW),
        )

        // Center glowing core dot
        drawCircle(
          color = IceGlowAccent,
          radius = if (deckState >= MfbDeckState.HOLDING) 2.4.dp.toPx() else 1.6.dp.toPx(),
          center = Offset(cx, cy),
        )

        // 4 Cardinal precision ticks
        val tickInner = ringRadius + 2.0.dp.toPx()
        val tickOuter = tickInner + 3.2.dp.toPx()

        drawLine(color = Color.White.copy(alpha = 0.85f), start = Offset(cx, cy - tickInner), end = Offset(cx, cy - tickOuter), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color = Color.White.copy(alpha = 0.85f), start = Offset(cx, cy + tickInner), end = Offset(cx, cy + tickOuter), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color = Color.White.copy(alpha = 0.85f), start = Offset(cx - tickInner, cy), end = Offset(cx - tickOuter, cy), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color = Color.White.copy(alpha = 0.85f), start = Offset(cx + tickInner, cy), end = Offset(cx + tickOuter, cy), strokeWidth = strokeW, cap = StrokeCap.Round)
      }
    }
  }
}

/**
 * Compact App List Item with 1.0s Dwell Auto-Launch Indicator.
 * Idle items are clean and transparent without clunky gray box tiles.
 * The hovered item receives a frosted glass specular pill background and the 1.0s charging ring.
 */
@Composable
private fun CompactTabAppItem(
  app: AppEntry,
  isHovered: Boolean,
  dwellProgress: Float,
) {
  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(42.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(
          if (isHovered) Brush.linearGradient(
            colors = listOf(
              Color(0x3538BDF8),
              Color(0x1838BDF8),
              Color(0x10FFFFFF),
            )
          ) else Brush.linearGradient(
            colors = listOf(
              Color.Transparent,
              Color.Transparent,
            )
          )
        )
        .border(
          width = if (isHovered) 1.0.dp else 0.dp,
          color = if (isHovered) IceGlowAccent.copy(alpha = 0.8f) else Color.Transparent,
          shape = RoundedCornerShape(10.dp),
        )
        .padding(horizontal = 8.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      // Liquid Glass App Icon
      LiquidGlassAppIcon(
        packageName = app.packageName,
        label = app.label,
        icon = app.icon,
        size = 28.dp,
      )

      // App Label
      Text(
        text = app.label,
        color = if (isHovered) TextPureWhite else Color(0xD5FFFFFF),
        fontSize = 12.sp,
        letterSpacing = 0.2.sp,
        fontWeight = if (isHovered) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )

      // Dwell Auto-Launch Ring Indicator
      if (isHovered && dwellProgress > 0.02f) {
        Box(
          modifier = Modifier.size(18.dp),
          contentAlignment = Alignment.Center,
        ) {
          Canvas(modifier = Modifier.size(15.dp)) {
            // Track
            drawCircle(
              color = Color(0x35FFFFFF),
              radius = size.width / 2f,
              style = Stroke(width = 1.4.dp.toPx()),
            )

            // Progress Arc
            drawArc(
              color = IceGlowAccent,
              startAngle = -90f,
              sweepAngle = dwellProgress * 360f,
              useCenter = false,
              style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round),
            )
          }
        }
      }
    }
  }
}
