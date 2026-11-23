package com.nexwork.edith

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nexwork.edith.theme.EDITHTheme

class MainActivity : ComponentActivity() {
  private var homeResetSignal by mutableIntStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Root launcher back press handling: consume back gestures at the root
    // so the launcher activity never finishes, closes tasks, or re-creates.
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        // No-op at root: home launcher must stay active
      }
    })

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    window.setBackgroundDrawableResource(android.R.color.black)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
      }
    }

    // Hide status bar on home screen, but show it transparently while IME is open or animating
    // so Gboard's OPTOUT_EDGE_TO_EDGE window never paints a white strip over the camera cutout.
    hideStatusBar()
    var imeAnimating = false
    ViewCompat.setWindowInsetsAnimationCallback(
      window.decorView,
      object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
          if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
            imeAnimating = true
            showStatusBar()
          }
        }

        override fun onProgress(
          insets: WindowInsetsCompat,
          runningAnimations: MutableList<WindowInsetsAnimationCompat>,
        ): WindowInsetsCompat = insets

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
          if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
            imeAnimating = false
            val imeVisible = ViewCompat.getRootWindowInsets(window.decorView)
              ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!imeVisible) {
              hideStatusBar()
            }
          }
        }
      },
    )
    ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
      if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
        showStatusBar()
      } else if (!imeAnimating) {
        hideStatusBar()
      }
      ViewCompat.onApplyWindowInsets(view, insets)
    }

    setContent {
      EDITHTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background,
        ) {
          CompositionLocalProvider(LocalHomeResetSignal provides homeResetSignal) {
            MainNavigation(homeResetSignal = homeResetSignal)
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
      homeResetSignal += 1
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      val imeOpen = ViewCompat.getRootWindowInsets(window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
      if (imeOpen) showStatusBar() else hideStatusBar()
    }
  }

  private fun showStatusBar() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = true
      show(WindowInsetsCompat.Type.statusBars())
    }
  }

  private fun hideStatusBar() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = false
      hide(WindowInsetsCompat.Type.statusBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }
}