@file:Suppress("DEPRECATION")

package com.nexwork.edith.jarvis.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Encrypted storage for the Google AI Studio API key backed by EncryptedSharedPreferences.
 *
 * Keystore access is deferred lazily so launcher first-frame rendering never stalls on disk or
 * hardware keystore IPC.
 */
class KeyVault(context: Context) {

  private val appContext = context.applicationContext
  private val lock = Any()

  @Volatile
  private var cachedPrefs: SharedPreferences? = null

  private val _hasApiKey = MutableStateFlow(false)
  val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

  private val _maskedKeyPreview = MutableStateFlow("")
  val maskedKeyPreview: StateFlow<String> = _maskedKeyPreview.asStateFlow()

  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError.asStateFlow()

  /** Warms encrypted preferences and refreshes published key presence state. */
  fun warmUp() {
    val key = getApiKey()
    publishState(key)
  }

  /** Returns the decrypted API key, or null when unset or unreadable. */
  fun getApiKey(): String? {
    val prefs = getOrCreatePrefs() ?: return null
    val raw = runCatching { prefs.getString(KEY_GEMINI_API_KEY, null) }.getOrNull()
    return raw?.trim()?.takeIf { it.isNotEmpty() }
  }

  /** Persists [rawKey] encrypted at rest, or clears the vault when blank. */
  fun saveApiKey(rawKey: String): Result<Unit> {
    val trimmed = rawKey.trim()
    if (trimmed.isEmpty()) {
      return clearApiKey()
    }
    return runCatching {
      val prefs = getOrCreatePrefs() ?: error("Encrypted vault unavailable")
      prefs.edit().putString(KEY_GEMINI_API_KEY, trimmed).apply()
      publishState(trimmed)
      _lastError.value = null
    }.onFailure { err ->
      _lastError.value = err.message ?: "Failed to save API key"
    }
  }

  /** Removes the stored API key from encrypted storage. */
  fun clearApiKey(): Result<Unit> {
    return runCatching {
      val prefs = getOrCreatePrefs() ?: error("Encrypted vault unavailable")
      prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
      publishState(null)
      _lastError.value = null
    }.onFailure { err ->
      _lastError.value = err.message ?: "Failed to clear API key"
    }
  }

  private fun publishState(key: String?) {
    val present = !key.isNullOrBlank()
    _hasApiKey.value = present
    _maskedKeyPreview.value = if (!key.isNullOrBlank()) maskApiKey(key) else ""
  }

  private fun getOrCreatePrefs(): SharedPreferences? {
    cachedPrefs?.let { return it }
    synchronized(lock) {
      cachedPrefs?.let { return it }
      val created = createEncryptedPrefs() ?: recoverAndRecreatePrefs()
      cachedPrefs = created
      return created
    }
  }

  @Suppress("DEPRECATION")
  private fun createEncryptedPrefs(): SharedPreferences? {
    return try {
      val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
      EncryptedSharedPreferences.create(
        appContext,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
    } catch (t: Throwable) {
      _lastError.value = t.message ?: "Keystore initialization error"
      null
    }
  }

  private fun recoverAndRecreatePrefs(): SharedPreferences? {
    // Wipe corrupted encrypted prefs XML once if Android Keystore keys were invalidated.
    runCatching {
      val prefsFile = File(appContext.filesDir.parentFile, "shared_prefs/$PREFS_FILE_NAME.xml")
      if (prefsFile.exists()) prefsFile.delete()
    }
    return createEncryptedPrefs()
  }

  companion object {
    private const val PREFS_FILE_NAME = "jarvis_key_vault"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"

    internal fun maskApiKey(raw: String): String {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) return ""
      if (trimmed.length <= 8) return "••••"
      return "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
    }
  }
}
