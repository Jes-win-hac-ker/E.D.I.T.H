package com.nexwork.edith.jarvis.senses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import androidx.core.content.ContextCompat
import com.nexwork.edith.jarvis.core.CapturedNotification
import java.io.File
import java.util.Locale

/**
 * Optional event-driven `.opus` voice-note observer watching WhatsApp and Telegram media folders
 * via Android [FileObserver] (zero polling). Only active when explicitly opted-in by the user and
 * `READ_MEDIA_AUDIO` (or `READ_EXTERNAL_STORAGE` below API 33) is granted.
 */
class VoiceNoteObserver {

  private data class ObservedVoiceFile(
    val file: File,
    val sourcePackageHint: String,
    val observedAtMs: Long,
  )

  private val lock = Any()
  private val watchers = mutableListOf<FileObserver>()
  private val recentOpusFiles = ArrayDeque<ObservedVoiceFile>()
  private val watchedPaths = HashSet<String>()

  @Volatile
  var enabled: Boolean = false
    private set

  fun setEnabled(appCtx: Context, enable: Boolean) {
    enabled = enable
    if (!enable || !hasAudioReadPermission(appCtx)) {
      stopAll()
    } else {
      startWatching(appCtx)
    }
  }

  fun startIfPermitted(appCtx: Context) {
    if (enabled && hasAudioReadPermission(appCtx)) {
      startWatching(appCtx)
    }
  }

  fun stopAll() {
    val active = synchronized(lock) {
      val copy = watchers.toList()
      watchers.clear()
      watchedPaths.clear()
      recentOpusFiles.clear()
      copy
    }
    active.forEach { observer -> runCatching { observer.stopWatching() } }
  }

  /**
   * Resolves settled `.opus` audio bytes for a voice-note notification (capped cleanly at 3 MB),
   * or returns null if disabled, permission missing, non-voice message, or no matching file exists.
   */
  fun resolveAudioBytesForNotification(
    context: Context,
    captured: CapturedNotification,
  ): ByteArray? {
    if (!enabled) return null
    if (!isVoiceNoteNotification(captured)) return null
    if (!hasAudioReadPermission(context)) return null

    val now = System.currentTimeMillis()
    val pkg = captured.packageName.trim().lowercase(Locale.ROOT)
    val postTime = captured.postTimeMs.takeIf { it > 0L } ?: now

    val candidate = synchronized(lock) {
      pruneOldLocked(now)
      recentOpusFiles.lastOrNull { entry ->
        val matchesPkg = entry.sourcePackageHint.isEmpty() || pkg.startsWith(entry.sourcePackageHint)
        val delta = kotlin.math.abs(entry.observedAtMs - postTime)
        matchesPkg && delta <= MAX_CORRELATION_WINDOW_MS && entry.file.exists() && entry.file.length() in 64L..MAX_AUDIO_BYTES
      }?.file
    } ?: findNewestRecentOpusFallback(pkg, postTime)

    return candidate?.let { file ->
      runCatching {
        if (file.length() in 64L..MAX_AUDIO_BYTES) file.readBytes() else null
      }.getOrNull()
    }
  }

  @Suppress("DEPRECATION")
  private fun startWatching(appCtx: Context) {
    val storageRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return
    val targetDirs = listOf(
      File(storageRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes") to "com.whatsapp",
      File(storageRoot, "WhatsApp/Media/WhatsApp Voice Notes") to "com.whatsapp",
      File(storageRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes") to "com.whatsapp.w4b",
      File(storageRoot, "Android/media/org.telegram.messenger/Telegram/Telegram Audio") to "org.telegram.messenger",
      File(storageRoot, "Telegram/Telegram Audio") to "org.telegram.messenger",
    )

    targetDirs.forEach { (dir, pkgHint) ->
      if (dir.exists() && dir.isDirectory) {
        attachDirectoryWatcher(dir, pkgHint, recurseChildren = true)
      }
    }
  }

  private fun attachDirectoryWatcher(dir: File, pkgHint: String, recurseChildren: Boolean) {
    val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
    synchronized(lock) {
      if (!watchedPaths.add(canonical)) return
    }
    val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
    val observer = object : FileObserver(dir, mask) {
      override fun onEvent(event: Int, path: String?) {
        if (!enabled || path.isNullOrBlank()) return
        val child = File(dir, path)
        if ((event and (FileObserver.CREATE or FileObserver.MOVED_TO)) != 0 && child.isDirectory) {
          attachDirectoryWatcher(child, pkgHint, recurseChildren = false)
          return
        }
        val cleanName = path.lowercase(Locale.ROOT)
        if ((event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO)) != 0 &&
          (cleanName.endsWith(".opus") || cleanName.endsWith(".ogg"))
        ) {
          recordObservedFile(child, pkgHint)
        }
      }
    }
    runCatching {
      observer.startWatching()
      synchronized(lock) { watchers.add(observer) }
    }

    if (recurseChildren) {
      // Attach observers to the 2 most recent weekly/period subfolders (e.g., WhatsApp YYYYWW)
      dir.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.lastModified() }
        ?.take(2)
        ?.forEach { subDir -> attachDirectoryWatcher(subDir, pkgHint, recurseChildren = false) }
    }
  }

  private fun recordObservedFile(file: File, pkgHint: String) {
    val now = System.currentTimeMillis()
    synchronized(lock) {
      pruneOldLocked(now)
      recentOpusFiles.addLast(ObservedVoiceFile(file = file, sourcePackageHint = pkgHint, observedAtMs = now))
      while (recentOpusFiles.size > MAX_RECENT_FILES) {
        recentOpusFiles.removeFirst()
      }
    }
  }

  private fun pruneOldLocked(now: Long) {
    while (recentOpusFiles.isNotEmpty() && (now - recentOpusFiles.first().observedAtMs) > CACHE_RETENTION_MS) {
      recentOpusFiles.removeFirst()
    }
  }

  @Suppress("DEPRECATION")
  private fun findNewestRecentOpusFallback(pkg: String, postTime: Long): File? {
    val storageRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return null
    val roots = when {
      pkg.startsWith("com.whatsapp.w4b") -> listOf(
        File(storageRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"),
      )
      pkg.startsWith("com.whatsapp") -> listOf(
        File(storageRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
        File(storageRoot, "WhatsApp/Media/WhatsApp Voice Notes"),
      )
      pkg.startsWith("org.telegram") -> listOf(
        File(storageRoot, "Android/media/org.telegram.messenger/Telegram/Telegram Audio"),
        File(storageRoot, "Telegram/Telegram Audio"),
      )
      else -> emptyList()
    }
    return roots
      .filter { it.exists() && it.isDirectory }
      .flatMap { root ->
        val latestSub = root.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }
        val candidates = mutableListOf<File>()
        root.listFiles()?.filter { it.isFile }?.let(candidates::addAll)
        latestSub?.listFiles()?.filter { it.isFile }?.let(candidates::addAll)
        candidates
      }
      .filter { file ->
        val n = file.name.lowercase(Locale.ROOT)
        (n.endsWith(".opus") || n.endsWith(".ogg")) &&
          file.length() in 64L..MAX_AUDIO_BYTES &&
          kotlin.math.abs(file.lastModified() - postTime) <= MAX_CORRELATION_WINDOW_MS
      }
      .maxByOrNull { it.lastModified() }
  }

  companion object {
    private const val MAX_AUDIO_BYTES = 3L * 1024L * 1024L
    private const val MAX_RECENT_FILES = 20
    private const val MAX_CORRELATION_WINDOW_MS = 25_000L
    private const val CACHE_RETENTION_MS = 60_000L

    private val VOICE_PACKAGES = setOf(
      "com.whatsapp",
      "com.whatsapp.w4b",
      "org.telegram.messenger",
      "org.thunderdog.challegram",
    )

    fun hasAudioReadPermission(context: Context): Boolean {
      val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
      } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
      }
      return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    internal fun isVoiceNoteNotification(captured: CapturedNotification): Boolean {
      val pkg = captured.packageName.trim().lowercase(Locale.ROOT)
      if (pkg !in VOICE_PACKAGES) return false
      val bodyLower = "${captured.title} ${captured.body}".lowercase(Locale.ROOT)
      return bodyLower.contains("voice message") ||
        bodyLower.contains("voice note") ||
        bodyLower.contains("audio message") ||
        bodyLower.contains("🎤") ||
        Regex("""\bptt\b""").containsMatchIn(bodyLower) ||
        Regex("""\baudio\s*\(\d+:\d+\)""").containsMatchIn(bodyLower)
    }
  }
}
