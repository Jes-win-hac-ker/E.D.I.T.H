package com.nexwork.edith.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns all audio routing for Jarvis voice I/O.
 *
 * - **Mic isolation**: when earbuds are connected, capture must come from the headset mic
 *   (SCO / BLE headset). The phone mic is refused so bystanders can't trigger Jarvis.
 * - **Focus**: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for the session only.
 * - **Playback**: 24 kHz / 16-bit mono `MODE_STREAM` [AudioTrack] for Gemini Live audio,
 *   with instant pause+flush for barge-in.
 * - **Teardown**: clears the communication device / SCO immediately so A2DP music quality returns.
 */
class AudioRouter(context: Context) {

  private val audioManager: AudioManager = context.applicationContext.getSystemService(AudioManager::class.java)
  private var focusRequest: AudioFocusRequest? = null
  private var track: AudioTrack? = null
  @Volatile private var commsRouted = false
  @Volatile var routeLabel: String = "PHONE MIC"
    private set

  private val headsetInputTypes = buildSet {
    add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
  }

  private val earbudOutputTypes = buildSet {
    add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      add(AudioDeviceInfo.TYPE_BLE_HEADSET)
      add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }
    add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
    add(AudioDeviceInfo.TYPE_USB_HEADSET)
  }

  fun isBluetoothHeadsetConnected(): Boolean =
    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
      it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
    }

  fun earbudsConnected(): Boolean =
    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in earbudOutputTypes }

  /** Headset mic input, or null when none is present. */
  fun headsetInput(): AudioDeviceInfo? =
    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in headsetInputTypes }
      ?: audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
      }

  fun preferredInputDevice(): AudioDeviceInfo? = if (earbudsConnected()) headsetInput() else null

  /**
   * Routes the communication path to the BT headset (SCO / LE Audio) so its mic becomes
   * available. Suspends up to 3 s for the SCO link. Returns false when isolation can't be met.
   */
  suspend fun beginVoiceRoute(): Boolean {
    if (!isBluetoothHeadsetConnected()) {
      routeLabel = if (earbudsConnected()) "WIRED HEADSET" else "PHONE MIC"
      return true
    }
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    commsRouted = true
    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val target = audioManager.availableCommunicationDevices.firstOrNull { it.type in headsetInputTypes }
      target != null && audioManager.setCommunicationDevice(target)
    } else {
      @Suppress("DEPRECATION")
      audioManager.startBluetoothSco()
      @Suppress("DEPRECATION")
      audioManager.isBluetoothScoOn = true
      true
    }
    if (!ok) {
      endVoiceRoute()
      routeLabel = "BT MIC UNAVAILABLE"
      return false
    }
    val linked = withTimeoutOrNull(3_000L) {
      while (headsetInput()?.type !in headsetInputTypes) delay(100)
      true
    } ?: false
    routeLabel = if (linked) "BT HEADSET MIC" else "BT MIC UNAVAILABLE"
    if (!linked) endVoiceRoute()
    return linked
  }

  /** Throws if earbuds are connected but [record] is not capturing from the headset mic. */
  fun assertInputIsolation(record: AudioRecord) {
    if (!earbudsConnected()) return
    val routed = record.routedDevice ?: record.preferredDevice
    val ok = routed != null && (routed.type in headsetInputTypes ||
      routed.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || routed.type == AudioDeviceInfo.TYPE_USB_HEADSET)
    if (!ok) {
      runCatching { record.stop() }
      record.release()
      throw SecurityException("Mic isolation: earbuds connected but capture is not from the headset mic")
    }
  }

  fun endVoiceRoute() {
    if (!commsRouted) return
    commsRouted = false
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.clearCommunicationDevice()
      } else {
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
      }
    }
    audioManager.mode = AudioManager.MODE_NORMAL
  }

  private fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(if (commsRouted) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

  fun requestFocus(): Boolean {
    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
      .setAudioAttributes(playbackAttributes())
      .setOnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) flushPlayback()
      }
      .build()
    focusRequest = req
    return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  fun abandonFocus() {
    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    focusRequest = null
  }

  @Synchronized
  private fun ensureTrack(): AudioTrack {
    track?.let { return it }
    val minBuf = AudioTrack.getMinBufferSize(PLAYBACK_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val t = AudioTrack.Builder()
      .setAudioAttributes(playbackAttributes())
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(PLAYBACK_RATE)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .build(),
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(maxOf(minBuf * 4, PLAYBACK_RATE)) // ~0.5 s headroom for gapless chunks
      .build()
    track = t
    return t
  }

  /** Blocking write of 24 kHz PCM16LE; call from an IO thread. */
  fun play(pcm16le: ByteArray) {
    val t = ensureTrack()
    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
    var off = 0
    while (off < pcm16le.size) {
      val n = t.write(pcm16le, off, pcm16le.size - off)
      if (n <= 0) break
      off += n
    }
  }

  /** Barge-in: drop everything queued in the track instantly. */
  @Synchronized
  fun flushPlayback() {
    track?.let {
      runCatching { it.pause() }
      runCatching { it.flush() }
    }
  }

  @Synchronized
  fun releasePlayback() {
    track?.let {
      runCatching { it.stop() }
      it.release()
    }
    track = null
  }

  /** Full session teardown: playback, focus, then BT comms (restores A2DP). */
  fun endSession() {
    releasePlayback()
    abandonFocus()
    endVoiceRoute()
  }

  companion object {
    const val PLAYBACK_RATE = 24_000
  }
}
