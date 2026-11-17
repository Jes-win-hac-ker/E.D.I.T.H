package com.nexwork.edith.data

import android.content.Context
import androidx.annotation.Keep
import com.nexwork.edith.jarvis.core.CompactUiNode
import com.nexwork.edith.jarvis.core.CompactUiTree
import com.nexwork.edith.jarvis.security.SecurityGateway
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Shizuku UserService running in the privileged `shell` (`uid 2000`) process.
 *
 * Executes only fixed, allowlisted system commands via [ProcessBuilder] without invoking a shell,
 * providing permission auto-granting, connectivity/DND toggles, refresh-rate presets, a compact
 * system status summary, and Shizuku (`uid 2000`) Jarvis UI Hands (`uiautomator dump` +
 * `input tap/swipe/text/keyevent`) for E.D.I.T.H without root.
 */
@Keep
class ShizukuControlService : IShizukuControlService.Stub {

  @Keep
  constructor() : super()

  @Keep
  constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

  override fun destroy() {
    exitProcess(0)
  }

  override fun grantLauncherPermissions(packageName: String, listenerComponent: String): Boolean {
    if (!ShizukuControlManager.sanitizeComponentToken(packageName) ||
      !ShizukuControlManager.sanitizeComponentToken(listenerComponent)
    ) {
      return false
    }
    val appOpsOk = runCommand("appops", "set", packageName, "GET_USAGE_STATS", "allow").exitCode == 0
    val listenerOk = runCommand("cmd", "notification", "allow_listener", listenerComponent).exitCode == 0
    return appOpsOk || listenerOk
  }

  override fun setWifiEnabled(enabled: Boolean): Boolean {
    val primary = runCommand("cmd", "wifi", "set-wifi-enabled", if (enabled) "enabled" else "disabled")
    if (primary.exitCode == 0) return true
    return runCommand("svc", "wifi", if (enabled) "enable" else "disable").exitCode == 0
  }

  override fun setBluetoothEnabled(enabled: Boolean): Boolean {
    val primary = runCommand("cmd", "bluetooth_manager", if (enabled) "enable" else "disable")
    if (primary.exitCode == 0) return true
    return runCommand("svc", "bluetooth", if (enabled) "enable" else "disable").exitCode == 0
  }

  override fun setDndEnabled(enabled: Boolean): Boolean {
    return runCommand("cmd", "notification", "set_dnd", if (enabled) "priority" else "off").exitCode == 0
  }

  override fun setRefreshRateHz(hz: Int): Boolean {
    val targetHz = ShizukuControlManager.parseRefreshRatePreset(hz)
    val minHz = if (targetHz >= 90) "60.0" else "$targetHz.0"
    val peakOk = runCommand(
      "cmd",
      "settings",
      "put",
      "system",
      "peak_refresh_rate",
      "$targetHz.0",
    ).exitCode == 0
    val minOk = runCommand(
      "cmd",
      "settings",
      "put",
      "system",
      "min_refresh_rate",
      minHz,
    ).exitCode == 0
    return peakOk && minOk
  }

  override fun readSystemStatus(): String {
    return runCatching {
      val wifiOut = runCommand("cmd", "wifi", "status").stdout
      val wifiLabel = when {
        wifiOut.contains("Wi-Fi is enabled", ignoreCase = true) -> "WIFI:ON"
        wifiOut.contains("Wi-Fi is disabled", ignoreCase = true) -> "WIFI:OFF"
        else -> "WIFI:?"
      }

      val hzOut = runCommand("cmd", "settings", "get", "system", "peak_refresh_rate").stdout
      val hzLabel = hzOut.toDoubleOrNull()?.toInt()?.let { "${it}Hz" } ?: "--Hz"

      val zenOut = runCommand("cmd", "settings", "get", "global", "zen_mode").stdout
      val dndLabel = when (zenOut.toIntOrNull()) {
        null -> "DND:?"
        0 -> "DND:OFF"
        else -> "DND:ON"
      }

      "$wifiLabel | $hzLabel | $dndLabel"
    }.getOrDefault("WIFI:? | --Hz | DND:?")
  }

  override fun dumpUiHierarchyXml(): String {
    val dumpFile = File(UI_DUMP_PATH)
    return runCatching {
      runCatching { if (dumpFile.exists()) dumpFile.delete() }
      runCommand("uiautomator", "dump", UI_DUMP_PATH, timeoutMs = UI_DUMP_TIMEOUT_MS)
      val fileXml = if (dumpFile.exists() && dumpFile.length() > 0L) {
        dumpFile.readText()
      } else {
        ""
      }
      runCatching { if (dumpFile.exists()) dumpFile.delete() }
      if (fileXml.contains("<hierarchy")) {
        fileXml
      } else {
        val ttyOut = runCommand("uiautomator", "dump", "/dev/tty", timeoutMs = UI_DUMP_TIMEOUT_MS).stdout
        val xmlStart = ttyOut.indexOf("<?xml").takeIf { it >= 0 } ?: ttyOut.indexOf("<hierarchy")
        if (xmlStart >= 0) ttyOut.substring(xmlStart) else ""
      }
    }.getOrDefault("")
  }

  override fun inputTap(x: Int, y: Int): Boolean {
    val safeX = x.coerceIn(0, 10_000).toString()
    val safeY = y.coerceIn(0, 10_000).toString()
    return runCommand("input", "tap", safeX, safeY).exitCode == 0
  }

  override fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
    val sx1 = x1.coerceIn(0, 10_000).toString()
    val sy1 = y1.coerceIn(0, 10_000).toString()
    val sx2 = x2.coerceIn(0, 10_000).toString()
    val sy2 = y2.coerceIn(0, 10_000).toString()
    val dur = durationMs.coerceIn(40, 4_000).toString()
    return runCommand("input", "swipe", sx1, sy1, sx2, sy2, dur).exitCode == 0
  }

  override fun inputTextShell(escapedText: String): Boolean {
    if (escapedText.isEmpty()) return true
    return runCommand("input", "text", escapedText).exitCode == 0
  }

  override fun inputKeyEvent(keyCode: Int): Boolean {
    if (keyCode <= 0 || keyCode > 1000) return false
    return runCommand("input", "keyevent", keyCode.toString()).exitCode == 0
  }

  private data class CommandResult(val exitCode: Int, val stdout: String)

  private fun runCommand(vararg argv: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): CommandResult {
    return runCatching {
      val proc = ProcessBuilder(*argv)
        .redirectErrorStream(true)
        .start()
      try {
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
          proc.destroyForcibly()
          return CommandResult(exitCode = -1, stdout = "")
        }
        val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
        CommandResult(exitCode = proc.exitValue(), stdout = out)
      } finally {
        runCatching { proc.inputStream.close() }
        runCatching { proc.outputStream.close() }
        runCatching { proc.errorStream.close() }
      }
    }.getOrDefault(CommandResult(exitCode = -1, stdout = ""))
  }

  internal data class RawNodeSnapshot(
    val packageName: String = "",
    val className: String,
    val viewId: String,
    val text: String,
    val contentDesc: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    fun toCompactNode(id: Int): CompactUiNode = CompactUiNode(
      id = id,
      className = className,
      viewId = viewId,
      text = text.take(80),
      contentDesc = contentDesc.take(80),
      clickable = clickable,
      editable = editable,
      scrollable = scrollable,
      left = left,
      top = top,
      right = right,
      bottom = bottom,
    )
  }

  companion object {
    private const val COMMAND_TIMEOUT_MS = 4_000L
    private const val UI_DUMP_TIMEOUT_MS = 6_000L
    const val UI_DUMP_PATH = "/data/local/tmp/jarvis_ui.xml"
    const val MAX_OUTPUT_NODES = 50

    private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    private val NODE_TAG_REGEX = Regex("""<node\b([^>]*)/?>""")
    private val ATTR_REGEX = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")
    private val SHELL_META_CHARS = setOf(
      '\\', '"', '\'', '`', '$', ';', '&', '|', '(', ')', '<', '>', '*', '?', '[', ']', '{', '}', '~', '!', '#',
    )

    private val cacheLock = Any()
    private val cachedNodeMap = HashMap<Int, CompactUiNode>()
    @Volatile
    private var lastForegroundPkg: String = ""

    fun lastKnownForegroundPackage(): String = lastForegroundPkg

    fun clearNodeCache() {
      synchronized(cacheLock) {
        cachedNodeMap.clear()
      }
    }

    fun getCachedNode(id: Int): CompactUiNode? = synchronized(cacheLock) {
      cachedNodeMap[id]
    }

    internal fun cacheNodes(packageName: String, nodes: List<CompactUiNode>) {
      lastForegroundPkg = packageName
      synchronized(cacheLock) {
        cachedNodeMap.clear()
        for (n in nodes) {
          cachedNodeMap[n.id] = n
        }
      }
    }

    /**
     * Escapes text for `input text <escaped>` in Android shell:
     * converts spaces to `%s` and backslash-escapes shell metacharacters.
     */
    fun escapeShellInputText(raw: String): String {
      if (raw.isEmpty()) return ""
      val sb = StringBuilder(raw.length + 16)
      for (ch in raw) {
        when {
          ch == ' ' || ch == '\t' || ch == '\n' -> sb.append("%s")
          ch in SHELL_META_CHARS -> sb.append('\\').append(ch)
          else -> sb.append(ch)
        }
      }
      return sb.toString()
    }

    internal fun parseBounds(boundsStr: String): IntArray? {
      val match = BOUNDS_REGEX.find(boundsStr) ?: return null
      val left = match.groupValues[1].toIntOrNull() ?: return null
      val top = match.groupValues[2].toIntOrNull() ?: return null
      val right = match.groupValues[3].toIntOrNull() ?: return null
      val bottom = match.groupValues[4].toIntOrNull() ?: return null
      if (right <= left || bottom <= top) return null
      return intArrayOf(left, top, right, bottom)
    }

    internal fun shouldKeepNode(snap: RawNodeSnapshot): Boolean {
      if (snap.right <= snap.left || snap.bottom <= snap.top) return false
      return snap.clickable ||
        snap.editable ||
        snap.scrollable ||
        snap.text.isNotBlank() ||
        snap.contentDesc.isNotBlank()
    }

    internal fun filterAndAssignNodes(snapshots: List<RawNodeSnapshot>): List<CompactUiNode> {
      var nextId = 1
      val result = ArrayList<CompactUiNode>(snapshots.size.coerceAtMost(MAX_OUTPUT_NODES))
      for (snap in snapshots) {
        if (!shouldKeepNode(snap)) continue
        result.add(snap.toCompactNode(nextId++))
        if (result.size >= MAX_OUTPUT_NODES) break
      }
      return result
    }

    internal fun formatCompactTree(
      packageName: String,
      windowTitle: String,
      nodes: List<CompactUiNode>,
    ): String {
      val jsonObj = buildJsonObject {
        put("package", packageName)
        if (windowTitle.isNotBlank()) put("title", windowTitle.take(60))
        put("count", nodes.size)
        put(
          "nodes",
          buildJsonArray {
            for (n in nodes.take(MAX_OUTPUT_NODES)) {
              add(
                buildJsonObject {
                  put("id", n.id)
                  put("class", n.className)
                  if (n.viewId.isNotBlank()) put("res_id", n.viewId)
                  if (n.text.isNotBlank()) put("text", n.text.replace('\n', ' '))
                  if (n.contentDesc.isNotBlank()) put("desc", n.contentDesc.replace('\n', ' '))
                  put("clickable", n.clickable)
                  if (n.editable) put("editable", true)
                  if (n.scrollable) put("scrollable", true)
                  put(
                    "bounds",
                    buildJsonArray {
                      add(kotlinx.serialization.json.JsonPrimitive(n.left))
                      add(kotlinx.serialization.json.JsonPrimitive(n.top))
                      add(kotlinx.serialization.json.JsonPrimitive(n.right))
                      add(kotlinx.serialization.json.JsonPrimitive(n.bottom))
                    },
                  )
                  put(
                    "center",
                    buildJsonArray {
                      add(kotlinx.serialization.json.JsonPrimitive(n.centerX))
                      add(kotlinx.serialization.json.JsonPrimitive(n.centerY))
                    },
                  )
                },
              )
            }
          },
        )
      }
      return jsonObj.toString()
    }

    /**
     * Parses raw `uiautomator dump` XML via [XmlPullParser] (with regex attribute fallback on
     * stubbed JVM unit test environments), enforces [SecurityGateway.isRestrictedPackage], and
     * builds a 50-node capped [CompactUiTree].
     */
    fun parseUiHierarchyXml(
      rawXml: String,
      selfPackageName: String = "com.nexwork.edith",
    ): CompactUiTree {
      val trimmed = rawXml.trim()
      if (trimmed.isEmpty()) {
        clearNodeCache()
        return CompactUiTree(
          packageName = "",
          blockedReason = "Empty UI hierarchy dump from Shizuku uiautomator",
        )
      }

      val snapshots = extractRawSnapshots(trimmed)
      val appPkg = snapshots.firstOrNull {
        it.packageName.isNotBlank() && !it.packageName.equals("com.android.systemui", ignoreCase = true)
      }?.packageName ?: snapshots.firstOrNull { it.packageName.isNotBlank() }?.packageName.orEmpty()

      if (appPkg.isNotBlank() && SecurityGateway.isRestrictedPackage(appPkg, selfPackageName)) {
        clearNodeCache()
        lastForegroundPkg = appPkg
        return CompactUiTree(
          packageName = appPkg,
          blockedReason = "Security firewall blocked reading restricted package: $appPkg",
        )
      }

      val filteredRaw = snapshots.filter { snap ->
        val pkg = snap.packageName
        pkg.isBlank() ||
          (!pkg.equals("com.android.systemui", ignoreCase = true) &&
            !SecurityGateway.isRestrictedPackage(pkg, selfPackageName))
      }

      val compactNodes = filterAndAssignNodes(filteredRaw)
      cacheNodes(appPkg, compactNodes)
      val formatted = formatCompactTree(appPkg, "", compactNodes)
      return CompactUiTree(
        packageName = appPkg,
        windowTitle = "",
        nodes = compactNodes,
        formattedText = formatted,
      )
    }

    private fun extractRawSnapshots(xml: String): List<RawNodeSnapshot> {
      val parsedViaPull = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        val out = ArrayList<RawNodeSnapshot>(64)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
          if (event == XmlPullParser.START_TAG && parser.name == "node") {
            val visible = parser.getAttributeValue(null, "visible-to-user")
            if (visible == null || visible.equals("true", ignoreCase = true)) {
              buildSnapshotFromAttrs { attr -> parser.getAttributeValue(null, attr) }?.let { out.add(it) }
            }
          }
          event = parser.next()
        }
        out
      }.getOrNull()

      if (parsedViaPull != null) return parsedViaPull

      // Fallback for JVM unit tests where android.jar stubs XmlPullParserFactory
      val out = ArrayList<RawNodeSnapshot>(64)
      for (match in NODE_TAG_REGEX.findAll(xml)) {
        val attrBody = match.groupValues[1]
        val attrs = HashMap<String, String>()
        for (a in ATTR_REGEX.findAll(attrBody)) {
          attrs[a.groupValues[1]] = a.groupValues[2]
        }
        val visible = attrs["visible-to-user"]
        if (visible == null || visible.equals("true", ignoreCase = true)) {
          buildSnapshotFromAttrs { key -> attrs[key] }?.let { out.add(it) }
        }
      }
      return out
    }

    private fun buildSnapshotFromAttrs(attrOf: (String) -> String?): RawNodeSnapshot? {
      val boundsStr = attrOf("bounds").orEmpty()
      val bounds = parseBounds(boundsStr) ?: return null
      val pkg = attrOf("package")?.trim().orEmpty()
      val fullClass = attrOf("class")?.trim().orEmpty()
      val shortClass = fullClass.substringAfterLast('.').ifEmpty { "View" }
      val resId = attrOf("resource-id")?.substringAfter(":id/")?.trim().orEmpty()
      val isPassword = attrOf("password").equals("true", ignoreCase = true)
      val text = if (isPassword) "[REDACTED]" else decodeXmlEntities(attrOf("text").orEmpty().trim())
      val desc = if (isPassword) "[REDACTED]" else decodeXmlEntities(attrOf("content-desc").orEmpty().trim())
      val clickable = attrOf("clickable").equals("true", ignoreCase = true)
      val scrollable = attrOf("scrollable").equals("true", ignoreCase = true)
      val editable = attrOf("editable").equals("true", ignoreCase = true) ||
        shortClass.contains("EditText", ignoreCase = true) ||
        shortClass.contains("AutoCompleteTextView", ignoreCase = true)

      return RawNodeSnapshot(
        packageName = pkg,
        className = shortClass,
        viewId = resId,
        text = text,
        contentDesc = desc,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        left = bounds[0],
        top = bounds[1],
        right = bounds[2],
        bottom = bounds[3],
      )
    }

    private fun decodeXmlEntities(s: String): String {
      if (!s.contains('&')) return s
      return s
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
    }

    suspend fun dumpCompactUiTree(context: Context): CompactUiTree =
      ShizukuControlManager(context).dumpCompactUiTree()

    suspend fun clickNode(context: Context, id: Int): Result<String> =
      ShizukuControlManager(context).clickNode(id)

    suspend fun tap(context: Context, x: Int, y: Int): Result<String> =
      ShizukuControlManager(context).tap(x, y)

    suspend fun swipe(context: Context, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 240): Result<String> =
      ShizukuControlManager(context).swipe(x1, y1, x2, y2, durationMs)

    suspend fun inputText(context: Context, id: Int, text: String): Result<String> =
      ShizukuControlManager(context).inputText(id, text)

    suspend fun pressKey(context: Context, keyCode: Int): Result<String> =
      ShizukuControlManager(context).pressKey(keyCode)
  }
}
