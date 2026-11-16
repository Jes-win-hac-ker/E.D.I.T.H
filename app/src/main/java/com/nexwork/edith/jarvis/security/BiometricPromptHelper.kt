package com.nexwork.edith.jarvis.security

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * Triggers Android's native platform [BiometricPrompt] (API 29+) with biometric or device
 * credential fallback so Tier 3 confirmation works directly from any `Activity` context without
 * adding external AndroidX Fragment/Biometric dependencies.
 */
object BiometricPromptHelper {

  fun authenticate(
    context: Context,
    title: String,
    subtitle: String,
    description: String,
    onResult: (approved: Boolean, detail: String?) -> Unit,
  ) {
    runCatching {
      val executor = ContextCompat.getMainExecutor(context)
      val signal = CancellationSignal()
      val builder = BiometricPrompt.Builder(context)
        .setTitle(title.ifBlank { "E.D.I.T.H Tier 3 Gate" })
        .setSubtitle(subtitle)
        .setDescription(description)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(
          BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
      } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
      }

      builder.build().authenticate(
        signal,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
            onResult(true, "Biometric/device credential verified")
          }

          override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            onResult(false, errString?.toString() ?: "Biometric authentication cancelled ($errorCode)")
          }

          override fun onAuthenticationFailed() = Unit
        },
      )
    }.onFailure { err ->
      onResult(false, err.message ?: "Biometric prompt unavailable")
    }
  }
}
