package com.nexwork.edith.jarvis.reflex

import com.nexwork.edith.jarvis.brain.PlannerClient
import com.nexwork.edith.jarvis.core.CompactUiNode
import com.nexwork.edith.jarvis.core.CompactUiTree
import com.nexwork.edith.jarvis.core.PlaybookStep
import com.nexwork.edith.jarvis.core.UiPlaybook
import com.nexwork.edith.jarvis.security.SecurityGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybookExecutorTest {

  private class FakePlaybookStore : PlaybookStore {
    val saved = linkedMapOf<String, UiPlaybook>()

    override fun findPlaybooksForIntent(triggerIntent: String, targetPackage: String?): List<UiPlaybook> {
      val norm = triggerIntent.trim().lowercase()
      return saved.values.filter { pb ->
        (targetPackage.isNullOrBlank() || pb.targetPackage == targetPackage) &&
          (pb.triggerIntent == norm || norm.contains(pb.triggerIntent))
      }
    }

    override fun upsertPlaybook(
      skillId: String,
      triggerIntent: String,
      targetPackage: String,
      steps: List<PlaybookStep>,
      incrementSuccess: Boolean,
    ): Long {
      val prev = saved[skillId]?.successCount ?: 0
      saved[skillId] = UiPlaybook(
        id = (saved.size + 1).toLong(),
        skillId = skillId,
        triggerIntent = triggerIntent,
        targetPackage = targetPackage,
        steps = steps,
        stepsJson = "",
        successCount = if (incrementSuccess) prev + 1 else prev,
      )
      return saved.size.toLong()
    }

    override fun incrementPlaybookSuccess(skillId: String) {
      val cur = saved[skillId] ?: return
      saved[skillId] = cur.copy(successCount = cur.successCount + 1)
    }
  }

  private class FakeEnv(
    private val biometricDecision: Boolean = true,
  ) : PlaybookEnvironment {
    var activePkg = "com.android.settings"
    val actions = mutableListOf<String>()
    var screenNodes = listOf(
      CompactUiNode(id = 42, className = "EditText", viewId = "search_bar", editable = true, clickable = true),
      CompactUiNode(id = 43, className = "Button", viewId = "go_btn", text = "Search", clickable = true),
    )

    override fun currentForegroundPackage(): String = activePkg

    override suspend fun dumpCompactUiTree(): CompactUiTree = CompactUiTree(
      packageName = activePkg,
      nodes = screenNodes,
      formattedText = screenNodes.joinToString("\n") { "[${it.id}] ${it.viewId}" },
    )

    override suspend fun clickNode(id: Int): Result<String> {
      val node = screenNodes.firstOrNull { it.id == id }
        ?: return Result.failure(IllegalArgumentException("missing node $id"))
      actions += "click:${node.viewId}"
      return Result.success("clicked ${node.viewId}")
    }

    override suspend fun inputText(id: Int, text: String): Result<String> {
      val node = screenNodes.firstOrNull { it.id == id }
        ?: return Result.failure(IllegalArgumentException("missing node $id"))
      actions += "input:${node.viewId}:$text"
      return Result.success("typed $text")
    }

    override suspend fun scrollNode(id: Int?, forward: Boolean): Result<String> {
      actions += "scroll:$forward"
      return Result.success("scrolled")
    }

    override fun openApp(packageName: String): Result<String> {
      activePkg = packageName
      actions += "open:$packageName"
      return Result.success("opened $packageName")
    }

    override fun sendNotificationReply(sbnKey: String, replyText: String): Result<String> {
      actions += "reply:$sbnKey:$replyText"
      return Result.success("replied")
    }

    override fun isContactTrusted(contactName: String, packageName: String): Boolean = false

    override suspend fun requestTier3BiometricApproval(
      actionTitle: String,
      actionSummary: String,
      reason: String,
      targetPackage: String,
      contactName: String,
    ): Boolean = biometricDecision
  }

  @Test
  fun replaySavedPlaybook_usesZeroLlmTokensAndParameterizesQuery() = runTest {
    var llmCalled = false
    val client = OkHttpClient.Builder()
      .addInterceptor(Interceptor {
        llmCalled = true
        error("LLM should not be called when saved playbook succeeds")
      })
      .build()

    val store = FakePlaybookStore().apply {
      upsertPlaybook(
        skillId = "youtube_search_youtube",
        triggerIntent = "search youtube",
        targetPackage = "com.google.android.youtube",
        steps = listOf(
          PlaybookStep(action = "open_app", packageName = "com.google.android.youtube"),
          PlaybookStep(action = "input_text", viewId = "search_bar", inputTemplate = "{{query}}"),
          PlaybookStep(action = "click_node", targetText = "Search", viewId = "go_btn"),
        ),
        incrementSuccess = true,
      )
    }
    val env = FakeEnv()
    val executor = PlaybookExecutor(
      env = env,
      store = store,
      security = SecurityGateway("com.nexwork.edith"),
      planner = PlannerClient(httpClient = client),
      settleDelayMs = 0L,
    )

    val result = executor.executeCommand("search youtube for orbital mechanics", apiKey = "AIzaDummy")
    assertTrue(result.ok)
    assertFalse(result.usedLlmPlanner)
    assertFalse(llmCalled)
    assertEquals("youtube_search_youtube", result.usedPlaybookSkillId)
    assertEquals(2, store.saved["youtube_search_youtube"]!!.successCount)
    assertEquals(
      listOf(
        "open:com.google.android.youtube",
        "input:search_bar:orbital mechanics",
        "click:go_btn",
      ),
      env.actions,
    )
  }

  @Test
  fun missingPlaybook_fallsBackToPlannerAndSavesNewParameterizedPlaybook() = runTest {
    var turn = 0
    val client = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        turn++
        val payload = when (turn) {
          1 -> """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"open_app","args":{"package_name":"com.google.android.youtube"}}}]}}]}"""
          2 -> """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_screen_tree","args":{}}}]}}]}"""
          3 -> """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"input_text","args":{"node_id":42,"text":"bebop jazz"}}}]}}]}"""
          4 -> """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"click_node","args":{"node_id":43}}}]}}]}"""
          else -> """{"candidates":[{"content":{"parts":[{"text":"Searched YouTube for bebop jazz."}]}}]}"""
        }
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(payload.toResponseBody("application/json".toMediaType()))
          .build()
      })
      .build()

    val store = FakePlaybookStore()
    val env = FakeEnv()
    val executor = PlaybookExecutor(
      env = env,
      store = store,
      security = SecurityGateway("com.nexwork.edith"),
      planner = PlannerClient(httpClient = client),
      settleDelayMs = 0L,
    )

    val res = executor.executeCommand("search youtube for bebop jazz", apiKey = "AIzaDummyKey")
    assertTrue(res.ok)
    assertTrue(res.usedLlmPlanner)
    assertEquals("youtube_search_youtube", res.savedPlaybookSkillId)

    val saved = store.saved["youtube_search_youtube"]!!
    assertEquals(3, saved.steps.size)
    assertEquals("{{query}}", saved.steps[1].inputTemplate)
  }

  @Test
  fun dlpOtpBlockedDuringReplay_neverFallsBackToLlm() = runTest {
    var llmCalled = false
    val client = OkHttpClient.Builder()
      .addInterceptor(Interceptor {
        llmCalled = true
        error("LLM must not run after security firewall denial")
      })
      .build()

    val store = FakePlaybookStore().apply {
      upsertPlaybook(
        skillId = "notes_type",
        triggerIntent = "paste secret",
        targetPackage = "com.example.notes",
        steps = listOf(
          PlaybookStep(action = "open_app", packageName = "com.example.notes"),
          PlaybookStep(action = "input_text", viewId = "search_bar", inputTemplate = "{{query}}"),
        ),
        incrementSuccess = true,
      )
    }
    val env = FakeEnv()
    val executor = PlaybookExecutor(
      env = env,
      store = store,
      security = SecurityGateway("com.nexwork.edith"),
      planner = PlannerClient(httpClient = client),
      settleDelayMs = 0L,
    )

    val res = executor.executeCommand("paste secret: 482910", apiKey = "AIzaDummyKey")
    assertFalse(res.ok)
    assertTrue(res.securityBlocked)
    assertFalse(llmCalled)
    assertTrue(res.summary.contains("DLP"))
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun replayPlaybook_cancellationHaltsRemainingUiSteps() = runTest {
    val store = FakePlaybookStore().apply {
      upsertPlaybook(
        skillId = "settings_search",
        triggerIntent = "search settings",
        targetPackage = "com.android.settings",
        steps = listOf(
          PlaybookStep(action = "open_app", packageName = "com.android.settings"),
          PlaybookStep(action = "input_text", viewId = "search_bar", inputTemplate = "{{query}}"),
          PlaybookStep(action = "click_node", viewId = "go_btn"),
        ),
        incrementSuccess = true,
      )
    }
    val env = FakeEnv()
    val executor = PlaybookExecutor(
      env = env,
      store = store,
      security = SecurityGateway("com.nexwork.edith"),
      settleDelayMs = 2_000L,
    )

    var aborted = false
    val job = launch {
      try {
        executor.executeCommand("search settings for wifi", apiKey = null)
      } catch (c: CancellationException) {
        aborted = true
        throw c
      }
    }
    advanceTimeBy(100L)
    runCurrent()
    assertEquals(listOf("open:com.android.settings"), env.actions)

    job.cancel(CancellationException("Physical stop pressed"))
    advanceTimeBy(10_000L)
    runCurrent()

    assertTrue(aborted)
    assertEquals(listOf("open:com.android.settings"), env.actions)
  }

  @Test
  fun directMediaCommand_openYoutubeAndPlayJazz_parsesCleanly() {
    val parsed = PlaybookExecutor.parseDirectMediaCommand("open YouTube and play jazz")
    assertEquals("com.google.android.youtube", parsed?.targetPackage)
    assertEquals("jazz", parsed?.query)
    assertEquals(true, parsed?.playImmediately)

    val spotify = PlaybookExecutor.parseDirectMediaCommand("play lo-fi beats on Spotify")
    assertEquals("com.spotify.music", spotify?.targetPackage)
    assertEquals("lo-fi beats", spotify?.query)
  }
}
