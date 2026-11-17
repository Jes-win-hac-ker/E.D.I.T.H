package com.nexwork.edith.jarvis.brain

import com.nexwork.edith.jarvis.core.PlaybookStep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

  @Test
  fun runPlan_cancellationAbortsInFlightOkHttpCallImmediately() = runBlocking {
    val requestStarted = CountDownLatch(1)
    val releaseRequest = CountDownLatch(1)
    val observedCanceledFlag = AtomicBoolean(false)

    val client = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        val call = chain.call()
        requestStarted.countDown()
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadlineNs && !call.isCanceled()) {
          Thread.sleep(15)
        }
        observedCanceledFlag.set(call.isCanceled())
        releaseRequest.countDown()
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body("""{"candidates":[]}""".toResponseBody("application/json".toMediaType()))
          .build()
      })
      .build()

    val planner = PlannerClient(httpClient = client)
    val bridge = object : PlannerToolBridge {
      override suspend fun executeTool(name: String, args: JsonObject) = error("must not run tools")
    }

    var caughtCancel = false
    val job = launch(Dispatchers.IO) {
      try {
        planner.runPlan("AIzaTestKey123456", "Slow hanging plan", bridge = bridge)
      } catch (c: CancellationException) {
        caughtCancel = true
        throw c
      }
    }

    assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
    job.cancel(CancellationException("User pressed physical STOP"))
    job.join()

    assertTrue(releaseRequest.await(2, TimeUnit.SECONDS))
    assertTrue(job.isCancelled)
    assertTrue(caughtCancel)
    assertTrue(observedCanceledFlag.get())
  }

  @Test
  fun runPlan_includesManglishRulebookAndContactPersonaNoteInPrompt() = runTest {
    var capturedBody = ""
    val client = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val sink = Buffer()
        req.body?.writeTo(sink)
        capturedBody = sink.readUtf8()
        val okJson = """{"candidates":[{"content":{"parts":[{"text":"Replied in Manglish."}]}}]}"""
        Response.Builder()
          .request(req)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(okJson.toResponseBody("application/json".toMediaType()))
          .build()
      })
      .build()

    val planner = PlannerClient(httpClient = client)
    val bridge = object : PlannerToolBridge {
      override suspend fun executeTool(name: String, args: JsonObject) = error("unused")
    }
    val personaNote = "<contact_persona>\nrelationship_tag: friend\nsent_samples: \"aa da\"\n</contact_persona>"
    val res = planner.runPlan(
      apiKey = "AIzaTestKey123456",
      goalPrompt = "Reply to Arjun in WhatsApp",
      bridge = bridge,
      personaContextNote = personaNote,
    )
    assertTrue(res.isSuccess)
    assertTrue(capturedBody.contains("manglish_rulebook"))
    assertTrue(capturedBody.contains("aa da"))
  }
}
