package com.nexwork.edith.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nexwork.edith.R

object CustomAppIcons {
  /**
   * Maps known package names to custom curated monochrome vector drawables.
   */
  @DrawableRes
  fun getCustomIconRes(packageName: String): Int? {
    val pkg = packageName.lowercase()
    return when {
      // WhatsApp
      pkg == "com.whatsapp" || pkg.startsWith("com.whatsapp.") -> R.drawable.ic_custom_whatsapp
      // Camera
      pkg.contains("camera") -> R.drawable.ic_custom_camera
      // Phone / Dialer
      pkg.contains("dialer") || pkg == "com.android.phone" -> R.drawable.ic_custom_phone
      // Google Pay / Wallet
      pkg.contains("paisa") || pkg.contains("wallet") -> R.drawable.ic_custom_gpay
      // Google Quick Search Box
      pkg.contains("googlequicksearchbox") -> R.drawable.ic_custom_google
      // Gemini / Assistant
      pkg.contains("bard") || pkg.contains("googleassistant") -> R.drawable.ic_custom_gemini
      // Qwen / Tongyi
      pkg.contains("qwen") || pkg.contains("tongyi") -> R.drawable.ic_custom_qwen
      // YouTube
      pkg.contains("youtube") -> R.drawable.ic_custom_youtube
      // Threads
      pkg == "com.instagram.barcelona" || pkg.contains("threads") -> R.drawable.ic_custom_threads
      // Instagram
      pkg == "com.instagram.android" || pkg == "com.instagram.lite" || pkg == "com.instagram" -> R.drawable.ic_custom_instagram
      // Chrome / Chromium
      pkg.contains("chrome") || pkg.contains("chromium") -> R.drawable.ic_custom_chrome
      // Settings
      pkg.contains("settings") -> R.drawable.ic_custom_settings
      // Play Store
      pkg == "com.android.vending" -> R.drawable.ic_custom_playstore
      // Messages / SMS
      pkg.contains("messaging") || pkg == "com.android.mms" -> R.drawable.ic_custom_messages
      // Gmail / Mail
      pkg == "com.google.android.gm" || pkg.contains("email") -> R.drawable.ic_custom_gmail
      // Telegram
      pkg.contains("telegram") -> R.drawable.ic_custom_telegram
      // Twitter / X / Grok
      pkg.contains("twitter") || pkg.contains("x.grok") -> R.drawable.ic_custom_twitter
      // Reddit
      pkg.contains("reddit") -> R.drawable.ic_custom_reddit
      // Spotify / Music
      pkg.contains("spotify") || pkg.contains("music.player") -> R.drawable.ic_custom_spotify
      // Discord
      pkg.contains("discord") -> R.drawable.ic_custom_discord
      // ChatGPT
      pkg.contains("openai") || pkg.contains("chatgpt") -> R.drawable.ic_custom_chatgpt
      // Claude
      pkg.contains("claude") || pkg.contains("anthropic") -> R.drawable.ic_custom_claude
      // GitHub
      pkg.contains("github") -> R.drawable.ic_custom_github
      // Files / File Manager
      pkg.contains("files") || pkg.contains("documentsui") || pkg.contains("zarchiver") -> R.drawable.ic_custom_files
      // Clock
      pkg.contains("clock") -> R.drawable.ic_custom_clock
      // Calculator
      pkg.contains("calculator") -> R.drawable.ic_custom_calculator
      // Maps
      pkg.contains("maps") -> R.drawable.ic_custom_maps
      // Photos / Gallery
      pkg.contains("photos") || pkg.contains("gallery") -> R.drawable.ic_custom_photos
      // Notes / Keep
      pkg.contains("keep") || pkg.contains("notes") -> R.drawable.ic_custom_notes
      // Terminal / Termux
      pkg.contains("termux") || pkg.contains("terminal") -> R.drawable.ic_custom_terminal
      // Contacts
      pkg.contains("contacts") -> R.drawable.ic_custom_contacts
      else -> null
    }
  }

  /**
   * Retrieves the official Android 13+ monochrome icon layer if supported by the app.
   */
  fun getMonochromeDrawable(drawable: Drawable?): Drawable? {
    if (drawable == null) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && drawable is AdaptiveIconDrawable) {
      return runCatching { drawable.monochrome }.getOrNull()
    }
    return null
  }
}

/**
 * Safely converts an Android Drawable to a Compose ImageBitmap, capping at 192px.
 */
fun Drawable.toImageBitmapSafe(): ImageBitmap? {
  return try {
    if (this is BitmapDrawable && bitmap != null) {
      return bitmap.asImageBitmap()
    }
    val w = intrinsicWidth.coerceAtLeast(1).coerceAtMost(192)
    val h = intrinsicHeight.coerceAtLeast(1).coerceAtMost(192)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    bmp.asImageBitmap()
  } catch (_: Exception) {
    null
  }
}
