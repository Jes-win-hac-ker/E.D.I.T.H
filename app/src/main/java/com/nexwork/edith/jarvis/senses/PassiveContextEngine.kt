package com.nexwork.edith.jarvis.senses

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.nexwork.edith.jarvis.core.AmbientContext
import com.nexwork.edith.jarvis.core.MotionState
import com.nexwork.edith.jarvis.core.PlaceTag
import com.nexwork.edith.jarvis.core.SavedPlaceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ContextSignal {
  data class StateCommitted(
    val previous: AmbientContext,
    val current: AmbientContext,
    val atMs: Long,
  ) : ContextSignal

  data class ScreenUnlocked(
    val context: AmbientContext,
    val atMs: Long,
  ) : ContextSignal
}

/**
 * Event-driven zero-polling ambient state engine combining:
 * - [ConnectivityManager.NetworkCallback] for Wi-Fi SSID transitions
 * - Dynamic [BroadcastReceiver] for Bluetooth ACL, Screen/User Present, Battery, and Activity Transitions
 * - [ActivityRecognition] transition API (`IN_VEHICLE`, `WALKING`, `STILL`)
 * - A 45-second transition debounce state machine emitting an immutable [StateFlow]<[AmbientContext]>.
 */
class PassiveContextEngine(
  private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val clockMs: () -> Long = System::currentTimeMillis,
) {

  private val stateLock = Any()
  private var savedPlacesByNormSsid: Map<String, SavedPlaceProfile> = emptyMap()
  private val connectedBtAddresses = HashSet<String>()

  private var rawMotion: MotionState = MotionState.UNKNOWN
  private var rawWifiSsid: String? = null
  private var pendingDebounceJob: Job? = null

  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var dynamicReceiver: BroadcastReceiver? = null
  private var activityPendingIntent: PendingIntent? = null
  private var startedContext: Context? = null

  private val _contextState = MutableStateFlow(AmbientContext(updatedAtMs = clockMs()))
  val contextState: StateFlow<AmbientContext> = _contextState.asStateFlow()

  private val _signals = MutableSharedFlow<ContextSignal>(extraBufferCapacity = 32)
  val signals: SharedFlow<ContextSignal> = _signals.asSharedFlow()

  fun start(context: Context, initialPlaces: List<SavedPlaceProfile> = emptyList()) {
    val appCtx = context.applicationContext
    updateSavedPlaces(initialPlaces)
    synchronized(stateLock) {
      if (startedContext != null) return
      startedContext = appCtx
    }
    registerWifiCallback(appCtx)
    registerDynamicReceiver(appCtx)
    registerActivityTransitions(appCtx)
    refreshInitialEnvironment(appCtx)
  }

  fun stop() {
    val appCtx = synchronized(stateLock) {
      val ctx = startedContext
      startedContext = null
      pendingDebounceJob?.cancel()
      pendingDebounceJob = null
      ctx
    } ?: return

    networkCallback?.let { cb ->
      runCatching {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.unregisterNetworkCallback(cb)
      }
    }
    networkCallback = null

    dynamicReceiver?.let { rx ->
      runCatching { appCtx.unregisterReceiver(rx) }
    }
    dynamicReceiver = null

    activityPendingIntent?.let { pi ->
      runCatching { ActivityRecognition.getClient(appCtx).removeActivityTransitionUpdates(pi) }
    }
    activityPendingIntent = null
  }

  fun refreshSensorsAfterPermissionGrant(context: Context) {
    val appCtx = context.applicationContext
    if (startedContext != null) {
      networkCallback?.let { cb ->
        runCatching {
          val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
          cm?.unregisterNetworkCallback(cb)
        }
      }
      networkCallback = null
      registerWifiCallback(appCtx)
      registerActivityTransitions(appCtx)
      refreshInitialEnvironment(appCtx)
    }
  }

  fun updateSavedPlaces(places: List<SavedPlaceProfile>) {
    val mapped = places.associateBy { it.ssidNormalized.trim().lowercase() }
    val now = clockMs()
    synchronized(stateLock) {
      savedPlacesByNormSsid = mapped
      val cur = _contextState.value
      val currentSsid = rawWifiSsid ?: cur.wifiSsid
      val (tag, quiet) = resolvePlaceForSsid(currentSsid, mapped)
      val awaySince = computeAwayDwellSince(cur.awayDwellSinceMs, tag, now)
      val next = cur.copy(
        wifiSsid = currentSsid,
        placeTag = tag,
        focusQuietActive = quiet,
        awayDwellSinceMs = awaySince,
        updatedAtMs = now,
      )
      _contextState.value = next
    }
  }

  fun onRawMotionTransition(motion: MotionState, immediateForTest: Boolean = false) {
    synchronized(stateLock) {
      rawMotion = motion
    }
    if (immediateForTest || debounceMs <= 0L) {
      commitDebouncedState()
    } else {
      scheduleDebouncedCommit()
    }
  }

  fun onRawWifiChanged(ssid: String?, immediate: Boolean = false) {
    val clean = sanitizeSsid(ssid)
    synchronized(stateLock) {
      rawWifiSsid = clean
    }
    if (immediate || debounceMs <= 0L) {
      commitDebouncedState()
    } else {
      scheduleDebouncedCommit()
    }
  }

  fun onBluetoothAclChanged(deviceKey: String?, connected: Boolean) {
    val key = deviceKey?.takeIf { it.isNotBlank() } ?: "bt_anon"
    val now = clockMs()
    synchronized(stateLock) {
      if (connected) connectedBtAddresses.add(key) else connectedBtAddresses.remove(key)
      val count = connectedBtAddresses.size
      _contextState.update { it.copy(bluetoothConnectedCount = count, updatedAtMs = now) }
    }
  }

  fun onBatteryUpdated(batteryPct: Int, isCharging: Boolean) {
    val now = clockMs()
    _contextState.update {
      it.copy(
        batteryPct = batteryPct.coerceIn(0, 100),
        isCharging = isCharging,
        updatedAtMs = now,
      )
    }
  }

  fun onScreenInteractiveChanged(interactive: Boolean, isUserUnlock: Boolean = false) {
    val now = clockMs()
    val updated = _contextState.updateAndGetCompat { cur ->
      cur.copy(
        isScreenInteractive = interactive,
        lastUnlockMs = if (isUserUnlock) now else cur.lastUnlockMs,
        updatedAtMs = now,
      )
    }
    if (isUserUnlock) {
      _signals.tryEmit(ContextSignal.ScreenUnlocked(updated, now))
    }
  }

  private fun scheduleDebouncedCommit() {
    synchronized(stateLock) {
      pendingDebounceJob?.cancel()
      pendingDebounceJob = scope.launch {
        delay(debounceMs)
        commitDebouncedState()
      }
    }
  }

  internal fun commitDebouncedState() {
    val now = clockMs()
    var prevSnapshot: AmbientContext? = null
    var nextSnapshot: AmbientContext? = null

    synchronized(stateLock) {
      val prev = _contextState.value
      val nextMotion = if (rawMotion == MotionState.UNKNOWN) prev.motionState else rawMotion
      val prevMotion = if (nextMotion != prev.motionState) prev.motionState else prev.previousMotionState
      val motionChanged = nextMotion != prev.motionState
      val nextSsid = rawWifiSsid
      val (nextTag, nextQuiet) = resolvePlaceForSsid(nextSsid, savedPlacesByNormSsid)
      val nextAwaySince = computeAwayDwellSince(prev.awayDwellSinceMs, nextTag, now)

      val next = prev.copy(
        motionState = nextMotion,
        previousMotionState = prevMotion,
        wifiSsid = nextSsid,
        placeTag = nextTag,
        focusQuietActive = nextQuiet,
        motionSinceMs = if (motionChanged || prev.motionSinceMs == 0L) now else prev.motionSinceMs,
        awayDwellSinceMs = nextAwaySince,
        updatedAtMs = now,
      )
      _contextState.value = next
      prevSnapshot = prev
      nextSnapshot = next
    }

    val p = prevSnapshot
    val n = nextSnapshot
    if (p != null && n != null) {
      _signals.tryEmit(ContextSignal.StateCommitted(p, n, now))
    }
  }

  private fun registerWifiCallback(appCtx: Context) {
    val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
          val ssid = extractSsidFromCapabilities(appCtx, caps)
          onRawWifiChanged(ssid, immediate = rawWifiSsid == null && _contextState.value.wifiSsid == null)
        }

        override fun onLost(network: Network) {
          onRawWifiChanged(null)
        }
      }
    } else {
      object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
          val ssid = extractSsidFromCapabilities(appCtx, caps)
          onRawWifiChanged(ssid, immediate = rawWifiSsid == null && _contextState.value.wifiSsid == null)
        }

        override fun onLost(network: Network) {
          onRawWifiChanged(null)
        }
      }
    }

    val req = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .build()
    runCatching {
      cm.registerNetworkCallback(req, callback)
      networkCallback = callback
    }
  }

  @SuppressLint("MissingPermission")
  @Suppress("DEPRECATION")
  private fun extractSsidFromCapabilities(appCtx: Context, caps: NetworkCapabilities): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val info = caps.transportInfo as? WifiInfo
      val fromCaps = sanitizeSsid(info?.ssid)
      if (fromCaps != null) return fromCaps
    }
    return readWifiSsidLegacy(appCtx)
  }

  @SuppressLint("MissingPermission")
  @Suppress("DEPRECATION")
  private fun readWifiSsidLegacy(appCtx: Context): String? {
    val fine = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    if (!fine) return null
    return runCatching {
      val wm = appCtx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      sanitizeSsid(wm?.connectionInfo?.ssid)
    }.getOrNull()
  }

  private fun registerDynamicReceiver(appCtx: Context) {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        when (intent?.action) {
          BluetoothDevice.ACTION_ACL_CONNECTED -> {
            val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
              ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_CONNECT) !=
              PackageManager.PERMISSION_GRANTED
            ) {
              "bt_device"
            } else {
              @Suppress("DEPRECATION")
              intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address
            }
            onBluetoothAclChanged(addr, connected = true)
          }

          BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
            val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
              ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_CONNECT) !=
              PackageManager.PERMISSION_GRANTED
            ) {
              "bt_device"
            } else {
              @Suppress("DEPRECATION")
              intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address
            }
            onBluetoothAclChanged(addr, connected = false)
          }

          Intent.ACTION_USER_PRESENT -> onScreenInteractiveChanged(interactive = true, isUserUnlock = true)
          Intent.ACTION_SCREEN_OFF -> onScreenInteractiveChanged(interactive = false, isUserUnlock = false)
          Intent.ACTION_BATTERY_CHANGED -> {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0) ((level * 100f) / scale).toInt() else _contextState.value.batteryPct
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
              status == BatteryManager.BATTERY_STATUS_FULL
            onBatteryUpdated(pct, charging)
          }

          ACTION_ACTIVITY_TRANSITION -> {
            if (ActivityTransitionResult.hasResult(intent)) {
              val result = ActivityTransitionResult.extractResult(intent) ?: return
              for (event in result.transitionEvents) {
                if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                  val mapped = mapDetectedActivity(event.activityType)
                  if (mapped != MotionState.UNKNOWN) {
                    onRawMotionTransition(mapped)
                  }
                }
              }
            }
          }
        }
      }
    }

    val filter = IntentFilter().apply {
      addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
      addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
      addAction(Intent.ACTION_USER_PRESENT)
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_BATTERY_CHANGED)
      addAction(ACTION_ACTIVITY_TRANSITION)
    }
    ContextCompat.registerReceiver(appCtx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    dynamicReceiver = receiver
  }

  @SuppressLint("MissingPermission")
  private fun registerActivityTransitions(appCtx: Context) {
    if (!hasActivityRecognitionPermission(appCtx)) return
    if (activityPendingIntent != null) return

    val transitions = listOf(
      DetectedActivity.IN_VEHICLE,
      DetectedActivity.WALKING,
      DetectedActivity.STILL,
    ).map { activityType ->
      ActivityTransition.Builder()
        .setActivityType(activityType)
        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        .build()
    }
    val request = ActivityTransitionRequest(transitions)
    val intent = Intent(ACTION_ACTIVITY_TRANSITION).setPackage(appCtx.packageName)
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    val pi = PendingIntent.getBroadcast(appCtx, REQUEST_ACTIVITY_TRANSITION, intent, flags)
    activityPendingIntent = pi
    runCatching {
      ActivityRecognition.getClient(appCtx).requestActivityTransitionUpdates(request, pi)
    }
  }

  private fun refreshInitialEnvironment(appCtx: Context) {
    val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val activeCaps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
      val initialSsid = extractSsidFromCapabilities(appCtx, activeCaps)
      onRawWifiChanged(initialSsid, immediate = true)
    } else {
      onRawWifiChanged(null, immediate = true)
    }
    val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val interactive = pm?.isInteractive ?: true
    _contextState.update { it.copy(isScreenInteractive = interactive) }
  }

  private inline fun <T> MutableStateFlow<T>.updateAndGetCompat(transform: (T) -> T): T {
    while (true) {
      val prev = value
      val next = transform(prev)
      if (compareAndSet(prev, next)) return next
    }
  }

  companion object {
    const val DEFAULT_DEBOUNCE_MS = 45_000L
    const val ACTION_ACTIVITY_TRANSITION = "com.nexwork.edith.jarvis.action.ACTIVITY_TRANSITION"
    private const val REQUEST_ACTIVITY_TRANSITION = 4192

    fun hasActivityRecognitionPermission(context: Context): Boolean {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
          PackageManager.PERMISSION_GRANTED
      } else {
        true
      }
    }

    internal fun mapDetectedActivity(activityType: Int): MotionState = when (activityType) {
      DetectedActivity.IN_VEHICLE -> MotionState.IN_VEHICLE
      DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> MotionState.WALKING
      DetectedActivity.STILL -> MotionState.STILL
      else -> MotionState.UNKNOWN
    }

    internal fun sanitizeSsid(raw: String?): String? {
      if (raw.isNullOrBlank()) return null
      val cleaned = raw.trim().removePrefix("\"").removeSuffix("\"").trim()
      if (cleaned.isEmpty() || cleaned.equals("<unknown ssid>", ignoreCase = true)) return null
      return cleaned
    }

    internal fun resolvePlaceForSsid(
      ssid: String?,
      savedPlacesByNormSsid: Map<String, SavedPlaceProfile>,
    ): Pair<PlaceTag, Boolean> {
      val norm = sanitizeSsid(ssid)?.lowercase() ?: return PlaceTag.NONE to false
      val matched = savedPlacesByNormSsid[norm] ?: return PlaceTag.NONE to false
      return matched.placeTag to matched.autoFocusQuiet
    }

    internal fun computeAwayDwellSince(
      existingAwaySinceMs: Long?,
      placeTag: PlaceTag,
      nowMs: Long,
    ): Long? {
      return when (placeTag) {
        PlaceTag.HOME, PlaceTag.CAMPUS -> null
        PlaceTag.NONE -> existingAwaySinceMs ?: nowMs
      }
    }
  }
}
