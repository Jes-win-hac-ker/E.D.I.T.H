package com.nexwork.edith.ui.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.LocationManager
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controller to drive live touch rotation, precision raycast waves, and location updates into the Three.js globe.
 * High-rate drag/zoom deltas are coalesced at most once per VSync to prevent WebView JS IPC flooding.
 */
class GlobeController {
  private var webViewRef: WebView? = null
  private var pendingDx = 0f
  private var pendingDy = 0f
  private var pendingZoom = 1f
  private var flushScheduled = false
  private var lastSearchActive = false
  private var lastAmbientCentered = false
  private var lastSettingsBackdropActive = false
  private var lastAccentHex: Int = 0x38BDF8
  private var lastWaveR: Float = 0.22f
  private var lastWaveG: Float = 0.98f
  private var lastWaveB: Float = 1.58f
  private var lastGraticuleHex: Int = 0x2C5470
  private val _isGlobeAtHorizon = MutableStateFlow(true)
  val isGlobeAtHorizon: StateFlow<Boolean> = _isGlobeAtHorizon.asStateFlow()
  private val _isStartupComplete = MutableStateFlow(false)
  val isStartupComplete: StateFlow<Boolean> = _isStartupComplete.asStateFlow()

  private val flushCallback = Choreographer.FrameCallback {
    flushScheduled = false
    flushPending()
  }

  internal fun attach(webView: WebView) {
    webViewRef = webView
  }

  internal fun detach() {
    if (flushScheduled) {
      Choreographer.getInstance().removeFrameCallback(flushCallback)
      flushScheduled = false
    }
    pendingDx = 0f
    pendingDy = 0f
    pendingZoom = 1f
    webViewRef = null
  }

  private fun scheduleFlush() {
    if (!flushScheduled && webViewRef != null) {
      flushScheduled = true
      Choreographer.getInstance().postFrameCallback(flushCallback)
    }
  }

  private fun flushPending() {
    val webView = webViewRef ?: return
    val dx = pendingDx
    val dy = pendingDy
    val zoomFactor = pendingZoom
    pendingDx = 0f
    pendingDy = 0f
    pendingZoom = 1f

    val hasRot = dx != 0f || dy != 0f
    val hasZoom = zoomFactor != 1f
    if (!hasRot && !hasZoom) return

    val script = buildString {
      if (hasRot) append("if(window.rotateGlobe) window.rotateGlobe($dx, $dy);")
      if (hasZoom) append("if(window.applyZoomDelta) window.applyZoomDelta($zoomFactor);")
    }
    webView.evaluateJavascript(script, null)
  }

  fun rotate(dx: Float, dy: Float) {
    pendingDx += dx
    pendingDy += dy
    scheduleFlush()
  }

  fun tap(x: Float, y: Float) {
    flushPending()
    webViewRef?.evaluateJavascript("if(window.triggerWaveAt) window.triggerWaveAt($x, $y);", null)
  }

  fun playStartupAnimation() {
    _isStartupComplete.value = false
    flushPending()
    webViewRef?.evaluateJavascript("if(window.triggerStartupAnimation) window.triggerStartupAnimation();", null)
  }

  fun markStartupComplete() {
    _isStartupComplete.value = true
  }

  fun finishStartupAnimation() {
    _isStartupComplete.value = true
    flushPending()
    webViewRef?.evaluateJavascript("if(window.finishStartupAnimation) window.finishStartupAnimation();", null)
  }

  fun setLocation(lat: Double, lon: Double) {
    webViewRef?.evaluateJavascript("if(window.setUserLocation) window.setUserLocation($lat, $lon);", null)
  }

  fun setSearchActive(active: Boolean) {
    val wasSearch = lastSearchActive
    lastSearchActive = active
    if (active || wasSearch) {
      _isGlobeAtHorizon.value = false
    }
    flushPending()
    webViewRef?.evaluateJavascript("if(window.setSearchActive) window.setSearchActive($active);", null)
  }

  fun setAmbientCentered(centered: Boolean) {
    val wasCentered = lastAmbientCentered
    lastAmbientCentered = centered
    if (centered || wasCentered) {
      _isGlobeAtHorizon.value = false
    }
    flushPending()
    webViewRef?.evaluateJavascript("if(window.setAmbientCentered) window.setAmbientCentered($centered);", null)
  }

  fun setSettingsBackdropActive(active: Boolean) {
    if (lastSettingsBackdropActive == active) return
    lastSettingsBackdropActive = active
    flushPending()
    webViewRef?.evaluateJavascript(
      "if(window.setSettingsBackdropActive) window.setSettingsBackdropActive($active);",
      null,
    )
  }

  fun setAccentTheme(
    accentHex: Int,
    waveR: Float,
    waveG: Float,
    waveB: Float,
    graticuleHex: Int,
  ) {
    lastAccentHex = accentHex
    lastWaveR = waveR
    lastWaveG = waveG
    lastWaveB = waveB
    lastGraticuleHex = graticuleHex
    flushPending()
    webViewRef?.evaluateJavascript(
      "if(window.setHudAccentColor) window.setHudAccentColor($accentHex, $waveR, $waveG, $waveB, $graticuleHex);",
      null,
    )
  }

  fun markHorizonSettled() {
    _isGlobeAtHorizon.value = true
  }

  internal fun syncState(webView: WebView?) {
    webView?.evaluateJavascript(
      "if(window.setAmbientCentered) window.setAmbientCentered($lastAmbientCentered);" +
        "if(window.setSearchActive) window.setSearchActive($lastSearchActive);" +
        "if(window.setSettingsBackdropActive) window.setSettingsBackdropActive($lastSettingsBackdropActive);" +
        "if(window.setHudAccentColor) window.setHudAccentColor($lastAccentHex, $lastWaveR, $lastWaveG, $lastWaveB, $lastGraticuleHex);",
      null,
    )
  }

  fun zoom(factor: Float) {
    if (factor > 0f) {
      pendingZoom *= factor
      scheduleFlush()
    }
  }

  fun resetZoom() {
    pendingZoom = 1f
    webViewRef?.evaluateJavascript("if(window.resetZoom) window.resetZoom();", null)
  }
}

/**
 * Renders the 3D WebGL interactive wireframe Earth ported directly from the user's
 * Firefox dashboard customization (Three.js, glowing Fibonacci landmass points,
 * country nodes, 3D SLERP great-circle arcs, and dynamic radar wave sweeps).
 */
@Keep
private class GlobeJsBridge(
  private val notifyHorizonSettled: () -> Unit,
  private val notifyStartupComplete: () -> Unit,
) {
  @JavascriptInterface
  fun onHorizonSettled() {
    notifyHorizonSettled()
  }

  @JavascriptInterface
  fun onStartupComplete() {
    notifyStartupComplete()
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InteractiveWireframeGlobe(
  modifier: Modifier = Modifier,
  controller: GlobeController? = null,
) {
  val context = LocalContext.current
  val currentController = rememberUpdatedState(controller)
  val locationCoords = remember(context) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val lastKnown = try {
      locationManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        ?: locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    } catch (_: SecurityException) {
      null
    }
    (lastKnown?.latitude ?: 10.516) to (lastKnown?.longitude ?: 76.2157)
  }

  val webView = remember {
    WebView(context).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
      setBackgroundColor(Color.TRANSPARENT)
      setLayerType(View.LAYER_TYPE_HARDWARE, null)
      isVerticalScrollBarEnabled = false
      isHorizontalScrollBarEnabled = false
      isClickable = false
      isLongClickable = false
      isFocusable = false
      isFocusableInTouchMode = false
      setOnTouchListener { _, _ -> true }
      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        allowFileAccessFromFileURLs = true
        allowUniversalAccessFromFileURLs = true
        cacheMode = WebSettings.LOAD_DEFAULT
      }
      addJavascriptInterface(
        GlobeJsBridge(
          notifyHorizonSettled = { currentController.value?.markHorizonSettled() },
          notifyStartupComplete = { currentController.value?.markStartupComplete() },
        ),
        "EdithGlobeBridge",
      )
      webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
          val (lat, lon) = locationCoords
          view?.evaluateJavascript(
            "if(window.setUserLocation) window.setUserLocation($lat, $lon);",
            null,
          )
          currentController.value?.syncState(view)
        }
      }
      loadUrl("file:///android_asset/globe/index.html")
    }
  }

  DisposableEffect(webView) {
    webView.onResume()
    onDispose {
      webView.onPause()
      webView.destroy()
    }
  }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    webView.onResume()
  }

  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
    webView.onPause()
  }

  DisposableEffect(webView, controller) {
    controller?.attach(webView)
    onDispose {
      controller?.detach()
    }
  }

  AndroidView(
    factory = { webView },
    modifier = modifier.fillMaxSize(),
  )
}
