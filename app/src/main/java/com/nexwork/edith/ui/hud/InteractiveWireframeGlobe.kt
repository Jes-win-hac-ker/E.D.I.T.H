package com.nexwork.edith.ui.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.LocationManager
import android.view.Choreographer
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

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

  fun setLocation(lat: Double, lon: Double) {
    webViewRef?.evaluateJavascript("if(window.setUserLocation) window.setUserLocation($lat, $lon);", null)
  }

  fun setSearchActive(active: Boolean) {
    lastSearchActive = active
    flushPending()
    webViewRef?.evaluateJavascript("if(window.setSearchActive) window.setSearchActive($active);", null)
  }

  fun setAmbientCentered(centered: Boolean) {
    lastAmbientCentered = centered
    flushPending()
    webViewRef?.evaluateJavascript("if(window.setAmbientCentered) window.setAmbientCentered($centered);", null)
  }

  internal fun syncState(webView: WebView?) {
    webView?.evaluateJavascript(
      "if(window.setAmbientCentered) window.setAmbientCentered($lastAmbientCentered);" +
        "if(window.setSearchActive) window.setSearchActive($lastSearchActive);",
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
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InteractiveWireframeGlobe(
  modifier: Modifier = Modifier,
  controller: GlobeController? = null,
) {
  val context = LocalContext.current
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
      setBackgroundColor(Color.BLACK)
      isVerticalScrollBarEnabled = false
      isHorizontalScrollBarEnabled = false
      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        allowFileAccessFromFileURLs = true
        allowUniversalAccessFromFileURLs = true
        cacheMode = WebSettings.LOAD_DEFAULT
      }
      webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
          val (lat, lon) = locationCoords
          view?.evaluateJavascript(
            "if(window.setUserLocation) window.setUserLocation($lat, $lon);",
            null,
          )
          controller?.syncState(view)
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
