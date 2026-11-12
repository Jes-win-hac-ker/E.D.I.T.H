package com.nexwork.edith.ui.hud

import android.annotation.SuppressLint
import android.graphics.Color
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

/**
 * Controller to drive live touch rotation and tap impulses into the Three.js WebGL globe.
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

  fun tap() {
    webViewRef?.evaluateJavascript("if(window.onGlobeTap) window.onGlobeTap();", null)
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
        loadWithOverviewMode = true
        useWideViewPort = true
        cacheMode = WebSettings.LOAD_DEFAULT
      }
      webViewClient = object : WebViewClient() {}
      loadUrl("file:///android_asset/globe/index.html")
    }
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
