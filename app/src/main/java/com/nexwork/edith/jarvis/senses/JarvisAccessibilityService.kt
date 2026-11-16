package com.nexwork.edith.jarvis.senses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.SparseArray
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.nexwork.edith.jarvis.core.CompactUiNode
import com.nexwork.edith.jarvis.core.CompactUiTree
import com.nexwork.edith.jarvis.core.JarvisRuntime
import com.nexwork.edith.jarvis.security.SecurityGateway
import kotlin.math.hypot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-demand Accessibility Hands service configured with `canRetrieveWindowContent="true"` and
 * `canPerformGestures="true"` WITHOUT `typeAllMask` so event monitoring stays completely idle
 * until explicitly invoked by PlaybookExecutor or PlannerClient.
 */
class JarvisAccessibilityService : AccessibilityService() {

  private val nodeLock = Any()
  private val nodeMap = SparseArray<AccessibilityNodeInfo>()
  private val compactMetaMap = SparseArray<CompactUiNode>()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  @Volatile
  private var overlayObserverJob: Job? = null

  @Volatile
  private var activeGestureDeferred: CompletableDeferred<Boolean>? = null

  @Volatile
  private var volumeAbortPressed: Boolean = false

  private var emergencyOverlayView: TextView? = null
  private var emergencyOverlayParams: WindowManager.LayoutParams? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    activeInstance = this
    _isConnected.value = true
    runCatching {
      serviceInfo = serviceInfo?.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
      }
    }
    startOverlayObserver()
    JarvisRuntime.refreshEnvironmentStatus(applicationContext)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // Intentionally idle until invoked on-demand via dumpCompactUiTree() / UI actions.
  }

  override fun onInterrupt() {
    abortActiveAutomation(bounceHome = false)
  }

  override fun onKeyEvent(event: KeyEvent?): Boolean {
    event ?: return super.onKeyEvent(event)
    if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
      val active = JarvisRuntime.isAutomationActive.value
      if (shouldConsumePhysicalAbortKey(event.keyCode, event.action, event.repeatCount, active)) {
        volumeAbortPressed = true
        JarvisRuntime.emergencyStop(
          context = applicationContext,
          reason = "Volume-Down physical stop",
          bounceHome = true,
        )
        return true
      }
      if (event.action == KeyEvent.ACTION_DOWN && volumeAbortPressed) {
        return true
      }
      if (event.action == KeyEvent.ACTION_UP && volumeAbortPressed) {
        volumeAbortPressed = false
        return true
      }
    }
    return super.onKeyEvent(event)
  }

  override fun onUnbind(intent: Intent?): Boolean {
    volumeAbortPressed = false
    overlayObserverJob?.cancel()
    overlayObserverJob = null
    hideEmergencyOverlay()
    clearNodeCache()
    if (activeInstance === this) activeInstance = null
    _isConnected.value = false
    JarvisRuntime.refreshEnvironmentStatus(applicationContext)
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    volumeAbortPressed = false
    overlayObserverJob?.cancel()
    overlayObserverJob = null
    hideEmergencyOverlay()
    clearNodeCache()
    if (activeInstance === this) activeInstance = null
    _isConnected.value = false
    super.onDestroy()
  }

  fun abortActiveAutomation(bounceHome: Boolean) {
    activeGestureDeferred?.takeIf { !it.isCompleted }?.complete(false)
    activeGestureDeferred = null
    clearNodeCache()
    mainHandler.post {
      hideEmergencyOverlay()
      triggerStopVibration()
      if (bounceHome) {
        val fg = currentForegroundPackage()
        if (fg.isNotBlank() && fg != applicationContext.packageName) {
          runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
        }
      }
    }
  }

  private fun startOverlayObserver() {
    overlayObserverJob?.cancel()
    overlayObserverJob = serviceScope.launch {
      JarvisRuntime.isAutomationActive.collect { active ->
        if (active) {
          showEmergencyOverlay()
        } else {
          hideEmergencyOverlay()
        }
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun showEmergencyOverlay() {
    if (emergencyOverlayView != null) return
    runCatching {
      val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
      val density = resources.displayMetrics.density
      val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
        .takeIf { it > 0 }
        ?.let { resources.getDimensionPixelSize(it) }
        ?: (28f * density).toInt()

      val pillBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 20f * density
        setColor(Color.parseColor("#EB16090D"))
        setStroke((1.4f * density).toInt().coerceAtLeast(1), Color.parseColor("#FFFF4D5E"))
      }

      val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
      val pill = TextView(this).apply {
        text = "■ STOP"
        setTextColor(Color.parseColor("#FFF8FAFC"))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        background = pillBg
        elevation = 10f * density
        val hPad = (14f * density).toInt()
        val vPad = (9f * density).toInt()
        setPadding(hPad, vPad, hPad, vPad)
      }

      val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = (14f * density).toInt()
        y = statusBarHeight + (10f * density).toInt()
      }

      var startX = 0
      var startY = 0
      var downRawX = 0f
      var downRawY = 0f
      var dragged = false

      pill.setOnTouchListener { _, ev ->
        when (ev.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            startX = params.x
            startY = params.y
            downRawX = ev.rawX
            downRawY = ev.rawY
            dragged = false
            true
          }
          MotionEvent.ACTION_MOVE -> {
            val dx = ev.rawX - downRawX
            val dy = ev.rawY - downRawY
            if (!dragged && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
              dragged = true
            }
            if (dragged) {
              params.x = (startX - dx.toInt()).coerceAtLeast(0)
              params.y = (startY + dy.toInt()).coerceAtLeast(statusBarHeight)
              runCatching { wm.updateViewLayout(pill, params) }
            }
            true
          }
          MotionEvent.ACTION_UP -> {
            if (!dragged) {
              JarvisRuntime.emergencyStop(
                context = applicationContext,
                reason = "Floating STOP pill tapped",
                bounceHome = true,
              )
            }
            true
          }
          else -> false
        }
      }

      wm.addView(pill, params)
      emergencyOverlayView = pill
      emergencyOverlayParams = params
    }
  }

  private fun hideEmergencyOverlay() {
    val view = emergencyOverlayView ?: return
    emergencyOverlayView = null
    emergencyOverlayParams = null
    runCatching {
      val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
      wm.removeViewImmediate(view)
    }
  }

  @SuppressLint("MissingPermission")
  @Suppress("DEPRECATION")
  private fun triggerStopVibration() {
    runCatching {
      val effect = VibrationEffect.createWaveform(longArrayOf(0L, 40L, 50L, 60L), -1)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator?.vibrate(effect)
      } else {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vib?.vibrate(effect)
      }
    }
  }

  private fun clearNodeCache() {
    synchronized(nodeLock) {
      nodeMap.clear()
      compactMetaMap.clear()
    }
  }

  private fun resolveActiveRoot(): AccessibilityNodeInfo? {
    rootInActiveWindow?.let { return it }
    val appWindow = windows?.firstOrNull { w ->
      w.isActive && w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
    } ?: windows?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
    return appWindow?.root
  }

  fun currentForegroundPackage(): String {
    return runCatching { resolveActiveRoot()?.packageName?.toString().orEmpty() }.getOrDefault("")
  }

  fun dumpCompactUiTree(): CompactUiTree {
    val root = resolveActiveRoot() ?: return CompactUiTree(
      packageName = "",
      blockedReason = "No active application window available",
    )
    val pkg = root.packageName?.toString().orEmpty()
    if (SecurityGateway.isRestrictedPackage(pkg, applicationContext.packageName)) {
      clearNodeCache()
      return CompactUiTree(
        packageName = pkg,
        blockedReason = "Security firewall blocked reading restricted package: $pkg",
      )
    }

    val rawNodes = mutableListOf<Pair<AccessibilityNodeInfo, RawNodeSnapshot>>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.addLast(root)
    val rect = Rect()

    while (queue.isNotEmpty() && rawNodes.size < MAX_SCAN_NODES) {
      val node = queue.removeFirst()
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let { child ->
          if (queue.size + rawNodes.size < MAX_SCAN_NODES) {
            queue.addLast(child)
          }
        }
      }

      if (!node.isVisibleToUser) continue
      node.getBoundsInScreen(rect)
      val viewId = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
      val rawText = if (node.isPassword) "[REDACTED]" else node.text?.toString().orEmpty()
      val rawDesc = if (node.isPassword) "[REDACTED]" else node.contentDescription?.toString().orEmpty()

      val snap = RawNodeSnapshot(
        className = node.className?.toString()?.substringAfterLast('.').orEmpty().ifEmpty { "View" },
        viewId = viewId,
        text = rawText.trim(),
        contentDesc = rawDesc.trim(),
        clickable = node.isClickable,
        editable = node.isEditable,
        scrollable = node.isScrollable,
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
      )
      if (shouldKeepNode(snap)) {
        rawNodes.add(node to snap)
        if (rawNodes.size >= MAX_OUTPUT_NODES) break
      }
    }

    val compactList = ArrayList<CompactUiNode>(rawNodes.size)
    synchronized(nodeLock) {
      nodeMap.clear()
      compactMetaMap.clear()
      rawNodes.forEachIndexed { idx, (a11yNode, snap) ->
        val id = idx + 1
        val compact = snap.toCompactNode(id)
        nodeMap.put(id, a11yNode)
        compactMetaMap.put(id, compact)
        compactList.add(compact)
      }
    }

    val windowTitle = root.window?.title?.toString().orEmpty()
    val formatted = formatCompactTree(pkg, windowTitle, compactList)
    return CompactUiTree(
      packageName = pkg,
      windowTitle = windowTitle,
      nodes = compactList,
      formattedText = formatted,
    )
  }

  fun getCachedNodeLabel(id: Int): String {
    val meta = synchronized(nodeLock) { compactMetaMap.get(id) } ?: return ""
    return listOf(meta.text, meta.contentDesc, meta.viewId).firstOrNull { it.isNotBlank() }.orEmpty()
  }

  suspend fun clickNode(id: Int): Result<String> {
    val fgPkg = currentForegroundPackage()
    if (SecurityGateway.isRestrictedPackage(fgPkg, applicationContext.packageName)) {
      clearNodeCache()
      return Result.failure(SecurityException("Blocked click inside restricted package: $fgPkg"))
    }

    val (node, meta) = synchronized(nodeLock) { nodeMap.get(id) to compactMetaMap.get(id) }
    if (node == null && meta == null) {
      return Result.failure(IllegalArgumentException("UI node id=$id not found in latest tree"))
    }

    // Climb clickable parents first (up to 6 hops)
    var cur: AccessibilityNodeInfo? = node
    var hops = 0
    while (cur != null && hops < 6) {
      if (cur.isClickable && cur.isEnabled) {
        val clicked = runCatching { cur.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        if (clicked) {
          return Result.success("Clicked node id=$id (hops=$hops label=${meta?.text.orEmpty()})")
        }
      }
      cur = cur.parent
      hops++
    }

    // Coordinate fallback via dispatchGesture
    val rect = Rect()
    if (node != null) {
      node.getBoundsInScreen(rect)
    } else if (meta != null) {
      rect.set(meta.left, meta.top, meta.right, meta.bottom)
    }
    if (rect.width() <= 0 || rect.height() <= 0) {
      return Result.failure(IllegalStateException("Node id=$id not clickable and has empty bounds"))
    }
    val cx = rect.exactCenterX().coerceAtLeast(1f)
    val cy = rect.exactCenterY().coerceAtLeast(1f)
    val tapped = dispatchTapGesture(cx, cy)
    return if (tapped) {
      Result.success("Gesture-tapped node id=$id at (${cx.toInt()},${cy.toInt()})")
    } else {
      Result.failure(IllegalStateException("Gesture tap failed for node id=$id at (${cx.toInt()},${cy.toInt()})"))
    }
  }

  fun inputText(id: Int, text: String): Result<String> {
    val fgPkg = currentForegroundPackage()
    if (SecurityGateway.isRestrictedPackage(fgPkg, applicationContext.packageName)) {
      clearNodeCache()
      return Result.failure(SecurityException("Blocked input inside restricted package: $fgPkg"))
    }
    SecurityGateway.inspectOutboundText(text)?.let { violation ->
      return Result.failure(SecurityException(violation))
    }

    val startNode = synchronized(nodeLock) { nodeMap.get(id) }
      ?: return Result.failure(IllegalArgumentException("UI node id=$id not found in latest tree"))

    val target = findEditableNode(startNode)
      ?: return Result.failure(IllegalStateException("No editable target found for node id=$id"))

    runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
    runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }

    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    val ok = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
    return if (ok) {
      Result.success("Injected ${text.length} chars into node id=$id")
    } else {
      Result.failure(IllegalStateException("ACTION_SET_TEXT rejected on node id=$id"))
    }
  }

  suspend fun scrollNode(id: Int?, forward: Boolean): Result<String> {
    val fgPkg = currentForegroundPackage()
    if (SecurityGateway.isRestrictedPackage(fgPkg, applicationContext.packageName)) {
      clearNodeCache()
      return Result.failure(SecurityException("Blocked scroll inside restricted package: $fgPkg"))
    }

    val candidate: AccessibilityNodeInfo? = synchronized(nodeLock) {
      if (id != null) {
        var cur: AccessibilityNodeInfo? = nodeMap.get(id)
        var hops = 0
        while (cur != null && hops < 6 && !cur.isScrollable) {
          cur = cur.parent
          hops++
        }
        cur?.takeIf { it.isScrollable }
      } else {
        var found: AccessibilityNodeInfo? = null
        for (i in 0 until nodeMap.size()) {
          val n = nodeMap.valueAt(i)
          if (n != null && n.isScrollable) {
            found = n
            break
          }
        }
        found
      }
    }

    val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    if (candidate != null) {
      val scrolled = runCatching { candidate.performAction(action) }.getOrDefault(false)
      if (scrolled) {
        return Result.success("Scrolled node (${if (forward) "forward" else "backward"})")
      }
    }

    val dm = resources.displayMetrics
    val cx = dm.widthPixels * 0.5f
    val startY = dm.heightPixels * if (forward) 0.72f else 0.30f
    val endY = dm.heightPixels * if (forward) 0.30f else 0.72f
    val swiped = dispatchSwipeGesture(cx, startY, cx, endY, 220L)
    return if (swiped) {
      Result.success("Gesture-scrolled (${if (forward) "forward" else "backward"})")
    } else {
      Result.failure(IllegalStateException("Scroll gesture failed"))
    }
  }

  private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isEditable) return node
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      if (child.isEditable) return child
    }
    var cur = node.parent
    var hops = 0
    while (cur != null && hops < 2) {
      if (cur.isEditable) return cur
      cur = cur.parent
      hops++
    }
    return node
  }

  private suspend fun dispatchTapGesture(x: Float, y: Float): Boolean {
    val path = Path().apply { moveTo(x, y) }
    val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    return dispatchGestureAndAwait(gesture, 1_500L)
  }

  private suspend fun dispatchSwipeGesture(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    durationMs: Long,
  ): Boolean {
    val path = Path().apply {
      moveTo(startX, startY)
      lineTo(endX, endY)
    }
    val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    return dispatchGestureAndAwait(gesture, 1_800L)
  }

  private suspend fun dispatchGestureAndAwait(gesture: GestureDescription, timeoutMs: Long): Boolean {
    val deferred = CompletableDeferred<Boolean>()
    activeGestureDeferred = deferred
    val dispatched = dispatchGesture(
      gesture,
      object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
          if (!deferred.isCompleted) deferred.complete(true)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
          if (!deferred.isCompleted) deferred.complete(false)
        }
      },
      null,
    )
    if (!dispatched) {
      if (activeGestureDeferred === deferred) activeGestureDeferred = null
      return false
    }
    return try {
      withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
    } finally {
      if (activeGestureDeferred === deferred) activeGestureDeferred = null
    }
  }

  internal data class RawNodeSnapshot(
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
    private const val MAX_SCAN_NODES = 420
    private const val MAX_OUTPUT_NODES = 120

    @Volatile
    private var activeInstance: JarvisAccessibilityService? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun instance(): JarvisAccessibilityService? = activeInstance

    /**
     * Pure gate deciding when a hardware key event triggers immediate automation abort.
     * Consumes Volume-Down ONLY while automation is active so normal media volume stays intact.
     */
    internal fun shouldConsumePhysicalAbortKey(
      keyCode: Int,
      action: Int,
      repeatCount: Int,
      isAutomationActive: Boolean,
    ): Boolean {
      if (!isAutomationActive) return false
      return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && action == KeyEvent.ACTION_DOWN && repeatCount == 0
    }

    fun isEnabledInSettings(context: Context): Boolean {
      return runCatching {
        val enabledServices = Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val target = ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
        val shortTarget = ComponentName(context, JarvisAccessibilityService::class.java).flattenToShortString()
        enabledServices.split(':').any { entry ->
          entry.equals(target, ignoreCase = true) || entry.equals(shortTarget, ignoreCase = true)
        }
      }.getOrDefault(false)
    }

    fun openAccessibilitySettings(context: Context) {
      runCatching {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
      }
    }

    internal fun shouldKeepNode(snap: RawNodeSnapshot): Boolean {
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
    ): String = buildString {
      append("pkg=").append(packageName)
      if (windowTitle.isNotBlank()) append(" title=\"").append(windowTitle.take(60)).append('"')
      append(" nodes=").append(nodes.size)
      for (n in nodes) {
        append('\n')
        append('[').append(n.id).append("] ").append(n.className)
        if (n.viewId.isNotBlank()) append(" id=").append(n.viewId)
        if (n.text.isNotBlank()) append(" text=\"").append(n.text.replace('\n', ' ')).append('"')
        if (n.contentDesc.isNotBlank()) append(" desc=\"").append(n.contentDesc.replace('\n', ' ')).append('"')
        val flags = buildList(3) {
          if (n.clickable) add("clickable")
          if (n.editable) add("editable")
          if (n.scrollable) add("scrollable")
        }
        if (flags.isNotEmpty()) append(' ').append(flags.joinToString(","))
        append(" [").append(n.left).append(',').append(n.top).append(',').append(n.right).append(',').append(n.bottom).append(']')
      }
    }
  }
}
