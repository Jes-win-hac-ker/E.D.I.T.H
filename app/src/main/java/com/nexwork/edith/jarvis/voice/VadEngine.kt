package com.nexwork.edith.jarvis.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One 32 ms (512-sample) mono 16 kHz frame plus its Silero speech probability. */
class VadFrame(
  val pcm: ShortArray,
  val probability: Float,
  /** Hysteresis-smoothed speech state (start >= 0.5, end after ~256 ms below 0.35). */
  val speaking: Boolean,
)

/**
 * Silero VAD v5 over a 16 kHz / 16-bit mono [AudioRecord] (`VOICE_RECOGNITION`) in 32 ms frames.
 *
 * Mic is physically released (AudioRecord stopped, privacy dot off) while paused:
 * - in-pocket: proximity reads near (0f) AND no Bluetooth headset is routing audio
 * - telephony: [AudioManager.MODE_IN_CALL] or [AudioManager.MODE_RINGTONE]
 *
 * Collect [frames] from `Dispatchers.IO`; cancelling the collector releases the mic.
 */
class VadEngine(
  context: Context,
  private val router: AudioRouter,
) {
  private val appCtx = context.applicationContext
  private val audioManager = appCtx.getSystemService(AudioManager::class.java)
  private val sensorManager = appCtx.getSystemService(SensorManager::class.java)

  private val _pauseReason = MutableStateFlow<String?>(null)
  val pauseReason: StateFlow<String?> = _pauseReason.asStateFlow()

  @Volatile private var proximityNear = false
  private var session: OrtSession? = null
  private val state = FloatArray(STATE_SIZE)
  private val context64 = FloatArray(CONTEXT_SAMPLES)
  private val captureMutex = Mutex()

  private val proximityListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      proximityNear = event.values.firstOrNull() == 0f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
  }

  fun hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private fun ensureSession(): OrtSession {
    session?.let { return it }
    val bytes = appCtx.assets.open(MODEL_ASSET).use { it.readBytes() }
    val opts = OrtSession.SessionOptions().apply {
      setIntraOpNumThreads(1)
      setInterOpNumThreads(1)
    }
    return OrtEnvironment.getEnvironment().createSession(bytes, opts).also { session = it }
  }

  fun resetState() {
    state.fill(0f)
    context64.fill(0f)
  }

  /** Runs one 512-sample frame through Silero and returns P(speech). */
  fun probability(pcm: ShortArray): Float {
    val s = ensureSession()
    val env = OrtEnvironment.getEnvironment()
    val input = FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES)
    System.arraycopy(context64, 0, input, 0, CONTEXT_SAMPLES)
    for (i in 0 until FRAME_SAMPLES) input[CONTEXT_SAMPLES + i] = pcm[i] / 32768f
    System.arraycopy(input, input.size - CONTEXT_SAMPLES, context64, 0, CONTEXT_SAMPLES)

    OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { x ->
      OnnxTensor.createTensor(env, FloatBuffer.wrap(state.copyOf()), longArrayOf(2, 1, 128)).use { st ->
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(AudioDsp.SAMPLE_RATE.toLong())), longArrayOf()).use { sr ->
          s.run(mapOf("input" to x, "state" to st, "sr" to sr)).use { res ->
            @Suppress("UNCHECKED_CAST")
            val prob = (res[0].value as Array<FloatArray>)[0][0]
            @Suppress("UNCHECKED_CAST")
            val newState = res[1].value as Array<Array<FloatArray>>
            var k = 0
            for (a in newState) for (b in a) for (v in b) state[k++] = v
            return prob
          }
        }
      }
    }
  }

  /** Current pause reason, or null when the mic may run. */
  fun computePauseReason(): String? {
    val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
    if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE) return "PHONE CALL"
    if (proximityNear && !router.isBluetoothHeadsetConnected()) return "IN POCKET"
    return null
  }

  /**
   * Cold flow of VAD frames. Opens the mic on collect (routed via [AudioRouter.preferredInputDevice]),
   * releases it whenever paused, and on cancellation.
   */
  @SuppressLint("MissingPermission")
  fun frames(): Flow<VadFrame> = flow {
    captureMutex.withLock {
      check(hasMicPermission()) { "RECORD_AUDIO not granted" }
      ensureSession()
      val proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
      if (proximity != null && sensorManager != null) {
        sensorManager.registerListener(proximityListener, proximity, SensorManager.SENSOR_DELAY_NORMAL)
      }
      var record: AudioRecord? = null
      var aec: AcousticEchoCanceler? = null
      var ns: NoiseSuppressor? = null
      var speaking = false
      var silentFrames = 0
      var zeroFrames = 0
      val buf = ShortArray(FRAME_SAMPLES)
      try {
        while (currentCoroutineContext().isActive) {
          val reason = computePauseReason()
          _pauseReason.value = reason
          if (reason != null) {
            if (record != null) {
              runCatching { record.stop() }
              record.release()
              aec?.release(); ns?.release()
              record = null; aec = null; ns = null
              speaking = false
              resetState()
            }
            delay(PAUSE_POLL_MS)
            continue
          }
          if (record == null) {
            val minBuf = AudioRecord.getMinBufferSize(
              AudioDsp.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val r = AudioRecord(
              MediaRecorder.AudioSource.VOICE_RECOGNITION,
              AudioDsp.SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              maxOf(minBuf, FRAME_SAMPLES * 2 * 8),
            )
            check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
            router.preferredInputDevice()?.let { r.preferredDevice = it }
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
            r.startRecording()
            // Strict isolation: if earbuds are connected, refuse frames from any non-headset mic.
            router.assertInputIsolation(r)
            record = r
            resetState()
          }
          var read = 0
          while (read < FRAME_SAMPLES) {
            val n = record.read(buf, read, FRAME_SAMPLES - read)
            if (n <= 0) error("AudioRecord read failed ($n)")
            read += n
          }
          val allZero = buf.all { it == 0.toShort() }
          if (allZero) {
            zeroFrames++
            if (zeroFrames >= MAX_ZERO_FRAMES) {
              error("Audio input silent (0s) via ${router.routeLabel} — check mic / SCO state")
            }
          } else {
            zeroFrames = 0
          }
          val prob = probability(buf)
          if (prob >= START_THRESHOLD) {
            speaking = true
            silentFrames = 0
          } else if (speaking && prob < END_THRESHOLD) {
            silentFrames++
            if (silentFrames >= MIN_SILENCE_FRAMES) speaking = false
          }
          emit(VadFrame(buf.copyOf(), prob, speaking))
        }
      } finally {
        record?.let { runCatching { it.stop() }; it.release() }
        aec?.release(); ns?.release()
        sensorManager?.unregisterListener(proximityListener)
        _pauseReason.value = null
      }
    }
  }.flowOn(Dispatchers.IO)

  fun close() {
    session?.close()
    session = null
  }

  companion object {
    const val FRAME_SAMPLES = 512 // 32 ms @ 16 kHz
    const val FRAME_MS = 32
    private const val CONTEXT_SAMPLES = 64
    private const val STATE_SIZE = 2 * 1 * 128
    private const val START_THRESHOLD = 0.40f
    private const val END_THRESHOLD = 0.25f
    private const val MIN_SILENCE_FRAMES = 8 // ~256 ms
    private const val MAX_ZERO_FRAMES = 60 // ~1.9 s of pure digital zeros -> fail fast
    private const val PAUSE_POLL_MS = 500L
    const val MODEL_ASSET = "models/silero_vad.onnx"
  }
}
