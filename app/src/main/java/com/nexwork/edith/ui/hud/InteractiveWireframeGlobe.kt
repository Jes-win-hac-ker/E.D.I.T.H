package com.nexwork.edith.ui.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.LocationManager
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Controller to drive live touch rotation, precision raycast waves, and location updates into the Three.js globe.
 */
class GlobeController {
  private var webViewRef: WebView? = null

  internal fun attach(webView: WebView) {
    webViewRef = webView
  }

  internal fun detach() {
    webViewRef = null
  }

  fun rotate(dx: Float, dy: Float) {
    webViewRef?.evaluateJavascript("if(window.rotateGlobe) window.rotateGlobe($dx, $dy);", null)
  }

  fun tap(x: Float, y: Float) {
    webViewRef?.evaluateJavascript("if(window.triggerWaveAt) window.triggerWaveAt($x, $y);", null)
  }

  fun setLocation(lat: Double, lon: Double) {
    webViewRef?.evaluateJavascript("if(window.setUserLocation) window.setUserLocation($lat, $lon);", null)
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
      webViewClient = object : WebViewClient() {}
      loadUrl("file:///android_asset/globe/index.html")
    }
  }

  // Detect real device location and pass it to the 3D globe beacon
  LaunchedEffect(webView) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val lastKnown = try {
      locationManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        ?: locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    } catch (_: SecurityException) { null }

    val lat = lastKnown?.latitude ?: 10.516
    val lon = lastKnown?.longitude ?: 76.2157
    controller?.setLocation(lat, lon)
  }

  DisposableEffect(webView, controller) {
    controller?.attach(webView)
    webView.onResume()
    onDispose {
      controller?.detach()
      webView.onPause()
    }
  }

  AndroidView(
    factory = { webView },
    modifier = modifier.fillMaxSize(),
  )
}
