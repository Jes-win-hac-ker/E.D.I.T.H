package com.nexwork.edith.jarvis.brain

import com.nexwork.edith.jarvis.core.PlaybookStep
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class PlannerToolOutcome(
  val ok: Boolean,
  val summary: String,
  val payload: JsonObject,
  val securityBlocked: Boolean = false,
  val recordedStep: PlaybookStep? = null,
  val resolvedTargetPackage: String? = null,
)

/**
 * Execution bridge connecting [PlannerClient] function calls to guarded device actions.
 */
interface PlannerToolBridge {
  suspend fun executeTool(name: String, args: JsonObject): PlannerToolOutcome
}

data class PlannerRunResult(
  val summary: String,
  val recordedSteps: List<PlaybookStep>,
  val targetPackage: String,
  val traceLog: List<String>,
  val turnsUsed: Int,
)

/**
 * Fallback agentic planner calling Google AI Studio `gemini-4-hydrogen` with Function Calling
 * declarations (`get_screen_tree`, `click_node`, `input_text`, `scroll_node`, `open_app`,
 * `reply_notification`, `ask_user_confirmation`) inside a bounded turn loop.
 */
class PlannerClient(
  private val httpClient: OkHttpClient = defaultHttpClient(),
  private val endpointUrl: String = DEFAULT_ENDPOINT_URL,
  private val maxTurns: Int = MAX_PLANNER_TURNS,
) {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
  }

  suspend fun runPlan(
    apiKey: String,
    goalPrompt: String,
    initialContextNote: String = "",
    bridge: PlannerToolBridge,
    personaContextNote: String = "",
  ): Result<PlannerRunResult> {
    val trimmedKey = apiKey.trim()
    if (trimmedKey.isEmpty()) {
      return Result.failure(IllegalStateException("Missing Google AI Studio API key in KeyVault"))
    }

    val history = mutableListOf<JsonObject>()
    val initialPrompt = buildString {
      append("Goal: ").append(goalPrompt.trim())
      if (initialContextNote.isNotBlank()) {
        append("\nContext: ").append(initialContextNote.trim())
      }
      if (personaContextNote.isNotBlank()) {
        append('\n').append(personaContextNote.trim())
      }
    }
    history += buildJsonObject {
      put("role", "user")
      put("parts", buildJsonArray { add(buildJsonObject { put("text", initialPrompt) }) })
    }

    val recordedSteps = mutableListOf<PlaybookStep>()
    val traceLog = mutableListOf<String>()
    var discoveredPkg = ""
    var turnsUsed = 0

    for (turn in 0 until maxTurns) {
      currentCoroutineContext().ensureActive()
      turnsUsed = turn + 1
      val requestPayload = buildRequestJson(history)
      val responseJson = postGenerateContent(trimmedKey, requestPayload).getOrElse { err ->
        if (err is CancellationException) throw err
        return Result.failure(err)
      }

      val candidateContent = responseJson["candidates"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("content")
        ?.jsonObject
        ?: return Result.failure(IllegalStateException("Planner response returned no candidate content"))

      val parts = candidateContent["parts"]?.jsonArray ?: JsonArray(emptyList())
      history += buildJsonObject {
        put("role", "model")
        put("parts", parts)
      }

      val functionCalls = parts.mapNotNull { part ->
        part.jsonObject["functionCall"]?.jsonObject
      }

      if (functionCalls.isEmpty()) {
        val finalText = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
          .joinToString("\n")
          .trim()
          .ifEmpty { "Plan completed" }
        return Result.success(
          PlannerRunResult(
            summary = finalText,
            recordedSteps = recordedSteps,
            targetPackage = discoveredPkg,
            traceLog = traceLog,
            turnsUsed = turnsUsed,
          )
        )
      }

      val responseParts = buildJsonArray {
        for (call in functionCalls) {
          currentCoroutineContext().ensureActive()
          val fnName = call["name"]?.jsonPrimitive?.content.orEmpty()
          val fnArgs = call["args"] as? JsonObject ?: JsonObject(emptyMap())
          val outcome = bridge.executeTool(fnName, fnArgs)
          traceLog += "[$fnName] ${outcome.summary}"
          if (!outcome.resolvedTargetPackage.isNullOrBlank()) {
            discoveredPkg = outcome.resolvedTargetPackage
          }
          outcome.recordedStep?.let { recordedSteps += it }

          add(
            buildJsonObject {
              put(
                "functionResponse",
                buildJsonObject {
                  put("name", fnName)
                  put("response", outcome.payload)
                },
              )
            },
          )

          if (outcome.securityBlocked) {
            return Result.failure(SecurityException(outcome.summary))
          }
        }
      }

      history += buildJsonObject {
        put("role", "user")
        put("parts", responseParts)
      }
    }

    return Result.failure(
      IllegalStateException("Planner halted at $maxTurns-step velocity cap before completion"),
    )
  }

  internal fun buildRequestJson(contents: List<JsonObject>): String {
    val body = buildJsonObject {
      put(
        "systemInstruction",
        buildJsonObject {
          put(
            "parts",
            buildJsonArray {
              add(buildJsonObject { put("text", SYSTEM_INSTRUCTION) })
            },
          )
        },
      )
      put("contents", JsonArray(contents))
      put("tools", buildToolDeclarations())
      put(
        "generationConfig",
        buildJsonObject {
          put("temperature", 0.1)
          put("maxOutputTokens", 768)
        },
      )
    }
    return json.encodeToString(JsonObject.serializer(), body)
  }

  private suspend fun postGenerateContent(apiKey: String, bodyJson: String): Result<JsonObject> {
    val primary = executePost(endpointUrl, apiKey, bodyJson)
    if (primary.isSuccess) return primary
    val err = primary.exceptionOrNull()
    if (err is CancellationException) throw err
    if (endpointUrl == DEFAULT_ENDPOINT_URL && err?.message?.contains("404") == true ||
      (endpointUrl == DEFAULT_ENDPOINT_URL && err?.message?.contains("not found", ignoreCase = true) == true)
    ) {
      return executePost(FALLBACK_ENDPOINT_URL, apiKey, bodyJson)
    }
    return primary
  }

  private suspend fun executePost(url: String, apiKey: String, bodyJson: String): Result<JsonObject> {
    val request = Request.Builder()
      .url(url)
      .header("x-goog-api-key", apiKey)
      .header("Content-Type", "application/json")
      .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
      .build()

    return try {
      httpClient.awaitResponse(request).use { resp ->
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          val errMsg = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]
              ?.jsonObject?.get("message")?.jsonPrimitive?.content
          }.getOrNull() ?: "HTTP ${resp.code}"
          return Result.failure(IOException("Planner HTTP error (${resp.code}): $errMsg"))
        }
        Result.success(json.parseToJsonElement(raw).jsonObject)
      }
    } catch (c: CancellationException) {
      throw c
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  companion object {
    const val DEFAULT_MODEL = "gemini-4-hydrogen"
    const val FALLBACK_MODEL = "gemini-2.5-flash"
    const val DEFAULT_ENDPOINT_URL =
      "https://generativelanguage.googleapis.com/v1beta/models/$DEFAULT_MODEL:generateContent"
    const val FALLBACK_ENDPOINT_URL =
      "https://generativelanguage.googleapis.com/v1beta/models/$FALLBACK_MODEL:generateContent"
    const val MAX_PLANNER_TURNS = 14

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val TOOL_NAMES = listOf(
      "get_screen_tree",
      "click_node",
      "input_text",
      "scroll_node",
      "press_key",
      "open_app",
      "reply_notification",
      "ask_user_confirmation",
    )

    private val SYSTEM_INSTRUCTION = """
      You are E.D.I.T.H's autonomous Android UI planner. Think through the user's goal and accomplish it cleanly using Shizuku UI Hands.
      Rules:
      1. Understand intent rather than copying filler words literally (e.g. for "open youtube and play some jazz", open com.google.android.youtube, search a clean query like "jazz music" or "relaxing jazz", inspect the results, and tap an actual video — never stop on a search results page when the user asked to play something).
      2. Common packages you can launch directly with open_app: com.google.android.youtube, com.spotify.music, com.google.android.apps.maps, com.whatsapp, com.android.settings, com.google.android.dialer, com.android.chrome.
      3. Always inspect the active UI with get_screen_tree before calling click_node or input_text. After input_text into a search box, call press_key(66) (ENTER) to submit, then call get_screen_tree to inspect the loaded results.
      4. When picking a video or media item from search results, skip filter chips, tabs, "Shorts", and sponsored/ad cards; tap a real content item (usually has a title/duration/channel in text or desc).
      5. Never touch financial, UPI, banking, password, or installer packages (they are firewall-blocked).
      6. Never transmit OTPs or secrets in input_text or reply_notification.
      7. Call ask_user_confirmation before destructive, financial, or sensitive submissions.
      8. When drafting notification replies via reply_notification or UI input, understand Manglish (Malayalam-English) intent and mirror the contact's <contact_persona> tone and brevity if provided.
      9. Verify on screen that the goal is actually achieved (e.g., playback started or target screen opened), then stop calling tools and reply concisely with what was done.

      ${JarvisLanguagePrompt.MANGLISH_RULEBOOK}
    """.trimIndent()

    internal fun buildToolDeclarations(): JsonArray = buildJsonArray {
      add(
        buildJsonObject {
          put(
            "functionDeclarations",
            buildJsonArray {
              add(
                fnDecl(
                  name = "get_screen_tree",
                  description = "Capture a compact filtered tree of visible/clickable/editable/scrollable nodes on the current screen.",
                  properties = buildJsonObject {
                    put("filter", prop("STRING", "Optional text filter hint"))
                  },
                ),
              )
              add(
                fnDecl(
                  name = "click_node",
                  description = "Tap a node from the latest get_screen_tree result by ephemeral node_id.",
                  properties = buildJsonObject {
                    put("node_id", prop("INTEGER", "Ephemeral integer node id from get_screen_tree"))
                    put("reason", prop("STRING", "Brief intent for audit log"))
                  },
                  required = listOf("node_id"),
                ),
              )
              add(
                fnDecl(
                  name = "input_text",
                  description = "Type text into an editable node id from get_screen_tree.",
                  properties = buildJsonObject {
                    put("node_id", prop("INTEGER", "Editable node id from get_screen_tree"))
                    put("text", prop("STRING", "Text payload to enter"))
                    put("reason", prop("STRING", "Brief intent for audit log"))
                  },
                  required = listOf("node_id", "text"),
                ),
              )
              add(
                fnDecl(
                  name = "scroll_node",
                  description = "Scroll a scrollable container or screen forward or backward.",
                  properties = buildJsonObject {
                    put("node_id", prop("INTEGER", "Optional scrollable node id"))
                    put("direction", prop("STRING", "forward or backward"))
                    put("reason", prop("STRING", "Brief intent for audit log"))
                  },
                ),
              )
              add(
                fnDecl(
                  name = "press_key",
                  description = "Send an Android keyCode via Shizuku input keyevent (e.g. 66 for ENTER, 4 for BACK).",
                  properties = buildJsonObject {
                    put("key_code", prop("INTEGER", "Android KeyEvent keyCode (e.g. 66 for ENTER)"))
                    put("reason", prop("STRING", "Brief intent for audit log"))
                  },
                  required = listOf("key_code"),
                ),
              )
              add(
                fnDecl(
                  name = "open_app",
                  description = "Launch an Android application by package name.",
                  properties = buildJsonObject {
                    put("package_name", prop("STRING", "Target Android package name"))
                    put("reason", prop("STRING", "Brief intent for audit log"))
                  },
                  required = listOf("package_name"),
                ),
              )
              add(
                fnDecl(
                  name = "reply_notification",
                  description = "Reply inline to an active messaging notification key via RemoteInput.",
                  properties = buildJsonObject {
                    put("sbn_key", prop("STRING", "StatusBarNotification key"))
                    put("reply_text", prop("STRING", "Reply text message"))
                    put("contact_name", prop("STRING", "Recipient display name"))
                  },
                  required = listOf("sbn_key", "reply_text", "contact_name"),
                ),
              )
              add(
                fnDecl(
                  name = "ask_user_confirmation",
                  description = "Prompt the user for explicit Tier 3 Biometric confirmation before proceeding.",
                  properties = buildJsonObject {
                    put("action_summary", prop("STRING", "Short summary shown inside BiometricPrompt"))
                    put("risk_reason", prop("STRING", "Why confirmation is required"))
                  },
                  required = listOf("action_summary", "risk_reason"),
                ),
              )
            },
          )
        },
      )
    }

    private fun fnDecl(
      name: String,
      description: String,
      properties: JsonObject,
      required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
      put("name", name)
      put("description", description)
      put(
        "parameters",
        buildJsonObject {
          put("type", "OBJECT")
          put("properties", properties)
          if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
          }
        },
      )
    }

    private fun prop(type: String, description: String): JsonObject = buildJsonObject {
      put("type", type)
      put("description", description)
    }

    fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(35, TimeUnit.SECONDS)
      .writeTimeout(12, TimeUnit.SECONDS)
      .callTimeout(45, TimeUnit.SECONDS)
      .build()
  }
}
