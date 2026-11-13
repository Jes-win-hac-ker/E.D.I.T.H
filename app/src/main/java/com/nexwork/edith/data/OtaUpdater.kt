package com.nexwork.edith.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Metadata for a remote OTA release parsed from GitHub Releases or a direct version.json feed. */
data class OtaRelease(
  val versionCode: Long,
  val versionName: String,
  val apkUrl: String,
  val sha256: String? = null,
)

/** Current lifecycle state of the in-app OTA updater. */
sealed interface OtaState {
  data object Idle : OtaState
  data object Checking : OtaState
  data class Available(val release: OtaRelease) : OtaState
  data class Downloading(val release: OtaRelease, val progress: Int) : OtaState
  data class ReadyToInstall(val release: OtaRelease, val apkFile: File) : OtaState
  data object UpToDate : OtaState
  data class Error(val message: String) : OtaState
}

/**
 * Checks a remote HTTPS endpoint (GitHub Releases API or direct `version.json`), streams the APK
 * to `cacheDir/ota/edith-update.apk`, verifies SHA-256 when provided, and hands off to Android's
 * package installer via [FileProvider].
 */
class OtaUpdater(private val context: Context) {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun getFeedUrl(): String =
    prefs.getString(KEY_FEED_URL, DEFAULT_FEED_URL) ?: DEFAULT_FEED_URL

  fun setFeedUrl(url: String) {
    val trimmed = url.trim()
    if (trimmed.startsWith("https://")) {
      prefs.edit().putString(KEY_FEED_URL, trimmed).apply()
    }
  }

  fun installedVersionCode(): Long = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0),
      )
    } else {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.longVersionCode
  } catch (_: Exception) {
    1L
  }

  fun shouldAutoCheck(nowMs: Long = System.currentTimeMillis()): Boolean {
    val last = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
    return (nowMs - last) >= AUTO_CHECK_INTERVAL_MS
  }

  suspend fun checkForUpdate(): Result<OtaRelease?> = withContext(Dispatchers.IO) {
    runCatching {
      prefs.edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
      val feedUrl = getFeedUrl()
      val body = fetchText(feedUrl)
      val release = parseReleaseJson(body) ?: return@runCatching null
      if (release.versionCode > installedVersionCode()) release else null
    }
  }

  suspend fun downloadAndVerify(
    release: OtaRelease,
    onProgress: (Int) -> Unit,
  ): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
      val otaDir = File(context.cacheDir, "ota").apply { mkdirs() }
      val target = File(otaDir, "edith-update.apk")
      val temp = File(otaDir, "edith-update.apk.part")

      val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/octet-stream")
        setRequestProperty("User-Agent", "EDITH-Launcher-OTA")
      }
      try {
        val code = conn.responseCode
        if (code !in 200..299) error("HTTP $code downloading APK")

        val totalBytes = conn.contentLengthLong
        var downloaded = 0L
        var lastReported = -1
        val digest = if (!release.sha256.isNullOrBlank()) MessageDigest.getInstance("SHA-256") else null

        conn.inputStream.use { input ->
          FileOutputStream(temp).use { output ->
            val buf = ByteArray(16 * 1024)
            while (true) {
              val read = input.read(buf)
              if (read <= 0) break
              output.write(buf, 0, read)
              digest?.update(buf, 0, read)
              downloaded += read
              if (totalBytes > 0L) {
                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                if (pct != lastReported) {
                  lastReported = pct
                  onProgress(pct)
                }
              }
            }
          }
        }

        val expectedHash = release.sha256?.trim()?.lowercase()
        if (!expectedHash.isNullOrEmpty() && digest != null) {
          val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
          if (actualHash != expectedHash) {
            temp.delete()
            error("SHA-256 mismatch")
          }
        }

        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
          temp.copyTo(target, overwrite = true)
          temp.delete()
        }
        target
      } finally {
        conn.disconnect()
      }
    }
  }

  fun launchInstaller(apkFile: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
      val permIntent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
      ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(permIntent)
      return
    }

    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      apkFile,
    )
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
  }

  private fun fetchText(url: String): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 12_000
      readTimeout = 15_000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/vnd.github+json, application/json")
      setRequestProperty("User-Agent", "EDITH-Launcher-OTA")
    }
    return try {
      val code = conn.responseCode
      if (code == 404) error("No release published yet (404)")
      if (code !in 200..299) error("HTTP $code")
      conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }

  companion object {
    const val DEFAULT_FEED_URL =
      "https://api.github.com/repos/Jes-win-hac-ker/E.D.I.T.H/releases/latest"
    private const val PREFS_NAME = "edith_ota"
    private const val KEY_FEED_URL = "feed_url"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val AUTO_CHECK_INTERVAL_MS = 30L * 60L * 1000L

    private val VERSION_CODE_REGEX = Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val SHA256_REGEX = Regex("""sha256\s*[:=]\s*([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE)
    private val TAG_PLUS_CODE_REGEX = Regex("""\+(\d+)$""")
    private val TAG_NUMERIC_REGEX = Regex("""v?(\d+)$""")
    private val JSON_NUM_VERSION_CODE = Regex(""""versionCode"\s*:\s*(\d+)""")
    private val JSON_APK_URL = Regex(""""apkUrl"\s*:\s*"([^"]+)"""")
    private val JSON_VERSION_NAME = Regex(""""versionName"\s*:\s*"([^"]+)"""")
    private val JSON_SHA256 = Regex(""""sha256"\s*:\s*"([^"]+)"""")
    private val JSON_TAG_NAME = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
    private val JSON_BODY = Regex(""""body"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val JSON_ASSET_APK = Regex(""""browser_download_url"\s*:\s*"(https?://[^"]+?\.apk)"""", RegexOption.IGNORE_CASE)

    /**
     * Parses either a direct `version.json` object (`versionCode`, `versionName`, `apkUrl`, `sha256`)
     * or a GitHub Releases API payload (`tag_name`, `body`, `assets[].browser_download_url`).
     */
    fun parseReleaseJson(rawJson: String): OtaRelease? {
      // 1. Direct version.json manifest
      val directCode = JSON_NUM_VERSION_CODE.find(rawJson)?.groupValues?.get(1)?.toLongOrNull()
      val directApk = JSON_APK_URL.find(rawJson)?.groupValues?.get(1)?.trim()
      if (directCode != null && directCode > 0L && !directApk.isNullOrEmpty()) {
        val name = JSON_VERSION_NAME.find(rawJson)?.groupValues?.get(1)?.trim()
          ?.ifEmpty { null } ?: directCode.toString()
        val sha = JSON_SHA256.find(rawJson)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        return OtaRelease(versionCode = directCode, versionName = name, apkUrl = directApk, sha256 = sha)
      }

      // 2. GitHub Releases API payload
      val tagName = JSON_TAG_NAME.find(rawJson)?.groupValues?.get(1)?.trim().orEmpty()
      val rawBody = JSON_BODY.find(rawJson)?.groupValues?.get(1)
        ?.replace("\\n", "\n")
        ?.replace("\\r", "\r")
        .orEmpty()
      val apkUrl = JSON_ASSET_APK.find(rawJson)?.groupValues?.get(1)?.trim() ?: return null

      val versionCode = VERSION_CODE_REGEX.find(rawBody)?.groupValues?.get(1)?.toLongOrNull()
        ?: TAG_PLUS_CODE_REGEX.find(tagName)?.groupValues?.get(1)?.toLongOrNull()
        ?: TAG_NUMERIC_REGEX.find(tagName)?.groupValues?.get(1)?.toLongOrNull()
        ?: return null

      val sha256 = SHA256_REGEX.find(rawBody)?.groupValues?.get(1)?.lowercase()
      val cleanName = tagName.removePrefix("v").substringBefore("+").ifEmpty { versionCode.toString() }
      return OtaRelease(
        versionCode = versionCode,
        versionName = cleanName,
        apkUrl = apkUrl,
        sha256 = sha256,
      )
    }
  }
}
