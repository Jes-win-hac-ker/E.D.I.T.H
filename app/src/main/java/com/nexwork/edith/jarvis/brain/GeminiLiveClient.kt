package com.nexwork.edith.jarvis.brain

import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Bridge invoked when `gemini-3.8-live` issues a `toolCall` frame. Implementations MUST route
 * all device/outbound actions through [com.nexwork.edith.jarvis.security.SecurityGateway].
 */
interface LiveToolBridge {
  suspend fun executeLiveTool(name: String, args: JsonObject): JsonObject
}

interface LiveSessionCallbacks {
  fun onConnected(modelId: String)
  fun onAudioOut(pcm24kLe: ByteArray)
  fun onInterrupted()
  fun onTranscript(text: String)
  fun onToolActivity(summary: String)
  fun onClosed(reason: String)
  fun onError(message: String)
}

/**
 * On-demand OkHttp WebSocket client for Google AI Studio's Multimodal Live API (`gemini-3.8-live`).
 *
 * Streams base64 `audio/pcm;rate=16000` frames upstream, plays `audio/pcm` model responses over a
 * dedicated single-consumer IO playback channel, reacts immediately to `serverContent.interrupted`
 * for barge-in, executes declared tools through [LiveToolBridge], and auto-closes 5 seconds after
 * post-response silence.
 */
class GeminiLiveClient(
  private val okHttpClient: OkHttpClient = defaultWebSocketClient(),
  private val primaryModel: String = PRIMARY_MODEL,
  private val fallbackModels: List<String> = listOf(FALLBACK_MODEL),
  private val postTurnSilenceTimeoutMs: Long = DEFAULT_SILENCE_CLOSE_MS,
) {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
  }

  private val lock = Any()
  private var activeSession: LiveSession? = null

  fun isConnected(): Boolean = synchronized(lock) { activeSession?.isOpen == true }

  /**
   * Opens a WebSocket live session and waits until the `setupComplete` handshake finishes.
   * Falls back to [fallbackModels] if Google AI Studio rejects [primaryModel] during connect.
   */
  suspend fun connect(
    apiKey: String,
    scope: CoroutineScope,
    toolBridge: LiveToolBridge,
    callbacks: LiveSessionCallbacks,
    personaNote: String = "",
  ): Result<LiveSession> {
    val cleanKey = apiKey.trim()
    if (cleanKey.isEmpty()) {
      return Result.failure(IllegalStateException("Missing Google AI Studio API key in KeyVault"))
    }
    disconnect("Replaced by new voice session")

    val modelsToTry = (listOf(primaryModel) + fallbackModels).distinct()
    var lastError: Throwable = IllegalStateException("Unable to open Gemini Live session")
    for (model in modelsToTry) {
      val attempt = openSingleModel(cleanKey, model, scope, toolBridge, callbacks, personaNote)
      if (attempt.isSuccess) {
        return attempt
      }
      lastError = attempt.exceptionOrNull() ?: lastError
    }
    return Result.failure(lastError)
  }

  fun sendAudioChunk(pcm16kLe: ByteArray) {
    val session = synchronized(lock) { activeSession } ?: return
    session.sendAudioChunk(pcm16kLe)
  }

  fun sendAudioStreamEnd() {
    val session = synchronized(lock) { activeSession } ?: return
    session.sendAudioStreamEnd()
  }

  fun sendTextTurn(promptText: String) {
    val session = synchronized(lock) { activeSession } ?: return
    session.sendTextTurn(promptText)
  }

  fun notifyUserSpeakingActivity() {
    val session = synchronized(lock) { activeSession } ?: return
    session.resetSilenceTimer()
  }

  fun disconnect(reason: String = "Session ended") {
    val session = synchronized(lock) {
      val s = activeSession
      activeSession = null
      s
    }
    session?.close(reason)
  }

  private suspend fun openSingleModel(
    apiKey: String,
    model: String,
    scope: CoroutineScope,
    toolBridge: LiveToolBridge,
    callbacks: LiveSessionCallbacks,
    personaNote: String,
  ): Result<LiveSession> {
    val session = LiveSession(
      modelId = model,
      scope = scope,
      toolBridge = toolBridge,
      callbacks = callbacks,
      json = json,
      silenceTimeoutMs = postTurnSilenceTimeoutMs,
      onSelfClosed = { closed ->
        synchronized(lock) {
          if (activeSession === closed) activeSession = null
        }
      },
    )
    synchronized(lock) { activeSession = session }

    val url = buildWsUrl(apiKey)
    val req = Request.Builder().url(url).build()
    val setupPayload = buildSetupFrame(model = model, extraSystemContext = personaNote).toString()
    val ws = okHttpClient.newWebSocket(req, session.listener(setupPayload))
    session.attachSocket(ws)

    val setupResult = withTimeoutOrNull(SETUP_TIMEOUT_MS) { session.awaitSetup() }
    return when {
      setupResult == null -> {
        session.close("Setup handshake timed out ($model)")
        Result.failure(IllegalStateException("Gemini Live setup timed out for $model"))
      }
      setupResult.isFailure -> {
        val err = setupResult.exceptionOrNull() ?: IllegalStateException("Setup failed for $model")
        session.close("Setup error ($model)")
        Result.failure(err)
      }
      else -> {
        callbacks.onConnected(model)
        Result.success(session)
      }
    }
  }

  inner class LiveSession internal constructor(
    val modelId: String,
    private val scope: CoroutineScope,
    private val toolBridge: LiveToolBridge,
    private val callbacks: LiveSessionCallbacks,
    private val json: Json,
    private val silenceTimeoutMs: Long,
    private val onSelfClosed: (LiveSession) -> Unit,
  ) {
    private val closed = AtomicBoolean(false)
    private val setupDeferred = CompletableDeferred<Result<Unit>>()
    private val audioOutQueue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val runningToolJobs = mutableMapOf<String, Job>()
    private var webSocket: WebSocket? = null
    private var silenceTimerJob: Job? = null
    private var playbackJob: Job? = null

    val isOpen: Boolean get() = !closed.get() && webSocket != null

    init {
      playbackJob = scope.launch(Dispatchers.IO) {
        for (chunk in audioOutQueue) {
          if (closed.get()) break
          runCatching { callbacks.onAudioOut(chunk) }
        }
      }
    }

    internal fun attachSocket(ws: WebSocket) {
      webSocket = ws
    }

    internal suspend fun awaitSetup(): Result<Unit> = setupDeferred.await()

    fun sendAudioChunk(pcm16kLe: ByteArray) {
      if (closed.get() || pcm16kLe.isEmpty()) return
      cancelSilenceTimer()
      val frame = buildRealtimeAudioFrame(pcm16kLe).toString()
      webSocket?.send(frame)
    }

    fun sendAudioStreamEnd() {
      if (closed.get()) return
      val frame = buildAudioStreamEndFrame().toString()
      webSocket?.send(frame)
    }

    fun sendTextTurn(text: String) {
      if (closed.get() || text.isBlank()) return
      cancelSilenceTimer()
      val frame = buildClientContentFrame(text).toString()
      webSocket?.send(frame)
    }

    fun resetSilenceTimer() {
      cancelSilenceTimer()
    }

    private fun armSilenceTimer() {
      if (closed.get() || silenceTimeoutMs <= 0L) return
      silenceTimerJob?.cancel()
      silenceTimerJob = scope.launch {
        delay(silenceTimeoutMs)
        if (!closed.get()) {
          close("Closed after ${silenceTimeoutMs / 1000}s post-response silence")
        }
      }
    }

    private fun cancelSilenceTimer() {
      silenceTimerJob?.cancel()
      silenceTimerJob = null
    }

    fun close(reason: String = "Session ended") {
      if (!closed.compareAndSet(false, true)) return
      onSelfClosed(this)
      cancelSilenceTimer()
      playbackJob?.cancel()
      audioOutQueue.close()
      synchronized(runningToolJobs) {
        runningToolJobs.values.forEach { it.cancel() }
        runningToolJobs.clear()
      }
      runCatching { webSocket?.close(1000, reason.take(100)) }
      webSocket = null
      if (!setupDeferred.isCompleted) {
        setupDeferred.complete(Result.failure(IllegalStateException(reason)))
      }
      callbacks.onClosed(reason)
    }

    internal fun listener(setupPayload: String): WebSocketListener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(setupPayload)
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        handleRawServerFrame(text)
      }

      override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        handleRawServerFrame(bytes.utf8())
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        close("Socket closing ($code): ${reason.ifBlank { "remote close" }}")
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        close("Socket closed ($code): ${reason.ifBlank { "done" }}")
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val httpDetail = response?.let { "HTTP ${it.code}" } ?: ""
        val msg = listOfNotNull(t.message, httpDetail.takeIf { it.isNotBlank() }).joinToString(" · ")
          .ifBlank { t::class.java.simpleName }
        if (!setupDeferred.isCompleted) {
          setupDeferred.complete(Result.failure(IllegalStateException(msg, t)))
        }
        callbacks.onError(msg)
        close("Socket failure: $msg")
      }
    }

    private fun handleRawServerFrame(raw: String) {
      val event = runCatching { parseServerEvent(json.parseToJsonElement(raw).jsonObject) }
        .getOrElse { err ->
          callbacks.onError("Malformed Live frame: ${err.message}")
          return
        }

      if (event.setupComplete && !setupDeferred.isCompleted) {
        setupDeferred.complete(Result.success(Unit))
      }

      if (event.interrupted) {
        cancelSilenceTimer()
        // Drain queued playback buffers cleanly before issuing AudioTrack pause + flush.
        while (audioOutQueue.tryReceive().isSuccess) {
          // drop stale incoming audio already buffered
        }
        callbacks.onInterrupted()
      }

      for (chunk in event.audioChunks) {
        cancelSilenceTimer()
        audioOutQueue.trySend(chunk)
      }

      event.transcriptText?.takeIf { it.isNotBlank() }?.let { callbacks.onTranscript(it) }

      if (event.cancelledToolIds.isNotEmpty()) {
        synchronized(runningToolJobs) {
          event.cancelledToolIds.forEach { id -> runningToolJobs.remove(id)?.cancel() }
        }
      }

      if (event.toolCalls.isNotEmpty()) {
        cancelSilenceTimer()
        dispatchToolCalls(event.toolCalls)
      }

      if (event.turnComplete) {
        armSilenceTimer()
      }
    }

    private fun dispatchToolCalls(calls: List<LiveToolCall>) {
      for (call in calls) {
        val job = scope.launch(Dispatchers.IO) {
          callbacks.onToolActivity("LIVE TOOL -> ${call.name}")
          val outcomePayload = runCatching {
            toolBridge.executeLiveTool(call.name, call.args)
          }.getOrElse { err ->
            buildJsonObject {
              put("ok", false)
              put("error", err.message ?: err::class.java.simpleName)
            }
          }
          synchronized(runningToolJobs) {
            runningToolJobs.remove(call.id)
          }
          if (!closed.get()) {
            val responseFrame = buildToolResponseFrame(
              callId = call.id,
              callName = call.name,
              resultPayload = outcomePayload,
            ).toString()
            webSocket?.send(responseFrame)
          }
        }
        synchronized(runningToolJobs) {
          runningToolJobs[call.id] = job
        }
      }
    }
  }

  internal data class LiveToolCall(
    val id: String,
    val name: String,
    val args: JsonObject,
  )

  internal data class ParsedServerEvent(
    val setupComplete: Boolean = false,
    val interrupted: Boolean = false,
    val turnComplete: Boolean = false,
    val audioChunks: List<ByteArray> = emptyList(),
    val transcriptText: String? = null,
    val toolCalls: List<LiveToolCall> = emptyList(),
    val cancelledToolIds: List<String> = emptyList(),
  )

  companion object {
    const val PRIMARY_MODEL = "gemini-3.8-live"
    const val FALLBACK_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
    const val DEFAULT_SILENCE_CLOSE_MS = 5_000L
    private const val SETUP_TIMEOUT_MS = 10_000L

    fun defaultWebSocketClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .pingInterval(20, TimeUnit.SECONDS)
      .build()

    internal fun buildWsUrl(apiKey: String): String =
      "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${apiKey.trim()}"

    internal fun buildSetupFrame(
      model: String = PRIMARY_MODEL,
      extraSystemContext: String = "",
    ): JsonObject {
      val qualifiedModel = if (model.startsWith("models/")) model else "models/$model"
      val sysPrompt = buildString {
        appendLine("You are JARVIS, the owner's on-device E.D.I.T.H voice assistant on Motorola Moto G45 5G.")
        appendLine("Speak naturally and concisely. When user speaks in English or Manglish, reply concisely with clear Indian/English cadence.")
        appendLine("Never invent private messages or confirm unsafe financial actions without asking via SecurityGateway.")
        appendLine(JarvisLanguagePrompt.MANGLISH_RULEBOOK)
        if (extraSystemContext.isNotBlank()) {
          appendLine(extraSystemContext.trim())
        }
      }.trim()

      return buildJsonObject {
        put("setup", buildJsonObject {
          put("model", qualifiedModel)
          put("generationConfig", buildJsonObject {
            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            put("speechConfig", buildJsonObject {
              put("voiceConfig", buildJsonObject {
                put("prebuiltVoiceConfig", buildJsonObject {
                  put("voiceName", "Charon")
                })
              })
            })
          })
          put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray {
              add(buildJsonObject { put("text", sysPrompt) })
            })
          })
          put("tools", buildJsonArray {
            add(buildJsonObject {
              put("functionDeclarations", buildToolDeclarations())
            })
          })
        })
      }
    }

    internal fun buildToolDeclarations() = buildJsonArray {
      add(buildJsonObject {
        put("name", "reply_notification")
        put("description", "Send a reply to a cached active notification after SecurityGateway & DLP checks.")
        put("parameters", buildJsonObject {
          put("type", "OBJECT")
          put("properties", buildJsonObject {
            put("target", buildJsonObject {
              put("type", "STRING")
              put("description", "Sender display name or notification sbnKey to reply to.")
            })
            put("reply_text", buildJsonObject {
              put("type", "STRING")
              put("description", "Reply message body. Must never contain OTPs, PINs, or card numbers.")
            })
          })
          put("required", buildJsonArray {
            add(JsonPrimitive("target"))
            add(JsonPrimitive("reply_text"))
          })
        })
      })
      add(buildJsonObject {
        put("name", "shizuku_ui_action")
        put("description", "Execute a natural-language UI automation or app launch command via Shizuku uid 2000 playbooks & planner.")
        put("parameters", buildJsonObject {
          put("type", "OBJECT")
          put("properties", buildJsonObject {
            put("command", buildJsonObject {
              put("type", "STRING")
              put("description", "User intent to run on device, e.g. 'open youtube and search lo-fi'.")
            })
          })
          put("required", buildJsonArray { add(JsonPrimitive("command")) })
        })
      })
      add(buildJsonObject {
        put("name", "open_maps")
        put("description", "Launch Google Maps navigation toward a destination.")
        put("parameters", buildJsonObject {
          put("type", "OBJECT")
          put("properties", buildJsonObject {
            put("destination", buildJsonObject {
              put("type", "STRING")
              put("description", "Place or address to navigate toward.")
            })
          })
          put("required", buildJsonArray { add(JsonPrimitive("destination")) })
        })
      })
      add(buildJsonObject {
        put("name", "search_memory")
        put("description", "Search local Jarvis SQLite memory (recent notifications, rules, saved destinations).")
        put("parameters", buildJsonObject {
          put("type", "OBJECT")
          put("properties", buildJsonObject {
            put("query", buildJsonObject {
              put("type", "STRING")
              put("description", "Keyword, sender name, or topic to look up.")
            })
            put("limit", buildJsonObject {
              put("type", "INTEGER")
              put("description", "Optional maximum results (default 5).")
            })
          })
          put("required", buildJsonArray { add(JsonPrimitive("query")) })
        })
      })
    }

    internal fun buildRealtimeAudioFrame(pcm16kLe: ByteArray): JsonObject {
      val b64 = Base64.getEncoder().encodeToString(pcm16kLe)
      return buildJsonObject {
        put("realtimeInput", buildJsonObject {
          put("mediaChunks", buildJsonArray {
            add(buildJsonObject {
              put("mimeType", "audio/pcm;rate=16000")
              put("data", b64)
            })
          })
        })
      }
    }

    internal fun buildAudioStreamEndFrame(): JsonObject = buildJsonObject {
      put("realtimeInput", buildJsonObject {
        put("audioStreamEnd", true)
      })
    }

    internal fun buildClientContentFrame(text: String): JsonObject = buildJsonObject {
      put("clientContent", buildJsonObject {
        put("turns", buildJsonArray {
          add(buildJsonObject {
            put("role", "user")
            put("parts", buildJsonArray {
              add(buildJsonObject { put("text", text) })
            })
          })
        })
        put("turnComplete", true)
      })
    }

    internal fun buildToolResponseFrame(
      callId: String,
      callName: String,
      resultPayload: JsonObject,
    ): JsonObject = buildJsonObject {
      put("toolResponse", buildJsonObject {
        put("functionResponses", buildJsonArray {
          add(buildJsonObject {
            put("id", callId)
            put("name", callName)
            put("response", buildJsonObject {
              put("result", resultPayload)
            })
          })
        })
      })
    }

    internal fun parseServerEvent(root: JsonObject): ParsedServerEvent {
      val setupComplete = root.containsKey("setupComplete")
      val sc = root["serverContent"]?.asObjectOrNull()
      val interrupted = sc?.get("interrupted")?.jsonPrimitive?.booleanOrNull == true
      val turnComplete = sc?.get("turnComplete")?.jsonPrimitive?.booleanOrNull == true

      val audioChunks = mutableListOf<ByteArray>()
      val textParts = mutableListOf<String>()

      val modelParts = sc?.get("modelTurn")?.asObjectOrNull()?.get("parts")?.jsonArray
      if (modelParts != null) {
        for (el in modelParts) {
          val obj = el.asObjectOrNull() ?: continue
          obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { textParts += it }
          val inline = obj["inlineData"]?.asObjectOrNull()
          val mime = inline?.get("mimeType")?.jsonPrimitive?.contentOrNull.orEmpty()
          val dataB64 = inline?.get("data")?.jsonPrimitive?.contentOrNull
          if (!dataB64.isNullOrBlank() && mime.startsWith("audio/pcm", ignoreCase = true)) {
            runCatching { Base64.getDecoder().decode(dataB64) }.getOrNull()?.let { decoded ->
              if (decoded.isNotEmpty()) audioChunks += decoded
            }
          }
        }
      }

      sc?.get("outputTranscription")?.asObjectOrNull()?.get("text")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }?.let { textParts += it }

      val toolCalls = mutableListOf<LiveToolCall>()
      val fnCalls = root["toolCall"]?.asObjectOrNull()?.get("functionCalls")?.jsonArray
      if (fnCalls != null) {
        for (el in fnCalls) {
          val obj = el.asObjectOrNull() ?: continue
          val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
          if (name.isBlank()) continue
          val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: name
          val args = obj["args"]?.asObjectOrNull() ?: JsonObject(emptyMap())
          toolCalls += LiveToolCall(id = id, name = name, args = args)
        }
      }

      val cancelledIds = root["toolCallCancellation"]?.asObjectOrNull()
        ?.get("ids")?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()

      return ParsedServerEvent(
        setupComplete = setupComplete,
        interrupted = interrupted,
        turnComplete = turnComplete,
        audioChunks = audioChunks,
        transcriptText = textParts.joinToString(" ").trim().takeIf { it.isNotEmpty() },
        toolCalls = toolCalls,
        cancelledToolIds = cancelledIds,
      )
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
  }
}
