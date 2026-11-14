package com.nexwork.edith.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
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
   * Attempts silent in-place installation via Shizuku (`pm install -r -S`), falling back to a
   * [PackageInstaller.Session] configured for self-update without prompt (`USER_ACTION_NOT_REQUIRED`).
   * Returns `true` when installed silently, `false` when handed off to system confirmation/settings UI.
   */
  suspend fun installApk(apkFile: File): Result<Boolean> = withContext(Dispatchers.IO) {
    runCatching {
      if (tryShizukuSilentInstall(apkFile)) {
        return@runCatching true
      }
      installViaPackageInstaller(apkFile)
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

    val args = Shizuku.UserServiceArgs(
      ComponentName(context.packageName, ShizukuInstallService::class.java.name),
    )
      .daemon(false)
      .processNameSuffix("shizuku_installer")
      .debuggable(false)
      .version(1)

    val binderDeferred = CompletableDeferred<IBinder?>()
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        if (!binderDeferred.isCompleted) binderDeferred.complete(service)
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        if (!binderDeferred.isCompleted) binderDeferred.complete(null)
      }
    }

    return try {
      withContext(Dispatchers.Main) {
        Shizuku.bindUserService(args, connection)
      }
      val binder = withTimeoutOrNull(10_000L) { binderDeferred.await() }
      if (binder == null || !binder.isBinderAlive) {
        android.util.Log.w("OtaUpdater", "Shizuku UserService failed to bind in time")
        return false
      }

      val pfd = ParcelFileDescriptor.open(apkFile, ParcelFileDescriptor.MODE_READ_ONLY)
      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      try {
        data.writeInterfaceToken(ShizukuInstallService.DESCRIPTOR)
        data.writeFileDescriptor(pfd.fileDescriptor)
        data.writeLong(apkFile.length())
        binder.transact(ShizukuInstallService.TRANSACTION_INSTALL, data, reply, 0)
        reply.readException()
        val ok = reply.readInt() == 1
        val output = reply.readString().orEmpty()
        android.util.Log.i("OtaUpdater", "Shizuku pm install ok=$ok output=$output")
        ok
      } finally {
        data.recycle()
        reply.recycle()
        pfd.close()
      }
    } catch (t: Throwable) {
      android.util.Log.w("OtaUpdater", "Shizuku UserService install error, falling back", t)
      false
    } finally {
      try {
        withContext(Dispatchers.Main) {
          Shizuku.unbindUserService(args, connection, true)
        }
      } catch (_: Throwable) {
      }
    }
  }

  private suspend fun installViaPackageInstaller(apkFile: File): Boolean {
    val appCtx = context.applicationContext
    if (!appCtx.packageManager.canRequestPackageInstalls()) {
      withContext(Dispatchers.Main) {
        launchUnknownSourcesSettings(appCtx)
      }
      return false
    }

    val packageInstaller = appCtx.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(appCtx.packageName)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
      }
    }

    val sessionId = try {
      packageInstaller.createSession(params)
    } catch (t: Throwable) {
      android.util.Log.w("OtaUpdater", "createSession failed, falling back to ACTION_VIEW", t)
      withContext(Dispatchers.Main) { launchSystemInstaller(appCtx, apkFile) }
      return false
    }

    val action = "${appCtx.packageName}.action.OTA_INSTALL_STATUS.$sessionId"
    val deferred = CompletableDeferred<Boolean>()
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        if (intent == null) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
          PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            val confirmIntent = IntentCompat.getParcelableExtra(
              intent,
              Intent.EXTRA_INTENT,
              Intent::class.java,
            )
            if (confirmIntent != null) {
              confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              runCatching { appCtx.startActivity(confirmIntent) }
            }
            if (!deferred.isCompleted) deferred.complete(false)
          }
          PackageInstaller.STATUS_SUCCESS -> {
            if (!deferred.isCompleted) deferred.complete(true)
          }
          else -> {
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            android.util.Log.w("OtaUpdater", "PackageInstaller failed status=$status msg=$msg")
            if (!deferred.isCompleted) {
              deferred.completeExceptionally(
                IllegalStateException(msg.ifBlank { "PackageInstaller failed ($status)" }),
              )
            }
          }
        }
      }
    }

    var committed = false
    ContextCompat.registerReceiver(appCtx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    return try {
      val session = packageInstaller.openSession(sessionId)
      try {
        apkFile.inputStream().use { input ->
          session.openWrite("edith-update.apk", 0L, apkFile.length()).use { output ->
            input.copyTo(output, bufferSize = 32 * 1024)
            session.fsync(output)
          }
        }
        val statusIntent = Intent(action).setPackage(appCtx.packageName)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
          PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(appCtx, sessionId, statusIntent, piFlags)
        session.commit(pendingIntent.intentSender)
        committed = true
      } finally {
        session.close()
      }
      withTimeoutOrNull(90_000L) { deferred.await() } ?: false
    } catch (t: Throwable) {
      if (!committed) {
        runCatching { packageInstaller.abandonSession(sessionId) }
        android.util.Log.w("OtaUpdater", "Session commit failed, falling back to ACTION_VIEW", t)
        withContext(Dispatchers.Main) { launchSystemInstaller(appCtx, apkFile) }
        false
      } else {
        throw t
      }
    } finally {
      runCatching { appCtx.unregisterReceiver(receiver) }
    }
  }

  private fun launchUnknownSourcesSettings(appCtx: Context) {
    val permIntent = Intent(
      Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
      Uri.parse("package:${appCtx.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    appCtx.startActivity(permIntent)
  }

  private fun launchSystemInstaller(appCtx: Context, apkFile: File) {
    val uri = FileProvider.getUriForFile(
      appCtx,
      "${appCtx.packageName}.fileprovider",
      apkFile,
    )
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    appCtx.startActivity(installIntent)
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
