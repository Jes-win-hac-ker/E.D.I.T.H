package com.nexwork.edith.ui.main

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.delay
import com.nexwork.edith.LocalHomeResetSignal
import com.nexwork.edith.model.HudIntensityPreset
import com.nexwork.edith.model.SettingsStage
import com.nexwork.edith.theme.EDITHTheme
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.toGlobePalette
import com.nexwork.edith.ui.hud.BindHudSettingsChoreography
import com.nexwork.edith.ui.hud.CrimeSceneFocusOverlay
import com.nexwork.edith.ui.hud.HudCardDeck
import com.nexwork.edith.ui.hud.HudClock
import com.nexwork.edith.ui.hud.HudKeyboard
import com.nexwork.edith.ui.hud.HudKeyboardLogic
import com.nexwork.edith.ui.hud.HudOtaPill
import com.nexwork.edith.ui.hud.HudSearchBar
import com.nexwork.edith.ui.hud.filterMatchingApps
import com.nexwork.edith.ui.hud.HudSettingsOverlayLayer
import com.nexwork.edith.ui.hud.HudSettingsRootLayer
import com.nexwork.edith.ui.hud.GlobeController
import com.nexwork.edith.ui.hud.InteractiveWireframeGlobe
import com.nexwork.edith.ui.hud.groupApps
import com.nexwork.edith.ui.hud.rememberHudSettingsChoreographer

/**
 * Home screen — Stark HUD void with the flat search line.
 * Swipe down opens search; swipe up opens the same console over the full app deck, so browsing
 * and searching are one surface. Back, a tap outside, or an upward swipe dismisses it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit = {},
  viewModel: MainScreenViewModel = viewModel(),
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  LaunchedEffect(Unit) {
    viewModel.initContext(context)
  }

  val state by viewModel.launcherState.collectAsStateWithLifecycle()
  val haptic = LocalHapticFeedback.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  var isResumed by remember { mutableStateOf(true) }
  var isCardActionArmed by remember { mutableStateOf(false) }
  var lastScreenActivityMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var lastAmbientWakeMs by remember { mutableLongStateOf(0L) }
  val isAmbientCenteredState = rememberUpdatedState(state.isAmbientCentered)
  val isSettingsOpen = rememberUpdatedState(state.settingsStage != SettingsStage.CLOSED)
  val globeController = remember { GlobeController() }
  val isGlobeAtHorizon by globeController.isGlobeAtHorizon.collectAsStateWithLifecycle()
  val isStartupComplete by globeController.isStartupComplete.collectAsStateWithLifecycle()
  var isClockMounted by remember { mutableStateOf(false) }
  var isOtaMounted by remember { mutableStateOf(false) }
  val isHudMounted = isClockMounted || isOtaMounted
  val settingsChoreographer = rememberHudSettingsChoreographer(state.settingsStage)
  var isSettingsMounted by remember { mutableStateOf(state.settingsStage != SettingsStage.CLOSED) }
  BindHudSettingsChoreography(
    choreographer = settingsChoreographer,
    stage = state.settingsStage,
    selectedSection = state.selectedSettingsSection,
    onMountedChange = { isSettingsMounted = it },
  )
  var clockRedrawSignal by remember { mutableIntStateOf(0) }
  var lastHapticRedrawSignal by remember { mutableIntStateOf(0) }
  var lastHomeWakeMs by remember { mutableLongStateOf(0L) }
  var screenOffAtMs by remember { mutableLongStateOf(0L) }
  var pendingEarthRegen by remember { mutableStateOf(false) }
  val homeResetSignal = LocalHomeResetSignal.current

  val triggerHomeWake: () -> Unit = {
    val now = System.currentTimeMillis()
    lastScreenActivityMs = now
    keyboardController?.hide()
    focusManager.clearFocus()
    viewModel.wakeFromAmbient()
    viewModel.closeSpotlightSearch()
    viewModel.closeAppBrowse()
    viewModel.dismissSettings()
    if (now - lastHomeWakeMs > 600L) {
      lastHomeWakeMs = now
      clockRedrawSignal++
      if (pendingEarthRegen) {
        pendingEarthRegen = false
        globeController.playStartupAnimation()
      }
    }
  }

  LaunchedEffect(homeResetSignal) {
    if (homeResetSignal > 0) {
      triggerHomeWake()
    }
  }

  val settingsBehindGlobe = settingsChoreographer.mounted && !settingsChoreographer.isRootAboveGlobe
  LaunchedEffect(settingsBehindGlobe) {
    globeController.setSettingsBackdropActive(settingsBehindGlobe)
  }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    isResumed = true
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    if (keyguardManager?.isKeyguardLocked != true && !isSettingsOpen.value) {
      triggerHomeWake()
    }
    viewModel.refreshLauncherData()
    viewModel.checkForUpdates(force = false)
    viewModel.ensureMonetPersisted()
  }
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
    isResumed = false
  }
  DisposableEffect(context) {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        when (intent?.action) {
          Intent.ACTION_SCREEN_OFF -> {
            screenOffAtMs = SystemClock.elapsedRealtime()
          }
          Intent.ACTION_SCREEN_ON -> {
            if (screenOffAtMs > 0L && SystemClock.elapsedRealtime() - screenOffAtMs >= 60_000L) {
              pendingEarthRegen = true
            }
            screenOffAtMs = 0L
          }
          Intent.ACTION_USER_PRESENT -> {
            if (screenOffAtMs > 0L && SystemClock.elapsedRealtime() - screenOffAtMs >= 60_000L) {
              pendingEarthRegen = true
            }
            screenOffAtMs = 0L
            triggerHomeWake()
          }
          else -> viewModel.ensureMonetPersisted()
        }
      }
    }
    @Suppress("DEPRECATION")
    val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED).apply {
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_USER_PRESENT)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    onDispose {
      runCatching { context.unregisterReceiver(receiver) }
    }
  }
  DisposableEffect(context) {
    val packageReceiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        val pkg = intent?.data?.schemeSpecificPart
        viewModel.refreshLauncherData(invalidatedPackage = pkg)
      }
    }
    val packageFilter = IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addAction(Intent.ACTION_PACKAGE_CHANGED)
      addAction(Intent.ACTION_PACKAGE_REPLACED)
      addDataScheme("package")
    }
    ContextCompat.registerReceiver(context, packageReceiver, packageFilter, ContextCompat.RECEIVER_EXPORTED)
    onDispose {
      runCatching { context.unregisterReceiver(packageReceiver) }
    }
  }
  LaunchedEffect(context, viewModel) {
    viewModel.feedbackEvents.collect { event ->
      event.clipboardText?.let { value ->
        runCatching {
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
          cm?.setPrimaryClip(ClipData.newPlainText("E.D.I.T.H", value))
        }
      }
      Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
    }
  }

  val consoleOpen = state.isSpotlightSearchOpen || state.isAppBrowseOpen
  val settingsOpen = state.settingsStage != SettingsStage.CLOSED
  val searchOpen = state.isSpotlightSearchOpen
  var isHudKeyboardVisible by remember { mutableStateOf(false) }
  var useSystemIme by remember { mutableStateOf(false) }
  LaunchedEffect(consoleOpen) {
    if (!consoleOpen) {
      isHudKeyboardVisible = false
      useSystemIme = false
    }
  }
  val dismissConsole: () -> Unit = {
    lastScreenActivityMs = System.currentTimeMillis()
    isHudKeyboardVisible = false
    useSystemIme = false
    if (state.isSpotlightSearchOpen) viewModel.closeSpotlightSearch() else viewModel.closeAppBrowse()
  }

  LaunchedEffect(clockRedrawSignal, isStartupComplete, consoleOpen, state.isAmbientCentered) {
    if (clockRedrawSignal > lastHapticRedrawSignal) {
      if (consoleOpen || state.isAmbientCentered) {
        lastHapticRedrawSignal = clockRedrawSignal
      } else if (isStartupComplete) {
        delay(760L)
        lastHapticRedrawSignal = clockRedrawSignal
        if (isResumed) {
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
      }
    }
  }

  // Sync search and idle-ambient center state to 3D globe after top HUD finishes dismissing
  val globeSearchActive = consoleOpen && !isHudMounted
  LaunchedEffect(consoleOpen, isStartupComplete) {
    if (consoleOpen && !isStartupComplete) {
      globeController.finishStartupAnimation()
    }
  }
  LaunchedEffect(isStartupComplete) {
    if (!isStartupComplete) {
      // Fallback watchdog matching the 2.10s Earth startup animation plus WebView headroom.
      delay(2_600L)
      globeController.markStartupComplete()
    }
  }
  LaunchedEffect(globeSearchActive) {
    globeController.setSearchActive(globeSearchActive)
  }
  val globeAmbientCentered = state.isAmbientCentered && !isHudMounted
  LaunchedEffect(globeAmbientCentered) {
    globeController.setAmbientCentered(globeAmbientCentered)
  }
  LaunchedEffect(isGlobeAtHorizon, consoleOpen, state.isAmbientCentered) {
    if (!isGlobeAtHorizon && !consoleOpen && !state.isAmbientCentered) {
      // Deterministic 700ms Earth poseTransition plus headroom if WebView throttles.
      delay(1_000L)
      globeController.markHorizonSettled()
    }
  }
  LaunchedEffect(state.isSpotlightSearchOpen, state.isAppBrowseOpen, state.searchQuery, state.browseGroup) {
    if (state.isSpotlightSearchOpen || state.isAppBrowseOpen) {
      lastScreenActivityMs = System.currentTimeMillis()
    }
  }
  LaunchedEffect(
    isResumed,
    consoleOpen,
    settingsOpen,
    searchOpen,
    state.searchQuery,
    state.browseGroup,
    state.isAmbientCentered,
    isCardActionArmed,
    lastScreenActivityMs,
  ) {
    if (!isResumed) return@LaunchedEffect
    if (settingsOpen) return@LaunchedEffect
    if (consoleOpen) {
      if (isCardActionArmed) return@LaunchedEffect
      val timeoutMs = if (searchOpen && state.searchQuery.isEmpty()) 4_000L else 10_000L
      val remainingMs = (timeoutMs - (System.currentTimeMillis() - lastScreenActivityMs)).coerceAtLeast(0L)
      delay(remainingMs)
      keyboardController?.hide()
      focusManager.clearFocus()
      viewModel.autoDeactivateConsole()
    } else if (!state.isAmbientCentered) {
      val remainingMs = (12_000L - (System.currentTimeMillis() - lastScreenActivityMs)).coerceAtLeast(0L)
      delay(remainingMs)
      viewModel.enterAmbientCenter()
    }
  }

  // Back climbs one level: detail -> branch -> root settings -> home, or search/browse unwind.
  BackHandler(enabled = consoleOpen || settingsOpen || isSettingsMounted) {
    lastScreenActivityMs = System.currentTimeMillis()
    if (consoleOpen && isHudKeyboardVisible) {
      isHudKeyboardVisible = false
    } else {
      viewModel.handleBackPress()
    }
  }

  EDITHTheme(
    crimsonMonetActive = state.monetUiState.isEnabledPersisted,
    launcherSettings = state.launcherSettings,
  ) {
  val activeStyle = LocalLauncherStyle.current
  LaunchedEffect(activeStyle.accentPreset, activeStyle.hudIntensity) {
    val gp = activeStyle.accentPreset.toGlobePalette()
    val gain = when (activeStyle.hudIntensity) {
      HudIntensityPreset.STEALTH -> 0.72f
      HudIntensityPreset.TACTICAL -> 1.00f
      HudIntensityPreset.OVERDRIVE -> 1.28f
    }
    globeController.setAccentTheme(
      accentHex = gp.accentHex,
      waveR = gp.waveR * gain,
      waveG = gp.waveG * gain,
      waveB = gp.waveB * gain,
      graticuleHex = gp.graticuleHex,
    )
  }
  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val anyDown = event.changes.any { it.changedToDownIgnoreConsumed() }
            val anyPressed = event.changes.any { it.pressed }
            if (anyPressed) {
              if (anyDown && isAmbientCenteredState.value) {
                lastAmbientWakeMs = System.currentTimeMillis()
              }
              viewModel.wakeFromAmbient()
              val now = System.currentTimeMillis()
              if (anyDown || now - lastScreenActivityMs >= 250L) {
                lastScreenActivityMs = now
              }
            }
          }
        }
      }
      .pointerInput(consoleOpen, settingsOpen, isSettingsMounted) {
        if (!consoleOpen && !settingsOpen && !isSettingsMounted) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downTimeMs = System.currentTimeMillis()
            val startX = down.position.x
            val startY = down.position.y
            val screenWidth = size.width.toFloat().coerceAtLeast(1f)
            val screenHeight = size.height.toFloat().coerceAtLeast(1f)
            val normStartX = startX / screenWidth
            val normStartY = startY / screenHeight

            val wasAmbientCentered = isAmbientCenteredState.value ||
              (downTimeMs - lastAmbientWakeMs < 350L)
            val onHorizonDome = normStartY >= 0.58f
            val onCenteredSphere = wasAmbientCentered &&
              kotlin.math.hypot(normStartX - 0.5f, (startY - screenHeight * 0.52f) / screenWidth) <= 0.26f
            val startedOnGlobe = onHorizonDome || onCenteredSphere

            val edgeMarginPx = 24.dp.toPx()
            val isSystemEdge = startX < edgeMarginPx ||
              startX > (screenWidth - edgeMarginPx) ||
              startY < edgeMarginPx

            val touchSlopPx = 6.dp.toPx()
            val lockSlopPx = 10.dp.toPx()
            val dragTriggerPx = 48.dp.toPx()
            val flickTriggerPx = 36.dp.toPx()

            var totalDx = 0f
            var totalDy = 0f
            var isDrag = false
            var isPinch = false
            var settingsTriggered = false
            var prevSpan = 0f
            // 0 = UNDECIDED, 1 = GLOBE_ROTATE, 2 = HUD_SWIPE
            var gestureMode = if (startedOnGlobe || isSystemEdge) 1 else 0

            while (true) {
              val elapsedHoldMs = System.currentTimeMillis() - downTimeMs
              val remainingHoldMs = (500L - elapsedHoldMs).coerceAtLeast(1L)
              val event = if (startedOnGlobe && !isDrag && !isPinch && !settingsTriggered) {
                withTimeoutOrNull(remainingHoldMs) { awaitPointerEvent() }
              } else {
                awaitPointerEvent()
              }
              if (event == null) {
                settingsTriggered = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.openSettingsRoot()
                continue
              }
              val activeChanges = event.changes.filter { it.pressed }
              if (activeChanges.isEmpty()) {
                if (!isDrag && !isPinch && !settingsTriggered) {
                  event.changes.forEach { it.consume() }
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  val normX = normStartX.coerceIn(0f, 1f)
                  val normY = normStartY.coerceIn(0f, 1f)
                  globeController.tap(normX, normY)
                }
                break
              }

              // Multi-touch two-finger pinch-to-zoom
              if (activeChanges.size >= 2) {
                isPinch = true
                gestureMode = 1
                val p1 = activeChanges[0].position
                val p2 = activeChanges[1].position
                val span = kotlin.math.hypot(p1.x - p2.x, p1.y - p2.y)
                if (prevSpan > 0f) {
                  val factor = span / prevSpan
                  globeController.zoom(factor)
                }
                prevSpan = span
                activeChanges.forEach { it.consume() }
                continue
              }

              val change = activeChanges.firstOrNull { it.id == down.id } ?: activeChanges.first()
              val dx = change.position.x - change.previousPosition.x
              val dy = change.position.y - change.previousPosition.y
              totalDx += dx
              totalDy += dy

              val dragDistance = kotlin.math.hypot(totalDx, totalDy)
              if (dragDistance > touchSlopPx) {
                isDrag = true
              }

              if (!isDrag) continue

              // Lock gesture mode once travel reaches lockSlopPx in the upper HUD void
              if (gestureMode == 0 && dragDistance >= lockSlopPx) {
                val absDx = kotlin.math.abs(totalDx)
                val absDy = kotlin.math.abs(totalDy)
                gestureMode = if (absDy > absDx * 1.25f) 2 else 1
              }

              if (gestureMode == 1) {
                change.consume()
                globeController.rotate(dx, dy)
              } else if (gestureMode == 2) {
                change.consume()
                val elapsedMs = System.currentTimeMillis() - downTimeMs
                val triggerPx = if (elapsedMs <= 180L) flickTriggerPx else dragTriggerPx
                if (totalDy >= triggerPx) {
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  viewModel.openSpotlightSearch()
                  break
                } else if (-totalDy >= triggerPx) {
                  haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  viewModel.openAppBrowse()
                  break
                }
              }
            }
          }
        }
      }
  ) {
    val accentColor = activeStyle.accent

    Box(
      modifier = Modifier
        .fillMaxSize()
        .zIndex(0.5f)
        .drawWithCache {
          onDrawBehind {
            val scanSpacing = 3.dp.toPx()
            val scanAlpha = 0.03f
            val scanPaint = Paint().apply {
              this.color = Color.White.copy(alpha = scanAlpha)
            }
            val gridPaint = Paint().apply {
              this.color = accentColor.copy(alpha = 0.025f)
            }
            val gridStrokeWidth = 0.5f.dp.toPx()
            gridPaint.strokeWidth = gridStrokeWidth
            gridPaint.style = PaintingStyle.Stroke

            for (y in 0 until size.height.toInt() step scanSpacing.toInt()) {
              drawRect(
                brush = Brush.verticalGradient(
                  listOf(Color.White.copy(alpha = scanAlpha * 1.5f), Color.Transparent),
                ),
                size = Size(size.width, 1.dp.toPx()),
                topLeft = Offset(0f, y.toFloat()),
              )
            }
            val gridSpacing = 60.dp.toPx()
            for (x in gridSpacing.toInt() until size.width.toInt() step gridSpacing.toInt()) {
              drawLine(
                color = gridPaint.color,
                start = Offset(x.toFloat(), 0f),
                end = Offset(x.toFloat(), size.height),
                strokeWidth = gridStrokeWidth,
              )
            }
            for (y in gridSpacing.toInt() until size.height.toInt() step gridSpacing.toInt()) {
              drawLine(
                color = gridPaint.color,
                start = Offset(0f, y.toFloat()),
                end = Offset(size.width, y.toFloat()),
                strokeWidth = gridStrokeWidth,
              )
            }
          }
        },
    )

    Box(
      modifier = Modifier
        .fillMaxSize()
        .zIndex(0.6f)
        .drawWithCache {
          val cornerSize = 48.dp.toPx()
          val bracketWidth = 1.5f.dp.toPx()
          onDrawBehind {
            val accentAlpha = 0.18f
            val tlColor = accentColor.copy(alpha = accentAlpha)
            val trColor = accentColor.copy(alpha = accentAlpha * 0.85f)
            val blColor = accentColor.copy(alpha = accentAlpha * 0.72f)
            val brColor = accentColor.copy(alpha = accentAlpha * 0.60f)

            // Top-left bracket
            drawRect(tlColor, Offset.Zero, Size(bracketWidth, cornerSize))
            drawRect(tlColor, Offset.Zero, Size(cornerSize, bracketWidth))

            val w = this@onDrawBehind.size.width
            val h = this@onDrawBehind.size.height
            // Top-right bracket
            drawRect(trColor, Offset(w - bracketWidth, 0f), Size(bracketWidth, cornerSize))
            drawRect(trColor, Offset(w - cornerSize, 0f), Size(cornerSize, bracketWidth))

            // Bottom-left bracket
            drawRect(blColor, Offset.Zero, Size(bracketWidth, cornerSize))
            drawRect(blColor, Offset(0f, h - bracketWidth), Size(cornerSize, bracketWidth))

            // Bottom-right bracket
            drawRect(brColor, Offset(w - bracketWidth, h - cornerSize), Size(bracketWidth, cornerSize))
            drawRect(brColor, Offset(w - cornerSize, h - bracketWidth), Size(cornerSize, bracketWidth))
          }
        },
    )

    val browseGroupCount = remember(state.apps, state.browseGroup) {
      val name = state.browseGroup ?: return@remember 0
      groupApps(state.apps).firstOrNull { it.name == name }?.apps?.size ?: 0
    }
    val backdropProgress = remember { Animatable(0f) }
    LaunchedEffect(globeSearchActive) {
      if (globeSearchActive) {
        val showMs = ((1f - backdropProgress.value).coerceIn(0f, 1f) * 520f).roundToInt().coerceAtLeast(1)
        backdropProgress.animateTo(1f, tween(showMs, easing = FastOutSlowInEasing))
      } else {
        val hideMs = (backdropProgress.value.coerceIn(0f, 1f) * 650f).roundToInt().coerceAtLeast(1)
        backdropProgress.animateTo(0f, tween(hideMs, easing = FastOutSlowInEasing))
      }
    }
    val consoleBlurSteps = remember {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        List(12) { idx ->
          val radius = (idx + 1).toFloat()
          android.graphics.RenderEffect.createBlurEffect(
            radius,
            radius,
            android.graphics.Shader.TileMode.CLAMP,
          ).asComposeRenderEffect()
        }
      } else {
        emptyList()
      }
    }

    // Root settings branch lifts above the globe (zIndex 2f) ONLY after HudSettingsGateChecker
    // confirms all Stage 2/3 overlay lasers have retraced back down behind the globe.
    HudSettingsRootLayer(
      stage = state.settingsStage,
      screenWidth = maxWidth,
      screenHeight = maxHeight,
      onSelectBranch = viewModel::selectSettingsBranch,
      choreographer = settingsChoreographer,
      modifier = Modifier.zIndex(if (settingsChoreographer.isRootAboveGlobe) 2f else 0f),
    )

    // Wireframe Earth backdrop with synchronized rack-focus dolly zoom and stepped blur
    Box(
      Modifier
        .fillMaxSize()
        .zIndex(1f)
        .graphicsLayer {
          val p = backdropProgress.value
          val zoom = 1f + 0.08f * p
          scaleX = zoom
          scaleY = zoom
          val blurStep = (p * consoleBlurSteps.size).roundToInt()
          renderEffect = if (blurStep > 0 && consoleBlurSteps.isNotEmpty()) {
            consoleBlurSteps[(blurStep - 1).coerceIn(0, consoleBlurSteps.lastIndex)]
          } else {
            null
          }
        }
    ) {
      InteractiveWireframeGlobe(
        controller = globeController,
        modifier = Modifier.fillMaxSize(),
      )
    }

    // Crime scene intro camera lens depth overlay
    CrimeSceneFocusOverlay(
      isOpen = consoleOpen,
      modifier = Modifier.fillMaxSize().zIndex(1.5f),
    )

    // The clock crowns the globe horizon apex after startup assembly completes and the globe rests at horizon.
    val topHudHidden =
      consoleOpen || settingsOpen || isSettingsMounted || state.isAmbientCentered || !isGlobeAtHorizon || !isStartupComplete
    val globeApexY = maxHeight * 0.64f
    val clockTop = (globeApexY - 82.dp).coerceAtLeast(66.dp)
    HudClock(
      isOpen = topHudHidden,
      redrawSignal = clockRedrawSignal,
      use24Hour = state.launcherSettings.use24Hour,
      screenWidth = maxWidth,
      screenHeight = maxHeight,
      onMountedChange = { isClockMounted = it },
      modifier = Modifier
        .zIndex(2f)
        .align(Alignment.TopStart)
        .padding(top = clockTop),
    )

    val otaTop = (clockTop - 52.dp).coerceAtLeast(24.dp)
    HudOtaPill(
      otaState = state.otaState,
      onClick = viewModel::onOtaPillClick,
      onDismiss = viewModel::dismissOtaPill,
      hidden = topHudHidden,
      onMountedChange = { isOtaMounted = it },
      modifier = Modifier
        .zIndex(2f)
        .align(Alignment.TopStart)
        .padding(start = 18.dp, end = 18.dp, top = otaTop),
    )


    HudSettingsOverlayLayer(
      stage = state.settingsStage,
      selectedSection = state.selectedSettingsSection,
      settings = state.launcherSettings,
      telemetry = state.telemetry,
      liveWeather = state.liveWeather,
      isDefaultLauncher = state.isDefaultLauncher,
      hasNotificationAccess = state.hasNotificationAccess,
      otaState = state.otaState,
      screenWidth = maxWidth,
      screenHeight = maxHeight,
      onSelectSection = viewModel::selectLauncherSetting,
      onBackStep = { viewModel.handleBackPress() },
      onAccentChange = viewModel::updateLauncherAccent,
      onIntensityChange = viewModel::updateHudIntensity,
      onFontChange = viewModel::updateLauncherFont,
      onFontScopeChange = viewModel::updateLauncherFontScope,
      onFontScaleChange = viewModel::updateLauncherFontScale,
      onUse24HourChange = viewModel::setUse24Hour,
      onRefreshRateSelect = { hz -> viewModel.setRefreshRatePreset(hz) },
      onRefreshWeather = { viewModel.refreshWeather(forceRefresh = true, announceResult = true) },
      onOpenAlarms = viewModel::openClockOrAlarms,
      onOpenHomeSettings = viewModel::openHomeSettings,
      onOpenUsageSettings = viewModel::openUsageAccessSettings,
      onOpenNotificationSettings = viewModel::openNotificationAccessSettings,
      onCheckOta = { viewModel.checkForUpdates(force = true) },
      choreographer = settingsChoreographer,
      modifier = Modifier.zIndex(3f),
    )

    // Tap outside the console dismisses it when open
    if (consoleOpen) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .zIndex(4f)
          .pointerInput(Unit) {
            detectTapGestures { dismissConsole() }
          }
      )
    }

    HudSearchBar(
      isOpen = consoleOpen,
      searchQuery = state.searchQuery,
      onSearchQueryChange = viewModel::onSearchQueryChange,
      onDismiss = dismissConsole,
      apps = state.apps,
      onLaunchApp = viewModel::launchApp,
      autoFocus = searchOpen,
      idleDismiss = false,
      scopeLabel = state.browseGroup,
      scopeCount = browseGroupCount,
      commandHint = state.commandHint,
      onSubmitQuery = viewModel::submitConsoleQuery,
      onExitGroup = viewModel::closeAppGroup,
      useSystemIme = useSystemIme,
      hudKeyboardOpen = isHudKeyboardVisible,
      onHudKeyboardVisibilityChange = { isHudKeyboardVisible = it },
      modifier = Modifier
        .zIndex(4f)
        .align(Alignment.TopStart),
    )

    // Results as a cascading deck; the leader is sharp, the rest defocus. Browse mode opens on
    // category plates and drills into one category's app deck; the strip's drag walks the window
    // past the five dealt cards, and a pull past the head of a category climbs back out.
    // Once the IME retracts the leader card parks with its centre at half screen height.
    val density = LocalDensity.current
    val cardHeight = (maxWidth - 36.dp) * 124f / 312f
    val deckResting = maxHeight * 0.5f - cardHeight / 2f
    val keyboardUp = consoleOpen && ((useSystemIme && WindowInsets.isImeVisible) || (!useSystemIme && isHudKeyboardVisible))
    val deckShift = animateFloatAsState(
      targetValue = if (keyboardUp) 0f else 1f,
      animationSpec = tween(260),
      label = "deckShift",
    )

    val matchingApps = remember(state.searchQuery, state.apps) {
      filterMatchingApps(state.searchQuery, state.apps)
    }

    HudCardDeck(
      isOpen = consoleOpen,
      searchQuery = state.searchQuery,
      onDismiss = dismissConsole,
      apps = state.apps,
      onLaunchApp = viewModel::launchApp,
      browse = state.isAppBrowseOpen,
      browseGroup = state.browseGroup,
      onEnterGroup = viewModel::openAppGroup,
      onExitGroup = viewModel::closeAppGroup,
      footprintOf = viewModel::footprintOf,
      onOpenAppInfo = viewModel::openAppInfo,
      onUninstallApp = viewModel::requestUninstall,
      notificationCounts = state.notificationCounts,
      onActionArmedChange = { armed ->
        isCardActionArmed = armed
        if (armed) {
          isHudKeyboardVisible = false
          keyboardController?.hide()
          focusManager.clearFocus()
        }
        lastScreenActivityMs = System.currentTimeMillis()
      },
      modifier = Modifier
        .zIndex(4f)
        .align(Alignment.TopStart)
        .padding(start = 18.dp, end = 18.dp, top = 236.dp, bottom = 28.dp)
        .graphicsLayer {
          translationY = with(density) { (deckResting - 236.dp).toPx() } * deckShift.value
        },
    )

    HudKeyboard(
      visible = consoleOpen && !useSystemIme && isHudKeyboardVisible,
      query = state.searchQuery,
      matchCount = matchingApps.size,
      onAppend = { token ->
        lastScreenActivityMs = System.currentTimeMillis()
        viewModel.onSearchQueryChange(HudKeyboardLogic.appendToken(state.searchQuery, token))
      },
      onBackspace = {
        lastScreenActivityMs = System.currentTimeMillis()
        viewModel.onSearchQueryChange(HudKeyboardLogic.backspace(state.searchQuery))
      },
      onClearQuery = {
        lastScreenActivityMs = System.currentTimeMillis()
        viewModel.onSearchQueryChange("")
      },
      onCommandChip = { chip ->
        lastScreenActivityMs = System.currentTimeMillis()
        viewModel.onSearchQueryChange(HudKeyboardLogic.applyCommandChip(state.searchQuery, chip))
      },
      onSubmit = {
        lastScreenActivityMs = System.currentTimeMillis()
        isHudKeyboardVisible = false
        if (!viewModel.submitConsoleQuery()) {
          matchingApps.firstOrNull()?.let { top ->
            viewModel.launchApp(top)
            dismissConsole()
          }
        }
      },
      onHideKeyboard = {
        lastScreenActivityMs = System.currentTimeMillis()
        isHudKeyboardVisible = false
      },
      onSwitchToSystemIme = {
        lastScreenActivityMs = System.currentTimeMillis()
        isHudKeyboardVisible = false
        useSystemIme = true
      },
      modifier = Modifier
        .zIndex(4.4f)
        .align(Alignment.BottomCenter),
    )

  }
  }
}
