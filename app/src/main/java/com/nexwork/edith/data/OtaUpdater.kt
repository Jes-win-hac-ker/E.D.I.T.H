package com.nexwork.edith.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.io.FileInputStream
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
  data class Installing(val release: OtaRelease) : OtaState
  data class Installed(val release: OtaRelease) : OtaState
  data object UpToDate : OtaState
  data class Error(val message: String) : OtaState
}

/**
 * Checks a remote HTTPS endpoint (GitHub Releases API or direct `version.json`), caches verified
 * APKs in `cacheDir/ota/` to prevent duplicate downloads, and installs either silently via Shizuku
 * (`pm install -r -S`) or via Android's standard [FileProvider] package installer.
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

  fun isUnmeteredWifi(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }

  /** Deletes cached update APKs once the installed version catches up or surpasses them. */
  fun cleanupStaleCache() {
    val cachedCode = prefs.getLong(KEY_CACHED_VERSION_CODE, -1L)
    val target = cachedApkFile()
    if (cachedCode > 0L && installedVersionCode() >= cachedCode) {
      if (target.exists()) target.delete()
      prefs.edit().remove(KEY_CACHED_VERSION_CODE).remove(KEY_CACHED_SHA256).apply()
    }
  }

  /** Returns the cached APK if it already satisfies [release] without re-downloading. */
  fun getCachedValidApk(release: OtaRelease): File? {
    val target = cachedApkFile()
    val cachedCode = prefs.getLong(KEY_CACHED_VERSION_CODE, -1L)
    val cachedSha = prefs.getString(KEY_CACHED_SHA256, null)
    val needDownload = shouldDownload(
      installedCode = installedVersionCode(),
      release = release,
      cachedCode = cachedCode,
      cachedSha = cachedSha,
      cachedFileExists = target.exists() && target.length() > 0L,
    )
    return if (!needDownload && release.versionCode > installedVersionCode() && target.exists()) {
      target
    } else {
      null
    }
  }

  suspend fun checkForUpdate(): Result<OtaRelease?> = withContext(Dispatchers.IO) {
    runCatching {
      cleanupStaleCache()
      prefs.edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
      val body = fetchText(getFeedUrl())
      val release = parseReleaseJson(body) ?: return@runCatching null
      if (release.versionCode > installedVersionCode()) release else null
    }
  }

  suspend fun downloadAndVerify(
    release: OtaRelease,
    onProgress: (Int) -> Unit,
  ): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
      getCachedValidApk(release)?.let { cached ->
        onProgress(100)
        return@runCatching cached
      }

      val otaDir = File(context.cacheDir, "ota").apply { mkdirs() }
      val target = cachedApkFile()
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
        val digest = MessageDigest.getInstance("SHA-256")

        conn.inputStream.use { input ->
          FileOutputStream(temp).use { output ->
            val buf = ByteArray(16 * 1024)
            while (true) {
              val read = input.read(buf)
              if (read <= 0) break
              output.write(buf, 0, read)
              digest.update(buf, 0, read)
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

        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        val expectedHash = release.sha256?.trim()?.lowercase()
        if (!expectedHash.isNullOrEmpty() && actualHash != expectedHash) {
          temp.delete()
          error("SHA-256 mismatch")
        }

        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
          temp.copyTo(target, overwrite = true)
          temp.delete()
        }
        prefs.edit()
          .putLong(KEY_CACHED_VERSION_CODE, release.versionCode)
          .putString(KEY_CACHED_SHA256, actualHash)
          .apply()
        target
      } finally {
        conn.disconnect()
      }
    }
  }

  /**
   * Attempts silent in-place installation via Shizuku (`pm install -r -S`), falling back to
   * Android's standard [FileProvider] installer if Shizuku is unavailable or unauthorized.
   * Returns `true` when installed silently via Shizuku, `false` when handed off to system UI.
   */
  suspend fun installApk(apkFile: File): Result<Boolean> = withContext(Dispatchers.IO) {
    runCatching {
      if (tryShizukuSilentInstall(apkFile)) {
        return@runCatching true
      }
      withContext(Dispatchers.Main) {
        launchSystemInstaller(apkFile)
      }
      false
    }
  }

  fun isShizukuReady(): Boolean = try {
    Shizuku.pingBinder()
  } catch (_: Throwable) {
    false
  }

  /** Prompts for Shizuku authorization upfront on launcher start so OTA updates never race it. */
  fun requestShizukuPermissionUpfront() {
    try {
      Shizuku.addBinderReceivedListenerSticky {
        try {
          if (Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
          ) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
          }
        } catch (t: Throwable) {
          android.util.Log.w("OtaUpdater", "Upfront Shizuku permission check failed", t)
        }
      }
    } catch (t: Throwable) {
      android.util.Log.w("OtaUpdater", "Failed to register Shizuku binder listener", t)
    }
  }

  private suspend fun ensureShizukuPermission(): Boolean {
    if (!isShizukuReady()) return false
    val alreadyGranted = try {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
      false
    }
    if (alreadyGranted) return true

    val deferred = CompletableDeferred<Boolean>()
    val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
      if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE && !deferred.isCompleted) {
        deferred.complete(grantResult == PackageManager.PERMISSION_GRANTED)
      }
    }
    return try {
      Shizuku.addRequestPermissionResultListener(listener)
      Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
      withTimeoutOrNull(30_000L) { deferred.await() } ?: false
    } catch (_: Throwable) {
      false
    } finally {
      try {
        Shizuku.removeRequestPermissionResultListener(listener)
      } catch (_: Throwable) {
      }
    }
  }

  private suspend fun tryShizukuSilentInstall(apkFile: File): Boolean {
    if (!ensureShizukuPermission()) {
      android.util.Log.w("OtaUpdater", "Shizuku not ready or permission denied (ping=${isShizukuReady()})")
      return false
    }

    return try {
      HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/")
      val pmBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"))
      val iPmClass = Class.forName("android.content.pm.IPackageManager\$Stub")
      val iPm = iPmClass.getMethod("asInterface", IBinder::class.java).invoke(null, pmBinder)
      val iPi = iPm.javaClass.getMethod("getPackageInstaller").invoke(iPm)
      val iPiClass = Class.forName("android.content.pm.IPackageInstaller")

      val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PackageInstaller::class.java
          .getConstructor(iPiClass, String::class.java, String::class.java, Int::class.javaPrimitiveType)
          .newInstance(iPi, "com.android.shell", null, 0)
      } else {
        PackageInstaller::class.java
          .getConstructor(iPiClass, String::class.java, Int::class.javaPrimitiveType)
          .newInstance(iPi, "com.android.shell", 0)
      }

      val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
      }
      val sessionId = installer.createSession(params)
      installer.openSession(sessionId).use { session ->
        session.openWrite("edith-update.apk", 0, apkFile.length()).use { out ->
          FileInputStream(apkFile).use { input ->
            input.copyTo(out, bufferSize = 32 * 1024)
          }
          session.fsync(out)
        }
        val statusIntent = PendingIntent.getBroadcast(
          context,
          sessionId,
          Intent("${context.packageName}.OTA_INSTALL_STATUS").setPackage(context.packageName),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session.commit(statusIntent.intentSender)
      }
      true
    } catch (t: Throwable) {
      android.util.Log.w("OtaUpdater", "Shizuku install error, falling back", t)
      false
    }
  }

  private fun launchSystemInstaller(apkFile: File) {
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

  private fun cachedApkFile(): File = File(File(context.cacheDir, "ota"), "edith-update.apk")

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
    private const val KEY_CACHED_VERSION_CODE = "cached_version_code"
    private const val KEY_CACHED_SHA256 = "cached_sha256"
    private const val AUTO_CHECK_INTERVAL_MS = 30L * 60L * 1000L
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4207

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

    /** Returns true only when [release] is newer than [installedCode] and not already cached. */
    fun shouldDownload(
      installedCode: Long,
      release: OtaRelease,
      cachedCode: Long,
      cachedSha: String?,
      cachedFileExists: Boolean,
    ): Boolean {
      if (release.versionCode <= installedCode) return false
      if (cachedFileExists && cachedCode == release.versionCode) {
        val expectedSha = release.sha256?.trim()?.lowercase()
        if (expectedSha.isNullOrEmpty() || expectedSha == cachedSha?.trim()?.lowercase()) {
          return false
        }
      }
      return true
    }

    /**
     * Parses either a direct `version.json` object (`versionCode`, `versionName`, `apkUrl`, `sha256`)
     * or a GitHub Releases API payload (`tag_name`, `body`, `assets[].browser_download_url`).
     */
    fun parseReleaseJson(rawJson: String): OtaRelease? {
      val directCode = JSON_NUM_VERSION_CODE.find(rawJson)?.groupValues?.get(1)?.toLongOrNull()
      val directApk = JSON_APK_URL.find(rawJson)?.groupValues?.get(1)?.trim()
      if (directCode != null && directCode > 0L && !directApk.isNullOrEmpty()) {
        val name = JSON_VERSION_NAME.find(rawJson)?.groupValues?.get(1)?.trim()
          ?.ifEmpty { null } ?: directCode.toString()
        val sha = JSON_SHA256.find(rawJson)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        return OtaRelease(versionCode = directCode, versionName = name, apkUrl = directApk, sha256 = sha)
      }

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
