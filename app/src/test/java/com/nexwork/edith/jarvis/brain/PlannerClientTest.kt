package com.nexwork.edith.jarvis.brain

import com.nexwork.edith.jarvis.core.PlaybookStep
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerClientTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun runPlan_callsGemini4HydrogenWithAll7ToolsAndHandlesMultiTurnFunctionCalling() = runTest {
    var turnCount = 0
    var capturedUrl = ""
    var firstRequestBody = ""

    val client = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        val req = chain.request()
        capturedUrl = req.url.toString()
        val sink = Buffer()
        req.body?.writeTo(sink)
        if (turnCount == 0) {
          firstRequestBody = sink.readUtf8()
        }
        turnCount++

        val replyJson = when (turnCount) {
          1 -> """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "functionCall": {
                      "name": "open_app",
                      "args": {"package_name": "com.google.android.youtube", "reason": "Open target"}
                    }
                  }]
                }
              }]
            }
          """.trimIndent()
          2 -> """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "functionCall": {
                      "name": "click_node",
                      "args": {"node_id": 1, "reason": "Tap search bar"}
                    }
                  }]
                }
              }]
            }
          """.trimIndent()
          else -> """
            {
              "candidates": [{
                "content": {
                  "parts": [{"text": "Opened YouTube and focused search."}]
                }
              }]
            }
          """.trimIndent()
        }

        Response.Builder()
          .request(req)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(replyJson.toResponseBody("application/json".toMediaType()))
          .build()
      })
      .build()

    val executedTools = mutableListOf<String>()
    val bridge = object : PlannerToolBridge {
      override suspend fun executeTool(name: String, args: JsonObject): PlannerToolOutcome {
        executedTools += name
        return when (name) {
          "open_app" -> PlannerToolOutcome(
            ok = true,
            summary = "Launched YouTube",
            payload = buildJsonObject { put("status", "ok") },
            recordedStep = PlaybookStep(action = "open_app", packageName = "com.google.android.youtube"),
            resolvedTargetPackage = "com.google.android.youtube",
          )
          else -> PlannerToolOutcome(
            ok = true,
            summary = "Clicked node 1",
            payload = buildJsonObject { put("status", "ok") },
            recordedStep = PlaybookStep(action = "click_node", viewId = "search_btn"),
            resolvedTargetPackage = "com.google.android.youtube",
          )
        }
      }
    }

    val planner = PlannerClient(httpClient = client)
    val res = planner.runPlan("AIzaTestKey123456", "Open YouTube search", bridge = bridge)
    assertTrue(res.isSuccess)

    assertEquals(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent",
      capturedUrl,
    )

    val parsedReq = json.parseToJsonElement(firstRequestBody).jsonObject
    val decls = parsedReq["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
    val declaredNames = decls.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertEquals(PlannerClient.TOOL_NAMES, declaredNames)

    val run = res.getOrThrow()
    assertEquals(listOf("open_app", "click_node"), executedTools)
    assertEquals(2, run.recordedSteps.size)
    assertEquals("com.google.android.youtube", run.targetPackage)
    assertEquals("Opened YouTube and focused search.", run.summary)
  }
}
