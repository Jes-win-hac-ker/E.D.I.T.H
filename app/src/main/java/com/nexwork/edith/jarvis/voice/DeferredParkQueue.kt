package com.nexwork.edith.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.nexwork.edith.jarvis.core.MotionState
import com.nexwork.edith.jarvis.core.ParkedMessage
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Abstracts short spoken prompts over earbuds so [DeferredParkQueue] can be unit-tested on JVM.
 */
fun interface WhisperSpeaker {
  fun speak(text: String)
}

/**
 * Android [TextToSpeech] implementation speaking short whispers with `USAGE_ASSISTANT` so
 * music ducks cleanly rather than switching Bluetooth into low-quality HFP/SCO.
 */
class AndroidWhisperSpeaker(
  context: Context,
  private val audioRouter: AudioRouter,
) : WhisperSpeaker {

  private val appCtx = context.applicationContext
  @Volatile private var ready = false
  @Volatile private var pendingText: String? = null
  private var tts: TextToSpeech? = null

  private fun ensureTts(): TextToSpeech {
    tts?.let { return it }
    val instance = TextToSpeech(appCtx) { status ->
      ready = status == TextToSpeech.SUCCESS
      if (ready) {
        tts?.language = Locale.getDefault()
        tts?.setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        )
        pendingText?.let { queued ->
          pendingText = null
          speakNow(queued)
        }
      }
    }
    tts = instance
    return instance
  }

  override fun speak(text: String) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    ensureTts()
    if (!ready) {
      pendingText = clean
      return
    }
    speakNow(clean)
  }

  private fun speakNow(text: String) {
    runCatching {
      audioRouter.requestFocus()
      val params = Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
      }
      tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "jarvis_whisper_${System.currentTimeMillis()}")
    }
  }

  fun shutdown() {
    runCatching { tts?.stop() }
    runCatching { tts?.shutdown() }
    tts = null
    ready = false
  }
}

/**
 * Milestone 4 Earbud Whisper + Deferred Park Queue.
 *
 * When the user is walking or riding (`MotionState.WALKING` / `MotionState.IN_VEHICLE`) with
 * earbuds connected, incoming triaged notifications trigger a gentle 5-second whisper prompt:
 * if claimed via voice/button within 5s, the message opens straight into Gemini Live; otherwise
 * it is safely parked in [parkedMessages] until the user is ready.
 */
class DeferredParkQueue(
  private val scope: CoroutineScope,
  private val speaker: WhisperSpeaker,
  private val whisperWindowMs: Long = DEFAULT_WHISPER_WINDOW_MS,
  private val clockMs: () -> Long = System::currentTimeMillis,
) {

  private val lock = Any()
  private val _parkedMessages = MutableStateFlow<List<ParkedMessage>>(emptyList())
  val parkedMessages: StateFlow<List<ParkedMessage>> = _parkedMessages.asStateFlow()

  private val _activeWhisper = MutableStateFlow<ParkedMessage?>(null)
  val activeWhisper: StateFlow<ParkedMessage?> = _activeWhisper.asStateFlow()

  private var whisperTimeoutJob: Job? = null

  /**
   * Decides whether [motionState] + [earbudsConnected] qualifies for earbud whisper parking.
   */
  fun shouldOfferEarbudWhisper(motionState: MotionState, earbudsConnected: Boolean): Boolean =
    earbudsConnected && (motionState == MotionState.WALKING || motionState == MotionState.IN_VEHICLE)

  /**
   * Starts a 5-second whisper prompt for [message] if not already in a live voice session;
   * if [isLiveSessionActive] is true, parks immediately without interrupting Gemini Live audio.
   */
  fun offerOrPark(
    message: ParkedMessage,
    isLiveSessionActive: Boolean = false,
  ) {
    if (isLiveSessionActive) {
      parkImmediately(message)
      return
    }

    val previousUnclaimed: ParkedMessage?
    synchronized(lock) {
      whisperTimeoutJob?.cancel()
      previousUnclaimed = _activeWhisper.value
      _activeWhisper.value = message
    }
    if (previousUnclaimed != null && previousUnclaimed.id != message.id) {
      parkImmediately(previousUnclaimed)
    }

    val urgencyNote = if (message.urgency.equals("CRITICAL", true) || message.urgency.equals("HIGH", true)) {
      "Urgent "
    } else {
      ""
    }
    speaker.speak("${urgencyNote}${message.sender} on ${message.appLabel}. Say read in 5 seconds, or parked.")

    val job = scope.launch {
      delay(whisperWindowMs)
      val timedOut = synchronized(lock) {
        val current = _activeWhisper.value
        if (current?.id == message.id) {
          _activeWhisper.value = null
          current
        } else {
          null
        }
      }
      if (timedOut != null) {
        parkImmediately(timedOut)
      }
    }
    synchronized(lock) {
      whisperTimeoutJob = job
    }
  }

  /**
   * Claims the currently whispered message (within the 5s window) so it isn't parked and can be
   * read/handled immediately by Gemini Live.
   */
  fun claimActiveWhisper(): ParkedMessage? = synchronized(lock) {
    whisperTimeoutJob?.cancel()
    whisperTimeoutJob = null
    val current = _activeWhisper.value
    _activeWhisper.value = null
    current
  }

  fun parkImmediately(message: ParkedMessage) {
    _parkedMessages.update { list ->
      val deduped = list.filterNot { it.sbnKey.isNotBlank() && it.sbnKey == message.sbnKey }
      (listOf(message) + deduped).take(MAX_PARKED_ITEMS)
    }
  }

  fun remove(id: String): ParkedMessage? {
    var removed: ParkedMessage? = null
    _parkedMessages.update { list ->
      removed = list.firstOrNull { it.id == id }
      list.filterNot { it.id == id }
    }
    return removed
  }

  fun drainAll(): List<ParkedMessage> {
    val active = claimActiveWhisper()
    var drained: List<ParkedMessage> = emptyList()
    _parkedMessages.update { list ->
      drained = list
      emptyList()
    }
    return buildList {
      if (active != null) add(active)
      addAll(drained)
    }
  }

  fun clearAll() {
    claimActiveWhisper()
    _parkedMessages.value = emptyList()
  }

  fun createParkedMessage(
    sbnKey: String,
    appLabel: String,
    sender: String,
    preview: String,
    summary: String,
    urgency: String,
    hasReplyHandle: Boolean,
  ): ParkedMessage = ParkedMessage(
    id = UUID.randomUUID().toString(),
    sbnKey = sbnKey,
    appLabel = appLabel,
    sender = sender,
    preview = preview.take(160),
    summary = summary.take(200),
    urgency = urgency,
    arrivedAtMs = clockMs(),
    hasReplyHandle = hasReplyHandle,
  )

  companion object {
    const val DEFAULT_WHISPER_WINDOW_MS = 5_000L
    const val MAX_PARKED_ITEMS = 25
  }
}
