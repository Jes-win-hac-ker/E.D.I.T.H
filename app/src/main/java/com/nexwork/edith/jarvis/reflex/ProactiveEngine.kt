package com.nexwork.edith.jarvis.reflex

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.nexwork.edith.jarvis.core.AmbientContext
import com.nexwork.edith.jarvis.core.MotionState
import com.nexwork.edith.jarvis.core.PlaceTag
import com.nexwork.edith.jarvis.core.ProactiveSuggestion
import com.nexwork.edith.jarvis.core.UpcomingDestination
import com.nexwork.edith.jarvis.senses.ContextSignal
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface DestinationStore {
  fun getActiveUpcomingDestinations(nowMs: Long): List<UpcomingDestination>
  fun markDestinationUsed(id: Long)
}

fun interface OneShotLocationVerifier {
  suspend fun confirmAwayVenueLabel(): String?
}

/**
 * Evaluates event-driven [ContextSignal] transitions from [PassiveContextEngine] against
 * destination TTLs, 20-minute away dwell + single one-shot location confirmation, and strict
 * 30-minute per-trigger cooldowns without polling loops.
 */
class ProactiveEngine(
  private val destinationStore: DestinationStore,
  private val locationVerifier: OneShotLocationVerifier,
  private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
  private val diningDwellMs: Long = DEFAULT_DINING_DWELL_MS,
  private val clockMs: () -> Long = System::currentTimeMillis,
  private val onSuggestionUpdated: (ProactiveSuggestion?) -> Unit = {},
) {

  private val lock = Any()
  private val lastTriggerAt = HashMap<String, Long>()

  private val _suggestion = MutableStateFlow<ProactiveSuggestion?>(null)
  val suggestion: StateFlow<ProactiveSuggestion?> = _suggestion.asStateFlow()

  suspend fun onSignal(signal: ContextSignal) {
    val now = clockMs()
    when (signal) {
      is ContextSignal.StateCommitted -> {
        val prev = signal.previous
        val cur = signal.current
        val isVehicleStop = prev.motionState == MotionState.IN_VEHICLE && cur.motionState == MotionState.STILL
        val startedWalkingAway = prev.motionState != MotionState.WALKING &&
          cur.motionState == MotionState.WALKING &&
          cur.placeTag == PlaceTag.NONE

        if (isVehicleStop && cur.placeTag == PlaceTag.NONE) {
          if (tryEmitOpenMaps(cur, now)) return
        }
        if (startedWalkingAway) {
          tryEmitPostDiningQr(cur, now)
        }
      }

      is ContextSignal.ScreenUnlocked -> {
        val cur = signal.context
        if (cur.placeTag == PlaceTag.NONE) {
          if (tryEmitOpenMaps(cur, now)) return
          tryEmitPostDiningQr(cur, now)
        }
      }
    }
  }

  private fun tryEmitOpenMaps(ctx: AmbientContext, now: Long): Boolean {
    if (ctx.placeTag != PlaceTag.NONE) return false
    if (!canTriggerLocked(TRIGGER_OPEN_MAPS, now)) return false
    val nextDest = destinationStore.getActiveUpcomingDestinations(now).firstOrNull() ?: return false
    recordTriggerLocked(TRIGGER_OPEN_MAPS, now)
    publishSuggestion(
      ProactiveSuggestion.OpenMaps(
        id = UUID.randomUUID().toString(),
        destinationId = nextDest.id,
        placeName = nextDest.placeName,
        sourceSummary = nextDest.sourceSummary,
        triggeredAtMs = now,
      ),
    )
    return true
  }

  private suspend fun tryEmitPostDiningQr(ctx: AmbientContext, now: Long): Boolean {
    if (ctx.placeTag != PlaceTag.NONE) return false
    val awaySince = ctx.awayDwellSinceMs ?: return false
    val dwellMs = now - awaySince
    if (dwellMs < diningDwellMs) return false
    if (!canTriggerLocked(TRIGGER_SCAN_UPI_QR, now)) return false

    val venueLabel = locationVerifier.confirmAwayVenueLabel() ?: return false
    val dwellMins = (dwellMs / 60_000L).coerceAtLeast(20L)
    recordTriggerLocked(TRIGGER_SCAN_UPI_QR, now)
    publishSuggestion(
      ProactiveSuggestion.ScanUpiQr(
        id = UUID.randomUUID().toString(),
        placeLabel = venueLabel,
        dwellMinutes = dwellMins,
        sourceSummary = "Dwelt ${dwellMins}m away from Home/Campus at $venueLabel",
        triggeredAtMs = now,
      ),
    )
    return true
  }

  fun dismissSuggestion() {
    publishSuggestion(null)
  }

  fun consumeOpenMapsSuggestion(destinationId: Long) {
    destinationStore.markDestinationUsed(destinationId)
    publishSuggestion(null)
  }

  /**
   * Forces a traffic-stall OpenMaps suggestion from the drawer debug panel without altering
   * real-world cooldown history, exercising the exact pill/notification/Intent delivery path.
   */
  fun simulateOpenMapsForDebug(fallbackPlaceName: String = "Koramangala 4th Block"): Boolean {
    val now = clockMs()
    val nextDest = destinationStore.getActiveUpcomingDestinations(now).firstOrNull()
    val id = nextDest?.id ?: -1L
    val place = nextDest?.placeName ?: fallbackPlaceName
    val src = nextDest?.sourceSummary ?: "Debug simulation: IN_VEHICLE -> STILL (>45s)"
    publishSuggestion(
      ProactiveSuggestion.OpenMaps(
        id = UUID.randomUUID().toString(),
        destinationId = id,
        placeName = place,
        sourceSummary = src,
        triggeredAtMs = now,
      ),
    )
    return true
  }

  /**
   * Forces a post-dining UPI QR suggestion from the drawer debug panel without altering
   * real-world cooldown history, exercising the exact pill/notification/Intent delivery path.
   */
  fun simulateScanUpiQrForDebug(venueLabel: String = "Merchant Nearby"): Boolean {
    val now = clockMs()
    publishSuggestion(
      ProactiveSuggestion.ScanUpiQr(
        id = UUID.randomUUID().toString(),
        placeLabel = venueLabel,
        dwellMinutes = 24L,
        sourceSummary = "Debug simulation: Dwelt 24m away + WALKING ($venueLabel)",
        triggeredAtMs = now,
      ),
    )
    return true
  }

  fun canTrigger(triggerKey: String): Boolean = canTriggerLocked(triggerKey, clockMs())

  private fun canTriggerLocked(triggerKey: String, now: Long): Boolean = synchronized(lock) {
    val last = lastTriggerAt[triggerKey] ?: return@synchronized true
    (now - last) >= cooldownMs
  }

  private fun recordTriggerLocked(triggerKey: String, now: Long) {
    synchronized(lock) {
      lastTriggerAt[triggerKey] = now
    }
  }

  private fun publishSuggestion(next: ProactiveSuggestion?) {
    _suggestion.value = next
    onSuggestionUpdated(next)
  }

  companion object {
    const val DEFAULT_COOLDOWN_MS = 30L * 60L * 1000L
    const val DEFAULT_DINING_DWELL_MS = 20L * 60L * 1000L
    const val TRIGGER_OPEN_MAPS = "OPEN_MAPS"
    const val TRIGGER_SCAN_UPI_QR = "SCAN_UPI_QR"
  }
}

/**
 * Single one-shot location fix via [FusedLocationProviderClient.getCurrentLocation] followed by
 * reverse [Geocoder] lookup. Never registers continuous GPS updates.
 */
class PlayServicesOneShotLocationVerifier(
  private val context: Context,
) : OneShotLocationVerifier {

  @SuppressLint("MissingPermission")
  override suspend fun confirmAwayVenueLabel(): String? = withContext(Dispatchers.IO) {
    val appCtx = context.applicationContext
    val fine = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return@withContext null

    val cts = CancellationTokenSource()
    val location = withTimeoutOrNull(8_000L) {
      suspendCancellableCoroutine<Location?> { cont ->
        cont.invokeOnCancellation { cts.cancel() }
        val client = LocationServices.getFusedLocationProviderClient(appCtx)
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
          .addOnSuccessListener { loc ->
            if (cont.isActive) cont.resume(loc)
          }
          .addOnFailureListener {
            if (cont.isActive) cont.resume(null)
          }
      }
    } ?: return@withContext null

    reverseGeocodeLabel(appCtx, location.latitude, location.longitude) ?: "Merchant Nearby"
  }

  @Suppress("DEPRECATION")
  private suspend fun reverseGeocodeLabel(appCtx: Context, lat: Double, lon: Double): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(appCtx, Locale.getDefault())
    return runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val deferred = CompletableDeferred<String?>()
        geocoder.getFromLocation(lat, lon, 1) { addresses ->
          val addr = addresses.firstOrNull()
          val label = addr?.featureName?.takeIf { !it.all { ch -> ch.isDigit() } }
            ?: addr?.subLocality
            ?: addr?.thoroughfare
            ?: addr?.locality
          if (!deferred.isCompleted) deferred.complete(label)
        }
        withTimeoutOrNull(4_000L) { deferred.await() }
      } else {
        val addr = geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
        addr?.featureName?.takeIf { !it.all { ch -> ch.isDigit() } }
          ?: addr?.subLocality
          ?: addr?.thoroughfare
          ?: addr?.locality
      }
    }.getOrNull()
  }
}
