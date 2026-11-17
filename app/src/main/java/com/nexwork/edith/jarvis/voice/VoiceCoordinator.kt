package com.nexwork.edith.jarvis.voice

import android.content.Context
import android.util.Log
import com.nexwork.edith.jarvis.brain.GeminiLiveClient
import com.nexwork.edith.jarvis.brain.LiveSessionCallbacks
import com.nexwork.edith.jarvis.brain.LiveToolBridge
import com.nexwork.edith.jarvis.core.JarvisForegroundService
import com.nexwork.edith.jarvis.core.MotionState
import com.nexwork.edith.jarvis.core.ParkedMessage
import com.nexwork.edith.jarvis.core.VoiceSessionMode
import com.nexwork.edith.jarvis.core.VoiceStatus
import com.nexwork.edith.jarvis.memory.JarvisDatabase
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates Milestone 4 audio capture ([VadEngine]), speaker verification ([VoiceprintGate]),
 * Bluetooth isolation & playback ([AudioRouter]), the 5 s earbud [DeferredParkQueue], and
 * on-demand [GeminiLiveClient] (`gemini-3.8-live`) streaming under the supervisor IO scope.
 */
class VoiceCoordinator(
  context: Context,
  private val scope: CoroutineScope,
  private val db: JarvisDatabase,
  private val apiKeyProvider: () -> String?,
  private val toolBridge: LiveToolBridge,
  private val onUiFault: (String) -> Unit,
) {
  private val appCtx = context.applicationContext
  val audioRouter = AudioRouter(appCtx)
  val vadEngine = VadEngine(appCtx, audioRouter)
  val voiceprintGate = VoiceprintGate(appCtx, db)
  private val whisperSpeaker = AndroidWhisperSpeaker(appCtx, audioRouter)
  val parkQueue = DeferredParkQueue(scope = scope, speaker = whisperSpeaker)
  val liveClient = GeminiLiveClient()

  private val _status = MutableStateFlow(VoiceStatus())
  val status: StateFlow<VoiceStatus> = _status.asStateFlow()

  private val jobLock = Any()
  private var voiceJob: Job? = null
  private var enrollJob: Job? = null
  @Volatile private var wakeWordEnabledFlag = false

  init {
    scope.launch(Dispatchers.IO) {
      parkQueue.parkedMessages.collect { list ->
        _status.update { it.copy(parkedMessages = list) }
      }
    }
    scope.launch(Dispatchers.IO) {
      parkQueue.activeWhisper.collect { active ->
        _status.update { it.copy(activeWhisper = active) }
      }
    }
    scope.launch(Dispatchers.IO) {
      vadEngine.pauseReason.collect { reason ->
        _status.update { current ->
          val nextMode = when {
            reason != null && current.mode != VoiceSessionMode.IDLE -> VoiceSessionMode.PAUSED
            reason == null && current.mode == VoiceSessionMode.PAUSED ->
              if (wakeWordEnabledFlag) VoiceSessionMode.WAKE_LISTENING else VoiceSessionMode.IDLE
            else -> current.mode
          }
          current.copy(pauseReason = reason, mode = nextMode)
        }
      }
    }
  }

  fun refreshEnvironment(wakeWordSaved: Boolean) {
    wakeWordEnabledFlag = wakeWordSaved
    _status.update {
      it.copy(
        hasMicPermission = vadEngine.hasMicPermission(),
        voiceprintEnrolled = voiceprintGate.isEnrolled(),
        speakerModelReady = voiceprintGate.isModelAvailable(),
        wakeWordEnabled = wakeWordSaved,
        earbudsConnected = audioRouter.earbudsConnected(),
        routeLabel = when {
          audioRouter.isBluetoothHeadsetConnected() -> "BT HEADSET READY"
          audioRouter.earbudsConnected() -> "WIRED HEADSET"
          else -> "PHONE MIC"
        },
        pauseReason = vadEngine.computePauseReason(),
      )
    }
    if (wakeWordSaved && vadEngine.hasMicPermission() && !isAnyVoiceJobActive()) {
      JarvisForegroundService.promoteForMicrophone(appCtx)
      startWakeWordLoop()
    } else if (!wakeWordSaved && !isAnyVoiceJobActive()) {
      audioRouter.endSession()
      JarvisForegroundService.demoteMicrophone(appCtx)
    }
  }

  fun isAnyVoiceJobActive(): Boolean = synchronized(jobLock) {
    voiceJob?.isActive == true || enrollJob?.isActive == true || liveClient.isConnected()
  }

  fun setWakeWordEnabled(enabled: Boolean) {
    wakeWordEnabledFlag = enabled
    _status.update { it.copy(wakeWordEnabled = enabled) }
    if (!enabled) {
      stopAllVoiceWork("Wake-word listening turned off")
      return
    }
    if (!vadEngine.hasMicPermission()) {
      onUiFault("Grant RECORD_AUDIO permission to enable Wake-Word voice mode")
      return
    }
    if (!voiceprintGate.isEnrolled()) {
      _status.update {
        it.copy(lastTranscriptOrStatus = "Enroll your voiceprint (3 sentences) to lock Wake-Word mode")
      }
    }
    JarvisForegroundService.promoteForMicrophone(appCtx)
    startWakeWordLoop()
  }

  fun startPushToTalk(initialPrompt: String? = null) {
    if (!vadEngine.hasMicPermission()) {
      onUiFault("RECORD_AUDIO permission required for Live Voice")
      return
    }
    val apiKey = apiKeyProvider()?.trim().orEmpty()
    if (apiKey.isEmpty()) {
      onUiFault("Add your Gemini API key in KeyVault first")
      return
    }
    cancelActiveJobs()
    JarvisForegroundService.promoteForMicrophone(appCtx)
    val claimedWhisper = parkQueue.claimActiveWhisper()
    val seedPrompt = initialPrompt ?: claimedWhisper?.let {
      "Read this newly arrived notification briefly and ask if I want to reply: " +
        "From ${it.sender} on ${it.appLabel} (${it.urgency}): ${it.summary}. sbnKey=${it.sbnKey}"
    }
    val job = scope.launch(Dispatchers.IO) {
      runLiveSession(apiKey = apiKey, seedAudio = null, seedPrompt = seedPrompt)
    }
    synchronized(jobLock) { voiceJob = job }
  }

  fun startEnrollment() {
    if (!vadEngine.hasMicPermission()) {
      onUiFault("Grant RECORD_AUDIO permission before enrolling voiceprint")
      return
    }
    cancelActiveJobs()
    JarvisForegroundService.promoteForMicrophone(appCtx)
    val job = scope.launch(Dispatchers.IO) {
      try {
        if (!voiceprintGate.isModelAvailable()) {
          _status.update {
            it.copy(
              mode = VoiceSessionMode.ENROLLING,
              enrollmentStep = 0,
              speakerModelDownloadPct = 0,
              lastTranscriptOrStatus = "Downloading on-device speaker model (~29 MB)...",
            )
          }
          voiceprintGate.downloadModel { pct ->
            _status.update { it.copy(speakerModelDownloadPct = pct) }
          }
          _status.update {
            it.copy(speakerModelReady = true, speakerModelDownloadPct = null)
          }
        }

        val routeOk = audioRouter.beginVoiceRoute()
        _status.update {
          it.copy(
            routeLabel = audioRouter.routeLabel,
            earbudsConnected = audioRouter.earbudsConnected(),
          )
        }
        if (!routeOk) {
          throw IllegalStateException("Bluetooth headset SCO mic failed to connect (${audioRouter.routeLabel})")
        }

        val utterances = mutableListOf<ShortArray>()
        for (step in 1..3) {
          currentCoroutineContext().ensureActive()
          _status.update {
            it.copy(
              mode = VoiceSessionMode.ENROLLING,
              enrollmentStep = step,
              lastTranscriptOrStatus = "Step $step/3: Read prompt aloud (${audioRouter.routeLabel})...",
            )
          }
          val clip = captureSingleUtterance(step = step, maxWaitMs = 10_000L)
          if (clip == null || clip.size < VoiceprintGate.MIN_SPEECH_SAMPLES) {
            val heardMs = (clip?.size ?: 0) / 16
            throw IllegalStateException(
              "Enrollment step $step heard only ${heardMs}ms via ${audioRouter.routeLabel} (need >=800ms).",
            )
          }
          utterances += clip
          if (step < 3) {
            _status.update { it.copy(lastTranscriptOrStatus = "Step $step/3 captured! Next sentence...") }
            delay(450)
          }
        }

        _status.update { it.copy(lastTranscriptOrStatus = "Computing encrypted speaker centroid...") }
        voiceprintGate.enroll(utterances)
        audioRouter.endVoiceRoute()
        _status.update {
          it.copy(
            voiceprintEnrolled = true,
            speakerModelReady = true,
            mode = VoiceSessionMode.IDLE,
            enrollmentStep = 0,
            routeLabel = if (audioRouter.isBluetoothHeadsetConnected()) "BT HEADSET READY" else audioRouter.routeLabel,
            lastConfidenceScore = 1.0f,
            lastConfidenceAccepted = true,
            lastTranscriptOrStatus = "Voiceprint enrolled (3 sentences) & Keystore-encrypted",
          )
        }
      } catch (c: CancellationException) {
        throw c
      } catch (err: Throwable) {
        val msg = err.message ?: "Voiceprint enrollment failed"
        _status.update {
          it.copy(
            mode = VoiceSessionMode.IDLE,
            enrollmentStep = 0,
            speakerModelDownloadPct = null,
            lastTranscriptOrStatus = msg,
          )
        }
        onUiFault(msg)
      } finally {
        audioRouter.endVoiceRoute()
        synchronized(jobLock) {
          if (enrollJob === coroutineContext[Job]) enrollJob = null
        }
        if (wakeWordEnabledFlag) {
          startWakeWordLoop()
        } else if (!isAnyVoiceJobActive()) {
          JarvisForegroundService.demoteMicrophone(appCtx)
        }
      }
    }
    synchronized(jobLock) { enrollJob = job }
  }

  fun clearVoiceprint() {
    voiceprintGate.clearEnrollment()
    _status.update {
      it.copy(
        voiceprintEnrolled = false,
        lastConfidenceScore = 0f,
        lastConfidenceAccepted = false,
        lastTranscriptOrStatus = "Stored voiceprint wiped",
      )
    }
  }

  fun onNotificationTriaged(
    sbnKey: String,
    appLabel: String,
    sender: String,
    bodyPreview: String,
    summary: String,
    urgency: String,
    hasReplyHandle: Boolean,
    motionState: MotionState,
  ) {
    val earbuds = audioRouter.earbudsConnected()
    if (!parkQueue.shouldOfferEarbudWhisper(motionState, earbuds)) return
    val item = parkQueue.createParkedMessage(
      sbnKey = sbnKey,
      appLabel = appLabel,
      sender = sender,
      preview = bodyPreview,
      summary = summary,
      urgency = urgency,
      hasReplyHandle = hasReplyHandle,
    )
    parkQueue.offerOrPark(item, isLiveSessionActive = liveClient.isConnected())
  }

  fun claimActiveWhisperAndSpeak() {
    val claimed = parkQueue.claimActiveWhisper() ?: return
    startPushToTalk(
      initialPrompt = "Read this message over my earbuds and ask if I want to reply: " +
        "${claimed.sender} (${claimed.appLabel}, ${claimed.urgency}): ${claimed.summary}. sbnKey=${claimed.sbnKey}",
    )
  }

  fun playParkedMessage(id: String) {
    val msg = parkQueue.remove(id) ?: return
    startPushToTalk(
      initialPrompt = "Read this parked message to me and ask if I should reply or dismiss: " +
        "${msg.sender} on ${msg.appLabel}: ${msg.summary}. sbnKey=${msg.sbnKey}",
    )
  }

  fun playAllParkedMessages() {
    val all = parkQueue.drainAll()
    if (all.isEmpty()) return
    val digest = all.joinToString("\n") { m ->
      "- From ${m.sender} (${m.appLabel}, ${m.urgency}): ${m.summary} [sbnKey=${m.sbnKey}]"
    }
    startPushToTalk(
      initialPrompt = "Read my ${all.size} parked messages briefly and ask which one I want to reply to:\n$digest",
    )
  }

  fun dismissParkedMessage(id: String) {
    parkQueue.remove(id)
  }

  fun clearParkedMessages() {
    parkQueue.clearAll()
  }

  fun stopAllVoiceWork(reason: String = "Voice session stopped") {
    cancelActiveJobs()
    liveClient.disconnect(reason)
    audioRouter.endSession()
    if (!wakeWordEnabledFlag) {
      JarvisForegroundService.demoteMicrophone(appCtx)
    }
    _status.update {
      it.copy(
        mode = VoiceSessionMode.IDLE,
        enrollmentStep = 0,
        routeLabel = if (audioRouter.isBluetoothHeadsetConnected()) "BT HEADSET READY" else audioRouter.routeLabel,
        lastTranscriptOrStatus = reason,
      )
    }
  }

  private fun cancelActiveJobs() {
    synchronized(jobLock) {
      voiceJob?.cancel(CancellationException("Superseded"))
      enrollJob?.cancel(CancellationException("Superseded"))
      voiceJob = null
      enrollJob = null
    }
    liveClient.disconnect("Stopped")
    audioRouter.endSession()
  }

  private fun startWakeWordLoop() {
    cancelActiveJobs()
    val job = scope.launch(Dispatchers.IO) {
      try {
        while (currentCoroutineContext().isActive && wakeWordEnabledFlag) {
          if (audioRouter.isBluetoothHeadsetConnected() && !audioRouter.beginVoiceRoute()) {
            _status.update {
              it.copy(
                routeLabel = audioRouter.routeLabel,
                lastTranscriptOrStatus = "Waiting for BT headset SCO link...",
              )
            }
            delay(1_500)
            continue
          }
          _status.update {
            it.copy(
              routeLabel = audioRouter.routeLabel,
              earbudsConnected = audioRouter.earbudsConnected(),
              mode = if (vadEngine.computePauseReason() != null) VoiceSessionMode.PAUSED else VoiceSessionMode.WAKE_LISTENING,
              lastTranscriptOrStatus = if (voiceprintGate.isEnrolled()) {
                "Listening with Silero VAD + Speaker Gate (>= 0.72)..."
              } else {
                "Wake-Word waiting: enroll voiceprint first for speaker lock"
              },
            )
          }
        val speechClip = captureWakeSpeechSegment() ?: continue
        if (!voiceprintGate.isEnrolled()) {
          _status.update { it.copy(lastTranscriptOrStatus = "Speech heard — enroll voiceprint to unlock hands-free Live") }
          delay(400)
          continue
        }
        if (!voiceprintGate.isModelAvailable()) {
          _status.update { it.copy(lastTranscriptOrStatus = "Speaker model missing — tap ENROLL to download") }
          delay(1000)
          continue
        }
        val verify = runCatching { voiceprintGate.verify(speechClip) }.getOrNull()
        if (verify == null) {
          _status.update { it.copy(lastTranscriptOrStatus = "Need slightly longer speech (>= 1s) to verify voiceprint") }
          continue
        }
        _status.update {
          it.copy(
            lastConfidenceScore = verify.score,
            lastConfidenceAccepted = verify.accepted,
          )
        }
        if (!verify.accepted) {
          val scoreStr = String.format(Locale.US, "%.2f", verify.score)
          _status.update { it.copy(lastTranscriptOrStatus = "Speaker rejected ($scoreStr < 0.72)") }
          continue
        }

        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isEmpty()) {
          onUiFault("Voice verified, but Gemini API key is missing in KeyVault")
          delay(1500)
          continue
        }

        val claimed = parkQueue.claimActiveWhisper()
        val seedPrompt = claimed?.let {
          "The user just responded after hearing ${it.sender} on ${it.appLabel}. " +
            "Message: ${it.summary} (sbnKey=${it.sbnKey})."
        }
        runLiveSession(apiKey = apiKey, seedAudio = speechClip, seedPrompt = seedPrompt)
        }
      } finally {
        audioRouter.endSession()
        if (!wakeWordEnabledFlag && !isAnyVoiceJobActive()) {
          JarvisForegroundService.demoteMicrophone(appCtx)
        }
      }
    }
    synchronized(jobLock) { voiceJob = job }
  }

  /** Collects frames until >= 1.25s of active speech has accumulated (with a 6-frame onset pre-roll). */
  private suspend fun captureWakeSpeechSegment(): ShortArray? {
    val preRoll = ArrayDeque<ShortArray>(PRE_ROLL_FRAMES)
    val speechBuf = mutableListOf<ShortArray>()
    var totalSamples = 0
    return try {
      vadEngine.frames().collect { frame ->
        if (frame.speaking) {
          if (speechBuf.isEmpty() && preRoll.isNotEmpty()) {
            while (preRoll.isNotEmpty()) {
              val pre = preRoll.removeFirst()
              speechBuf += pre
              totalSamples += pre.size
            }
          }
          speechBuf += frame.pcm
          totalSamples += frame.pcm.size
          if (totalSamples >= WAKE_VERIFY_MIN_SAMPLES) {
            throw WakeSegmentReadyException(concatShorts(speechBuf, totalSamples))
          }
        } else if (totalSamples > 0) {
          if (totalSamples < VoiceprintGate.MIN_SPEECH_SAMPLES) {
            speechBuf.clear()
            totalSamples = 0
          } else {
            throw WakeSegmentReadyException(concatShorts(speechBuf, totalSamples))
          }
        } else {
          if (preRoll.size >= PRE_ROLL_FRAMES) preRoll.removeFirst()
          preRoll.addLast(frame.pcm)
        }
      }
      null
    } catch (ready: WakeSegmentReadyException) {
      ready.pcm
    }
  }

  private suspend fun captureSingleUtterance(step: Int, maxWaitMs: Long): ShortArray? {
    val preRoll = ArrayDeque<ShortArray>(PRE_ROLL_FRAMES)
    val speechBuf = mutableListOf<ShortArray>()
    var totalSamples = 0
    var silenceTail = 0
    var announcedHearing = false
    runCatching {
      withTimeoutOrNull(maxWaitMs) {
        vadEngine.frames().collect { frame ->
          if (frame.speaking) {
            if (speechBuf.isEmpty() && preRoll.isNotEmpty()) {
              while (preRoll.isNotEmpty()) {
                val pre = preRoll.removeFirst()
                speechBuf += pre
                totalSamples += pre.size
              }
            }
            speechBuf += frame.pcm
            totalSamples += frame.pcm.size
            silenceTail = 0
            if (!announcedHearing) {
              announcedHearing = true
              _status.update { it.copy(lastTranscriptOrStatus = "Step $step/3: Hearing speech...") }
            }
            if (totalSamples >= AudioDsp.SAMPLE_RATE * 4) {
              throw WakeSegmentReadyException(concatShorts(speechBuf, totalSamples))
            }
          } else if (totalSamples > 0) {
            silenceTail++
            if (totalSamples >= VoiceprintGate.MIN_SPEECH_SAMPLES && silenceTail >= 7) {
              throw WakeSegmentReadyException(concatShorts(speechBuf, totalSamples))
            }
          } else {
            if (preRoll.size >= PRE_ROLL_FRAMES) preRoll.removeFirst()
            preRoll.addLast(frame.pcm)
          }
        }
      }
    }.onFailure { err ->
      if (err is WakeSegmentReadyException) return err.pcm
      if (err is CancellationException) throw err
      throw err
    }
    return if (totalSamples > 0) concatShorts(speechBuf, totalSamples) else null
  }

  private suspend fun runLiveSession(
    apiKey: String,
    seedAudio: ShortArray?,
    seedPrompt: String?,
  ) {
    try {
      val routeOk = audioRouter.beginVoiceRoute()
      _status.update { it.copy(routeLabel = audioRouter.routeLabel, earbudsConnected = audioRouter.earbudsConnected()) }
      if (!routeOk) {
        val msg = "BT Headset Mic isolation failed — blocking speakerphone fallback"
        _status.update { it.copy(mode = VoiceSessionMode.IDLE, lastTranscriptOrStatus = msg) }
        onUiFault(msg)
        return
      }
      audioRouter.requestFocus()
      _status.update {
        it.copy(
          mode = VoiceSessionMode.LIVE_STREAMING,
          lastTranscriptOrStatus = "Connecting gemini-3.8-live...",
        )
      }

      val callbacks = object : LiveSessionCallbacks {
        override fun onConnected(modelId: String) {
          Log.i(TAG, "Connected Gemini Live session: model=$modelId route=${audioRouter.routeLabel}")
          _status.update {
            it.copy(
              mode = VoiceSessionMode.LIVE_STREAMING,
              lastTranscriptOrStatus = "LIVE [$modelId] · Speak now (${audioRouter.routeLabel})",
            )
          }
        }

        override fun onAudioOut(pcm24kLe: ByteArray) {
          audioRouter.play(pcm24kLe)
        }

        override fun onInterrupted() {
          audioRouter.flushPlayback()
          _status.update { it.copy(lastTranscriptOrStatus = "Barge-in: AudioTrack flushed") }
        }

        override fun onTranscript(text: String) {
          Log.i(TAG, "Gemini Live transcript: ${text.take(120)}")
          _status.update { it.copy(lastTranscriptOrStatus = text.take(140)) }
        }

        override fun onToolActivity(summary: String) {
          Log.i(TAG, "Gemini Live tool: $summary")
          _status.update { it.copy(lastTranscriptOrStatus = summary) }
        }

        override fun onClosed(reason: String) {
          Log.i(TAG, "Gemini Live closed: $reason")
          _status.update {
            it.copy(
              mode = if (wakeWordEnabledFlag) VoiceSessionMode.WAKE_LISTENING else VoiceSessionMode.IDLE,
              lastTranscriptOrStatus = reason,
            )
          }
        }

        override fun onError(message: String) {
          Log.w(TAG, "Gemini Live error: $message")
          onUiFault("Gemini Live: $message")
        }
      }

      val connectResult = liveClient.connect(
        apiKey = apiKey,
        scope = scope,
        toolBridge = toolBridge,
        callbacks = callbacks,
      )
      connectResult.onFailure { err ->
        if (err is CancellationException) throw err
        val msg = err.message ?: "Gemini Live connect failed"
        Log.e(TAG, "connect failure: $msg", err)
        _status.update { it.copy(mode = VoiceSessionMode.IDLE, lastTranscriptOrStatus = msg) }
        onUiFault(msg)
        return
      }

      seedAudio?.let { pcm ->
        liveClient.sendAudioChunk(AudioDsp.shortsToLeBytes(pcm))
        liveClient.sendAudioStreamEnd()
      }
      seedPrompt?.let { text ->
        liveClient.sendTextTurn(text)
      }

      val enrolledForVerify = voiceprintGate.isEnrolled() && voiceprintGate.isModelAvailable()
      var verifiedInPtt = !enrolledForVerify || seedAudio != null
      val verifyBuf = mutableListOf<ShortArray>()
      var verifySamples = 0
      val preRoll = ArrayDeque<ShortArray>(PRE_ROLL_FRAMES)
      var wasSpeaking = false
      var trailingSilenceSent = 0

      vadEngine.frames().collect { frame ->
        if (!liveClient.isConnected()) {
          throw LiveClosedSignal()
        }
        if (frame.speaking) {
          liveClient.notifyUserSpeakingActivity()
          if (!wasSpeaking) {
            wasSpeaking = true
            trailingSilenceSent = 0
            while (preRoll.isNotEmpty()) {
              val pre = preRoll.removeFirst()
              liveClient.sendAudioChunk(AudioDsp.shortsToLeBytes(pre))
            }
          }
          // Always stream speech frames immediately in Push-to-Talk so initial words are never clipped.
          liveClient.sendAudioChunk(AudioDsp.shortsToLeBytes(frame.pcm))

          if (!verifiedInPtt) {
            verifyBuf += frame.pcm
            verifySamples += frame.pcm.size
            if (verifySamples >= VoiceprintGate.MIN_SPEECH_SAMPLES) {
              val clip = concatShorts(verifyBuf, verifySamples)
              val res = runCatching { voiceprintGate.verify(clip) }.getOrNull()
              if (res != null) {
                Log.i(TAG, "Voiceprint verify score=${res.score} accepted=${res.accepted}")
                _status.update {
                  it.copy(lastConfidenceScore = res.score, lastConfidenceAccepted = res.accepted)
                }
                if (!res.accepted && wakeWordEnabledFlag) {
                  val scoreStr = String.format(Locale.US, "%.2f", res.score)
                  liveClient.disconnect("Voiceprint mismatch ($scoreStr < 0.72)")
                  throw LiveClosedSignal()
                }
                verifiedInPtt = true
              }
            }
          }
        } else {
          if (wasSpeaking) {
            // Send ~640ms of trailing silence frames and then signal audioStreamEnd so Gemini Live's
            // server-side VAD immediately detects end-of-turn and responds.
            if (trailingSilenceSent < TRAILING_SILENCE_FRAMES) {
              trailingSilenceSent++
              liveClient.sendAudioChunk(AudioDsp.shortsToLeBytes(frame.pcm))
              if (trailingSilenceSent == TRAILING_SILENCE_FRAMES) {
                liveClient.sendAudioStreamEnd()
                wasSpeaking = false
              }
            } else {
              wasSpeaking = false
            }
          } else {
            if (preRoll.size >= PRE_ROLL_FRAMES) preRoll.removeFirst()
            preRoll.addLast(frame.pcm)
          }
        }
      }
    } catch (_: LiveClosedSignal) {
      // clean post-response silence / remote close
    } catch (c: CancellationException) {
      throw c
    } catch (err: Throwable) {
      if (err is WakeSegmentReadyException) return
      Log.e(TAG, "runLiveSession fault", err)
      onUiFault(err.message ?: "Voice stream fault")
    } finally {
      liveClient.disconnect("Voice session closed")
      audioRouter.endSession()
      if (!wakeWordEnabledFlag) {
        JarvisForegroundService.demoteMicrophone(appCtx)
      }
      _status.update {
        it.copy(
          mode = if (wakeWordEnabledFlag) VoiceSessionMode.WAKE_LISTENING else VoiceSessionMode.IDLE,
          routeLabel = if (audioRouter.isBluetoothHeadsetConnected()) "BT HEADSET READY" else audioRouter.routeLabel,
          earbudsConnected = audioRouter.earbudsConnected(),
        )
      }
    }
  }

  private fun concatShorts(chunks: List<ShortArray>, total: Int): ShortArray {
    val out = ShortArray(total)
    var off = 0
    for (c in chunks) {
      System.arraycopy(c, 0, out, off, c.size)
      off += c.size
    }
    return out
  }

  private class WakeSegmentReadyException(val pcm: ShortArray) : RuntimeException()
  private class LiveClosedSignal : RuntimeException()

  companion object {
    private const val TAG = "JarvisVoice"
    private const val PRE_ROLL_FRAMES = 6 // ~192 ms onset pre-roll
    private const val TRAILING_SILENCE_FRAMES = 18 // ~576 ms trailing silence for server-side VAD
    private const val WAKE_VERIFY_MIN_SAMPLES = AudioDsp.SAMPLE_RATE + (AudioDsp.SAMPLE_RATE / 4) // 1.25 s
    val ENROLLMENT_PROMPTS = listOf(
      "Hey Jarvis, unlock my voice profile for E.D.I.T.H.",
      "Check my unread messages and hold the rest until I stop.",
      "Open navigation home and stay sharp on the road.",
    )
  }
}
