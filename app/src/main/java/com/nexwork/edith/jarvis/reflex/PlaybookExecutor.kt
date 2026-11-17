package com.nexwork.edith.jarvis.reflex

import com.nexwork.edith.jarvis.brain.PlannerClient
import com.nexwork.edith.jarvis.brain.PlannerToolBridge
import com.nexwork.edith.jarvis.brain.PlannerToolOutcome
import com.nexwork.edith.jarvis.core.CompactUiNode
import com.nexwork.edith.jarvis.core.CompactUiTree
import com.nexwork.edith.jarvis.core.PlaybookStep
import com.nexwork.edith.jarvis.core.UiPlaybook
import com.nexwork.edith.jarvis.security.JarvisActionType
import com.nexwork.edith.jarvis.security.SecurityActionRequest
import com.nexwork.edith.jarvis.security.SecurityGateway
import com.nexwork.edith.jarvis.security.SecurityVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Abstraction over Shizuku (`uid 2000`) UI Hands, App Launcher, RemoteInput, Storage, and Biometric
 * approval so [PlaybookExecutor] runs natively on-device and tests deterministically on JVM without mocks.
 */
interface PlaybookEnvironment {
  fun currentForegroundPackage(): String
  suspend fun dumpCompactUiTree(): CompactUiTree
  suspend fun clickNode(id: Int): Result<String>
  suspend fun tap(x: Int, y: Int): Result<String> = Result.success("Tapped ($x,$y)")
  suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 240): Result<String> =
    Result.success("Swiped ($x1,$y1)->($x2,$y2)")
  suspend fun inputText(id: Int, text: String): Result<String>
  suspend fun scrollNode(id: Int?, forward: Boolean): Result<String>
  suspend fun pressKey(keyCode: Int): Result<String> = Result.success("Sent keyevent $keyCode")
  fun openApp(packageName: String): Result<String>
  fun launchMediaOrSearchIntent(
    targetPackage: String,
    query: String,
    playImmediately: Boolean,
  ): Result<String> = Result.failure(UnsupportedOperationException("Direct intent not supported"))
  fun sendNotificationReply(sbnKey: String, replyText: String): Result<String>
  fun isContactTrusted(contactName: String, packageName: String): Boolean
  suspend fun requestTier3BiometricApproval(
    actionTitle: String,
    actionSummary: String,
    reason: String,
    targetPackage: String = "",
    contactName: String = "",
  ): Boolean
}

interface PlaybookStore {
  fun findPlaybooksForIntent(triggerIntent: String, targetPackage: String? = null): List<UiPlaybook>
  fun upsertPlaybook(
    skillId: String,
    triggerIntent: String,
    targetPackage: String,
    steps: List<PlaybookStep>,
    incrementSuccess: Boolean,
  ): Long
  fun incrementPlaybookSuccess(skillId: String)
}

data class CommandExecutionResult(
  val ok: Boolean,
  val summary: String,
  val usedPlaybookSkillId: String? = null,
  val usedLlmPlanner: Boolean = false,
  val savedPlaybookSkillId: String? = null,
  val traceLog: List<String> = emptyList(),
  val securityBlocked: Boolean = false,
)

/**
 * Coordinates local zero-token UI playbook replay and guarded fallback to [PlannerClient]
 * (`gemini-4-hydrogen`), enforcing [SecurityGateway] at every action boundary.
 */
class PlaybookExecutor(
  private val env: PlaybookEnvironment,
  private val store: PlaybookStore,
  private val security: SecurityGateway,
  private val planner: PlannerClient = PlannerClient(),
  private val settleDelayMs: Long = DEFAULT_STEP_DELAY_MS,
  private val resolvePersonaContext: (String) -> String = { "" },
) {

  suspend fun executeCommand(
    commandText: String,
    apiKey: String?,
    targetPackageHint: String? = null,
    explicitParams: Map<String, String> = emptyMap(),
  ): CommandExecutionResult {
    val parsed = parseCommandIntent(commandText, explicitParams)
    if (parsed.intent.isBlank()) {
      return CommandExecutionResult(
        ok = false,
        summary = "Command cannot be blank",
      )
    }

    val traceLog = mutableListOf<String>()
    val candidates = store.findPlaybooksForIntent(parsed.intent, targetPackageHint)

    // 1. Attempt local zero-LLM replay first
    for (playbook in candidates) {
      currentCoroutineContext().ensureActive()
      traceLog += "Playbook hit: ${playbook.skillId} (0 LLM tokens)"
      val replayOutcome = replayPlaybook(playbook, parsed.params, traceLog)
      when (replayOutcome) {
        is ReplayResult.Success -> {
          store.incrementPlaybookSuccess(playbook.skillId)
          return CommandExecutionResult(
            ok = true,
            summary = "Replayed ${playbook.skillId} locally (${playbook.steps.size} steps, 0 LLM tokens)",
            usedPlaybookSkillId = playbook.skillId,
            usedLlmPlanner = false,
            traceLog = traceLog,
          )
        }
        is ReplayResult.SecurityBlocked -> {
          return CommandExecutionResult(
            ok = false,
            summary = replayOutcome.reason,
            usedPlaybookSkillId = playbook.skillId,
            usedLlmPlanner = false,
            traceLog = traceLog,
            securityBlocked = true,
          )
        }
        is ReplayResult.StepFailed -> {
          traceLog += "Replay fallback (${playbook.skillId}): ${replayOutcome.reason}"
        }
      }
    }

    // 1.5. Zero-Shizuku Direct Intent Fast-Path (YouTube / Spotify / Maps media & search)
    parseDirectMediaCommand(commandText)?.let { directCmd ->
      val dlpBlock = SecurityGateway.inspectOutboundText(directCmd.query)
      if (dlpBlock != null) {
        return CommandExecutionResult(
          ok = false,
          summary = dlpBlock,
          traceLog = traceLog,
          securityBlocked = true,
        )
      }
      val verdict = security.evaluateAndConsume(
        SecurityActionRequest(
          actionType = JarvisActionType.OPEN_APP,
          activePackage = env.currentForegroundPackage(),
          targetPackage = directCmd.targetPackage,
          targetLabel = directCmd.targetPackage,
          runStepIndex = 0,
        ),
      )
      if (verdict is SecurityVerdict.Deny) {
        return CommandExecutionResult(
          ok = false,
          summary = verdict.reason,
          traceLog = traceLog,
          securityBlocked = true,
        )
      }
      val launchRes = env.launchMediaOrSearchIntent(
        targetPackage = directCmd.targetPackage,
        query = directCmd.query,
        playImmediately = directCmd.playImmediately,
      )
      if (launchRes.isSuccess) {
        val detail = launchRes.getOrDefault("Launched ${directCmd.targetPackage}")
        traceLog += "Direct intent fast-path: $detail (0 LLM tokens)"
        if (directCmd.playImmediately && directCmd.targetPackage == "com.google.android.youtube") {
          if (settleDelayMs > 0L) delay(settleDelayMs * 3)
          val tree = env.dumpCompactUiTree()
          if (tree.blockedReason == null && tree.nodes.isNotEmpty()) {
            val videoNode = tree.nodes.firstOrNull { n ->
              n.clickable && n.top > 210 && (n.text.length >= 4 || n.contentDesc.length >= 6) &&
                !n.viewId.contains("search", ignoreCase = true)
            }
            if (videoNode != null) {
              env.clickNode(videoNode.id)
              traceLog += "Auto-tapped top video result id=${videoNode.id}"
            }
          }
        }
        return CommandExecutionResult(
          ok = true,
          summary = detail,
          usedLlmPlanner = false,
          traceLog = traceLog,
        )
      }
    }

    // 2. Fallback to PlannerClient (gemini-4-hydrogen Function Calling)
    val resolvedKey = apiKey?.trim().orEmpty()
    if (resolvedKey.isEmpty()) {
      return CommandExecutionResult(
        ok = false,
        summary = if (candidates.isEmpty()) {
          "No saved playbook for '${parsed.intent}' and Gemini API key is unset"
        } else {
          "Playbook replay failed and Gemini API key is unset"
        },
        traceLog = traceLog,
      )
    }

    traceLog += "Invoking gemini-4-hydrogen planner fallback..."
    val bridge = GuardedPlannerBridge(
      env = env,
      security = security,
      traceLog = traceLog,
      initialExpectedPkg = targetPackageHint,
    )

    val planResult = planner.runPlan(
      apiKey = resolvedKey,
      goalPrompt = commandText,
      initialContextNote = buildString {
        if (!targetPackageHint.isNullOrBlank()) append("target_package=").append(targetPackageHint)
      },
      bridge = bridge,
      personaContextNote = resolvePersonaContext(commandText),
    )

    return planResult.fold(
      onSuccess = { run ->
        traceLog.addAll(run.traceLog)
        var savedSkillId: String? = null
        val meaningfulSteps = run.recordedSteps.filter { it.action != "get_screen_tree" }
        val resolvedPkg = run.targetPackage.ifBlank { targetPackageHint.orEmpty() }.ifBlank { "unknown" }
        if (meaningfulSteps.size >= 2) {
          val skillId = buildSkillId(parsed.intent, resolvedPkg)
          val parameterized = parameterizeSteps(meaningfulSteps, parsed.params)
          store.upsertPlaybook(
            skillId = skillId,
            triggerIntent = parsed.intent,
            targetPackage = resolvedPkg,
            steps = parameterized,
            incrementSuccess = true,
          )
          savedSkillId = skillId
          traceLog += "Saved new playbook: $skillId (${parameterized.size} steps)"
        }
        CommandExecutionResult(
          ok = true,
          summary = run.summary,
          usedLlmPlanner = true,
          savedPlaybookSkillId = savedSkillId,
          traceLog = traceLog,
        )
      },
      onFailure = { err ->
        if (err is CancellationException) throw err
        CommandExecutionResult(
          ok = false,
          summary = err.message ?: "Planner execution failed",
          usedLlmPlanner = true,
          traceLog = traceLog,
          securityBlocked = err is SecurityException,
        )
      },
    )
  }

  private sealed interface ReplayResult {
    data object Success : ReplayResult
    data class SecurityBlocked(val reason: String) : ReplayResult
    data class StepFailed(val reason: String) : ReplayResult
  }

  private suspend fun replayPlaybook(
    playbook: UiPlaybook,
    params: Map<String, String>,
    traceLog: MutableList<String>,
  ): ReplayResult {
    var expectedPkg: String? = playbook.targetPackage.takeIf { it.isNotBlank() && it != "unknown" }
    var runStepCount = 0

    for ((idx, step) in playbook.steps.withIndex()) {
      currentCoroutineContext().ensureActive()
      when (step.action) {
        "open_app" -> {
          val pkg = resolveTemplate(step.packageName ?: playbook.targetPackage, params)
          val verdict = security.evaluateAndConsume(
            SecurityActionRequest(
              actionType = JarvisActionType.OPEN_APP,
              activePackage = env.currentForegroundPackage(),
              targetPackage = pkg,
              targetLabel = pkg,
              runStepIndex = runStepCount,
            ),
          )
          when (verdict) {
            is SecurityVerdict.Deny -> return ReplayResult.SecurityBlocked(verdict.reason)
            is SecurityVerdict.RequireBiometric -> {
              if (!env.requestTier3BiometricApproval("OPEN APP", verdict.actionSummary, verdict.reason, pkg)) {
                return ReplayResult.SecurityBlocked("User denied Tier 3 biometric confirmation")
              }
            }
            is SecurityVerdict.Allow -> Unit
          }
          val res = env.openApp(pkg)
          if (res.isFailure) return ReplayResult.StepFailed("open_app($pkg) failed: ${res.exceptionOrNull()?.message}")
          expectedPkg = pkg
          runStepCount++
          traceLog += "step#${idx + 1} open_app($pkg)"
          if (settleDelayMs > 0L) delay(settleDelayMs)
        }

        "click_node", "input_text", "scroll_node" -> {
          val treeVerdict = security.evaluateAndConsume(
            SecurityActionRequest(
              actionType = JarvisActionType.GET_SCREEN_TREE,
              activePackage = env.currentForegroundPackage(),
              expectedPackage = expectedPkg,
            ),
          )
          if (treeVerdict is SecurityVerdict.Deny) return ReplayResult.SecurityBlocked(treeVerdict.reason)

          val tree = env.dumpCompactUiTree()
          if (tree.blockedReason != null) {
            return ReplayResult.SecurityBlocked(tree.blockedReason)
          }

          val targetNode = resolveTargetNode(tree, step)
            ?: return ReplayResult.StepFailed("Target node missing (${step.viewId ?: step.targetText ?: step.targetDesc})")

          val label = targetNode.text.ifBlank { targetNode.contentDesc }.ifBlank { targetNode.viewId }
          val actionType = when (step.action) {
            "click_node" -> JarvisActionType.CLICK_NODE
            "input_text" -> JarvisActionType.INPUT_TEXT
            else -> JarvisActionType.SCROLL_NODE
          }
          val textPayload = if (step.action == "input_text") {
            resolveTemplate(step.inputTemplate.orEmpty(), params)
          } else {
            null
          }

          val verdict = security.evaluateAndConsume(
            SecurityActionRequest(
              actionType = actionType,
              activePackage = tree.packageName,
              expectedPackage = expectedPkg,
              targetPackage = tree.packageName,
              targetLabel = label,
              outboundText = textPayload,
              runStepIndex = runStepCount,
            ),
          )
          when (verdict) {
            is SecurityVerdict.Deny -> return ReplayResult.SecurityBlocked(verdict.reason)
            is SecurityVerdict.RequireBiometric -> {
              if (!env.requestTier3BiometricApproval("CONFIRM UI STEP", verdict.actionSummary, verdict.reason, tree.packageName)) {
                return ReplayResult.SecurityBlocked("User denied Tier 3 biometric confirmation")
              }
            }
            is SecurityVerdict.Allow -> Unit
          }

          val execRes = when (step.action) {
            "click_node" -> env.clickNode(targetNode.id)
            "input_text" -> env.inputText(targetNode.id, textPayload.orEmpty())
            else -> env.scrollNode(targetNode.id, step.scrollForward)
          }
          if (execRes.isFailure) {
            return ReplayResult.StepFailed("${step.action} failed: ${execRes.exceptionOrNull()?.message}")
          }
          runStepCount++
          traceLog += "step#${idx + 1} ${step.action}($label)"
          if (settleDelayMs > 0L) delay(settleDelayMs)
        }

        else -> return ReplayResult.StepFailed("Unknown step action: ${step.action}")
      }
    }
    return ReplayResult.Success
  }

  private class GuardedPlannerBridge(
    private val env: PlaybookEnvironment,
    private val security: SecurityGateway,
    private val traceLog: MutableList<String>,
    initialExpectedPkg: String?,
  ) : PlannerToolBridge {

    private var latestTree: CompactUiTree? = null
    private var expectedPkg: String? = initialExpectedPkg?.takeIf { it.isNotBlank() }
    private var uiStepCount = 0

    override suspend fun executeTool(name: String, args: JsonObject): PlannerToolOutcome {
      return when (name) {
        "get_screen_tree" -> handleGetScreenTree()
        "open_app" -> handleOpenApp(args)
        "click_node" -> handleClickNode(args)
        "input_text" -> handleInputText(args)
        "scroll_node" -> handleScrollNode(args)
        "press_key" -> handlePressKey(args)
        "reply_notification" -> handleReplyNotification(args)
        "ask_user_confirmation" -> handleAskUserConfirmation(args)
        else -> PlannerToolOutcome(
          ok = false,
          summary = "Unsupported tool: $name",
          payload = errorJson("Unsupported tool: $name"),
        )
      }
    }

    private suspend fun handleGetScreenTree(): PlannerToolOutcome {
      val fg = env.currentForegroundPackage()
      val verdict = security.evaluateAndConsume(
        SecurityActionRequest(
          actionType = JarvisActionType.GET_SCREEN_TREE,
          activePackage = fg,
          expectedPackage = expectedPkg,
        ),
      )
      if (verdict is SecurityVerdict.Deny) {
        return PlannerToolOutcome(
          ok = false,
          summary = verdict.reason,
          payload = errorJson(verdict.reason),
          securityBlocked = true,
        )
      }
      val tree = env.dumpCompactUiTree()
      if (tree.blockedReason != null) {
        return PlannerToolOutcome(
          ok = false,
          summary = tree.blockedReason,
          payload = errorJson(tree.blockedReason),
          securityBlocked = true,
        )
      }
      latestTree = tree
      if (tree.packageName.isNotBlank()) expectedPkg = tree.packageName
      return PlannerToolOutcome(
        ok = true,
        summary = "tree(${tree.packageName}, ${tree.nodes.size} nodes)",
        payload = buildJsonObject {
          put("package_name", tree.packageName)
          put("window_title", tree.windowTitle)
          put("ui_tree", tree.formattedText)
        },
        resolvedTargetPackage = tree.packageName,
      )
    }

    private fun handleOpenApp(args: JsonObject): PlannerToolOutcome {
      val pkg = args["package_name"]?.jsonPrimitive?.content?.trim().orEmpty()
      val verdict = security.evaluateAndConsume(
        SecurityActionRequest(
          actionType = JarvisActionType.OPEN_APP,
          activePackage = env.currentForegroundPackage(),
          targetPackage = pkg,
          targetLabel = pkg,
          runStepIndex = uiStepCount,
        ),
      )
      if (verdict is SecurityVerdict.Deny) {
        return PlannerToolOutcome(
          ok = false,
          summary = verdict.reason,
          payload = errorJson(verdict.reason),
          securityBlocked = true,
        )
      }
      val res = env.openApp(pkg)
      return res.fold(
        onSuccess = { msg ->
          expectedPkg = pkg
          latestTree = null
          uiStepCount++
          PlannerToolOutcome(
            ok = true,
            summary = msg,
            payload = buildJsonObject { put("status", "ok"); put("detail", msg) },
            recordedStep = PlaybookStep(action = "open_app", packageName = pkg),
            resolvedTargetPackage = pkg,
          )
        },
        onFailure = { err ->
          val m = err.message ?: "Failed to open $pkg"
          PlannerToolOutcome(ok = false, summary = m, payload = errorJson(m))
        },
      )
    }

    private suspend fun handleClickNode(args: JsonObject): PlannerToolOutcome {
      val nodeId = args["node_id"]?.jsonPrimitive?.intOrNull
        ?: return PlannerToolOutcome(ok = false, summary = "Missing node_id", payload = errorJson("Missing node_id"))
      val fg = env.currentForegroundPackage()
      val node = latestTree?.nodes?.firstOrNull { it.id == nodeId }
      val label = node?.let { it.text.ifBlank { it.contentDesc }.ifBlank { it.viewId } }.orEmpty()

      val verdict = security.evaluateAndConsume(
        SecurityActionRequest(
          actionType = JarvisActionType.CLICK_NODE,
          activePackage = fg,
          expectedPackage = expectedPkg,
          targetPackage = fg,
          targetLabel = label,
          runStepIndex = uiStepCount,
          reason = args["reason"]?.jsonPrimitive?.content.orEmpty(),
        ),
      )
      when (verdict) {
        is SecurityVerdict.Deny -> return PlannerToolOutcome(
          ok = false,
          summary = verdict.reason,
          payload = errorJson(verdict.reason),
          securityBlocked = true,
        )
        is SecurityVerdict.RequireBiometric -> {
          val ok = env.requestTier3BiometricApproval("CONFIRM CLICK", verdict.actionSummary, verdict.reason, fg)
          if (!ok) {
            return PlannerToolOutcome(
              ok = false,
              summary = "Tier 3 biometric approval denied for click",
              payload = errorJson("User denied Tier 3 biometric confirmation"),
              securityBlocked = true,
            )
          }
        }
        is SecurityVerdict.Allow -> Unit
      }

      val res = env.clickNode(nodeId)
      return res.fold(
        onSuccess = { msg ->
          uiStepCount++
          val step = PlaybookStep(
            action = "click_node",
            targetText = node?.text?.takeIf { it.isNotBlank() },
            targetDesc = node?.contentDesc?.takeIf { it.isNotBlank() },
            viewId = node?.viewId?.takeIf { it.isNotBlank() },
          )
          PlannerToolOutcome(
            ok = true,
            summary = msg,
            payload = buildJsonObject { put("status", "ok"); put("detail", msg) },
            recordedStep = step,
            resolvedTargetPackage = fg,
          )
        },
        onFailure = { err ->
          val m = err.message ?: "Click failed"
          PlannerToolOutcome(ok = false, summary = m, payload = errorJson(m))
        },
      )
    }

    private suspend fun handleInputText(args: JsonObject): PlannerToolOutcome {
      val nodeId = args["node_id"]?.jsonPrimitive?.intOrNull
        ?: return PlannerToolOutcome(ok = false, summary = "Missing node_id", payload = errorJson("Missing node_id"))
      val text = args["text"]?.jsonPrimitive?.content.orEmpty()
      val fg = env.currentForegroundPackage()
      val node = latestTree?.nodes?.firstOrNull { it.id == nodeId }
      val label = node?.let { it.text.ifBlank { it.contentDesc }.ifBlank { it.viewId } }.orEmpty()

      val verdict = security.evaluateAndConsume(
        SecurityActionRequest(
          actionType = JarvisActionType.INPUT_TEXT,
          activePackage = fg,
          expectedPackage = expectedPkg,
          targetPackage = fg,
          targetLabel = label,
          outboundText = text,
          runStepIndex = uiStepCount,
          reason = args["reason"]?.jsonPrimitive?.content.orEmpty(),
        ),
      )
      when (verdict) {
        is SecurityVerdict.Deny -> return PlannerToolOutcome(
          ok = false,
          summary = verdict.reason,
          payload = errorJson(verdict.reason),
          securityBlocked = true,
        )
        is SecurityVerdict.RequireBiometric -> {
          val ok = env.requestTier3BiometricApproval("CONFIRM INPUT", verdict.actionSummary, verdict.reason, fg)
          if (!ok) {
            return PlannerToolOutcome(
              ok = false,
              summary = "Tier 3 biometric approval denied for input",
              payload = errorJson("User denied Tier 3 biometric confirmation"),
              securityBlocked = true,
            )
          }
        }
        is SecurityVerdict.Allow -> Unit
      }

      val res = env.inputText(nodeId, text)
      return res.fold(
        onSuccess = { msg ->
          uiStepCount++
          val step = PlaybookStep(
            action = "input_text",
            targetText = node?.text?.takeIf { it.isNotBlank() },
            targetDesc = node?.contentDesc?.takeIf { it.isNotBlank() },
            viewId = node?.viewId?.takeIf { it.isNotBlank() },
            inputTemplate = text,
          )
          PlannerToolOutcome(
            ok = true,
            summary = msg,
            payload = buildJsonObject { put("status", "ok"); put("detail", msg) },
            recordedStep = step,
            resolvedTargetPackage = fg,
          )
        },
        onFailure = { err ->
          val m = err.message ?: "Input failed"
          PlannerToolOutcome(ok = false, summary = m, payload = errorJson(m))
        },
      )
    }

    private suspend fun handleScrollNode(args: JsonObject): PlannerToolOutcome {
      val nodeId = args["node_id"]?.jsonPrimitive?.intOrNull
      val forward = !args["direction"]?.jsonPrimitive?.content.equals("backward", ignoreCase = true)
      val fg = env.currentForegroundPackage()
      val node = nodeId?.let { id -> latestTree?.nodes?.firstOrNull { it.id == id } }

      val verdict = security.evaluateAndConsume(
        SecurityActionRequest(
          actionType = JarvisActionType.SCROLL_NODE,
          activePackage = fg,
          expectedPackage = expectedPkg,
          targetPackage = fg,
          runStepIndex = uiStepCount,
        ),
      )
      if (verdict is SecurityVerdict.Deny) {
        return PlannerToolOutcome(
          ok = false,
          summary = verdict.reason,
          payload = errorJson(verdict.reason),
          securityBlocked = true,
        )
      }

      val res = env.scrollNode(nodeId, forward)
      return res.fold(
        onSuccess = { msg ->
          uiStepCount++
          PlannerToolOutcome(
            ok = true,
            summary = msg,
            payload = buildJsonObject { put("status", "ok"); put("detail", msg) },
            recordedStep = PlaybookStep(
              action = "scroll_node",
              viewId = node?.viewId?.takeIf { it.isNotBlank() },
              scrollForward = forward,
            ),
            resolvedTargetPackage = fg,
          )
        },
        onFailure = { err ->
          val m = err.message ?: "Scroll failed"
          PlannerToolOutcome(ok = false, summary = m, payload = errorJson(m))
        },
      )
    }

    private suspend fun handlePressKey(args: JsonObject): PlannerToolOutcome {
      val keyCode = args["key_code"]?.jsonPrimitive?.intOrNull
        ?: return PlannerToolOutcome(ok = false, summary = "Missing key_code", payload = errorJson("Missing key_code"))
      val fg = env.currentForegroundPackage()
      val verdict = security.evaluateAndConsume(
        SecurityActionRequest(
          actionType = JarvisActionType.CLICK_NODE,
          activePackage = fg,
          expectedPackage = expectedPkg,
          targetPackage = fg,
          targetLabel = "keyevent:$keyCode",
          runStepIndex = uiStepCount,
          reason = args["reason"]?.jsonPrimitive?.content.orEmpty(),
        ),
      )
      if (verdict is SecurityVerdict.Deny) {
        return PlannerToolOutcome(
          ok = false,
          summary = verdict.reason,
          payload = errorJson(verdict.reason),
          securityBlocked = true,
        )
      }
      val res = env.pressKey(keyCode)
      return res.fold(
        onSuccess = { msg ->
          uiStepCount++
          PlannerToolOutcome(
            ok = true,
            summary = msg,
            payload = buildJsonObject { put("status", "ok"); put("detail", msg) },
            resolvedTargetPackage = fg,
          )
        },
        onFailure = { err ->
          val m = err.message ?: "Keyevent failed"
          PlannerToolOutcome(ok = false, summary = m, payload = errorJson(m))
        },
      )
    }

    private suspend fun handleReplyNotification(args: JsonObject): PlannerToolOutcome {
      val sbnKey = args["sbn_key"]?.jsonPrimitive?.content.orEmpty()
      val replyText = args["reply_text"]?.jsonPrimitive?.content.orEmpty()
      val contactName = args["contact_name"]?.jsonPrimitive?.content.orEmpty()
      val pkgGuess = sbnKey.split('|').getOrNull(1).orEmpty()
      val trusted = env.isContactTrusted(contactName, pkgGuess)

      val verdict = security.evaluateAndConsume(
        SecurityActionRequest(
          actionType = JarvisActionType.REPLY_NOTIFICATION,
          activePackage = pkgGuess,
          targetPackage = pkgGuess,
          outboundText = replyText,
          contactName = contactName,
          isTrustedContact = trusted,
        ),
      )
      when (verdict) {
        is SecurityVerdict.Deny -> return PlannerToolOutcome(
          ok = false,
          summary = verdict.reason,
          payload = errorJson(verdict.reason),
          securityBlocked = true,
        )
        is SecurityVerdict.RequireBiometric -> {
          val ok = env.requestTier3BiometricApproval(
            actionTitle = "UNTRUSTED REPLY",
            actionSummary = "Send '$replyText' to $contactName",
            reason = verdict.reason,
            targetPackage = pkgGuess,
            contactName = contactName,
          )
          if (!ok) {
            return PlannerToolOutcome(
              ok = false,
              summary = "User denied Tier 3 biometric confirmation for reply",
              payload = errorJson("User denied Tier 3 confirmation"),
              securityBlocked = true,
            )
          }
        }
        is SecurityVerdict.Allow -> Unit
      }

      val res = env.sendNotificationReply(sbnKey, replyText)
      return res.fold(
        onSuccess = { msg ->
          if (trusted) {
            security.recordTrustedAutoReply(pkgGuess, contactName)
          }
          traceLog += "Replied to $contactName via RemoteInput"
          PlannerToolOutcome(
            ok = true,
            summary = msg,
            payload = buildJsonObject { put("status", "ok"); put("detail", msg) },
          )
        },
        onFailure = { err ->
          val m = err.message ?: "Reply failed"
          PlannerToolOutcome(ok = false, summary = m, payload = errorJson(m))
        },
      )
    }

    private suspend fun handleAskUserConfirmation(args: JsonObject): PlannerToolOutcome {
      val summary = args["action_summary"]?.jsonPrimitive?.content.orEmpty()
      val riskReason = args["risk_reason"]?.jsonPrimitive?.content.orEmpty()
      val fg = env.currentForegroundPackage()
      val approved = env.requestTier3BiometricApproval(
        actionTitle = "TIER 3 BIOMETRIC CHECK",
        actionSummary = summary,
        reason = riskReason,
        targetPackage = fg,
      )
      return if (approved) {
        PlannerToolOutcome(
          ok = true,
          summary = "User approved Tier 3 action: $summary",
          payload = buildJsonObject { put("approved", true) },
        )
      } else {
        PlannerToolOutcome(
          ok = false,
          summary = "User denied Tier 3 action: $summary",
          payload = buildJsonObject { put("approved", false) },
          securityBlocked = true,
        )
      }
    }

    private fun errorJson(msg: String): JsonObject = buildJsonObject {
      put("status", "error")
      put("error", msg)
    }
  }

  internal data class ParsedIntent(
    val intent: String,
    val params: Map<String, String>,
  )

  internal data class DirectMediaCommand(
    val targetPackage: String,
    val query: String,
    val playImmediately: Boolean,
  )

  companion object {
    const val DEFAULT_STEP_DELAY_MS = 450L
    private val SEARCH_FOR_REGEX = Regex("""^(search\s+[a-z0-9._-]+)\s+for\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val SEARCH_COLON_REGEX = Regex("""^([a-z0-9._\-\s]+?)\s*:\s*(.+)$""")
    private val YOUTUBE_PLAY_REGEX = Regex(
      """^(?:open\s+youtube\s+(?:and\s+)?(?:play|search(?:\s+for)?)|play\s+(.+?)\s+on\s+youtube|youtube\s+play)\s*(.*)$""",
      RegexOption.IGNORE_CASE,
    )
    private val SPOTIFY_PLAY_REGEX = Regex(
      """^(?:open\s+spotify\s+(?:and\s+)?(?:play|search(?:\s+for)?)|play\s+(.+?)\s+on\s+spotify|spotify\s+play)\s*(.*)$""",
      RegexOption.IGNORE_CASE,
    )
    private val MAPS_NAV_REGEX = Regex(
      """^(?:open\s+(?:google\s+)?maps\s+(?:and\s+)?(?:navigate\s+to|search(?:\s+for)?|to)|navigate\s+to)\s+(.+)$""",
      RegexOption.IGNORE_CASE,
    )

    internal fun parseDirectMediaCommand(rawCommand: String): DirectMediaCommand? {
      val trimmed = rawCommand.trim()
      if (trimmed.isEmpty()) return null
      YOUTUBE_PLAY_REGEX.matchEntire(trimmed)?.let { m ->
        val q = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        if (q.isNotEmpty()) {
          val play = trimmed.contains("play", ignoreCase = true)
          return DirectMediaCommand("com.google.android.youtube", q, playImmediately = play)
        }
      }
      SPOTIFY_PLAY_REGEX.matchEntire(trimmed)?.let { m ->
        val q = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        if (q.isNotEmpty()) {
          return DirectMediaCommand("com.spotify.music", q, playImmediately = true)
        }
      }
      MAPS_NAV_REGEX.matchEntire(trimmed)?.let { m ->
        val q = m.groupValues[1].trim()
        if (q.isNotEmpty()) {
          return DirectMediaCommand("com.google.android.apps.maps", q, playImmediately = false)
        }
      }
      return null
    }

    internal fun parseCommandIntent(
      rawCommand: String,
      explicitParams: Map<String, String> = emptyMap(),
    ): ParsedIntent {
      val trimmed = rawCommand.trim()
      if (trimmed.isEmpty()) return ParsedIntent("", explicitParams)

      val searchMatch = SEARCH_FOR_REGEX.matchEntire(trimmed)
      if (searchMatch != null) {
        val baseIntent = searchMatch.groupValues[1].trim().lowercase()
        val arg = searchMatch.groupValues[2].trim()
        val merged = LinkedHashMap<String, String>(explicitParams).apply {
          putIfAbsent("query", arg)
          putIfAbsent("text", arg)
          putIfAbsent("arg", arg)
        }
        return ParsedIntent(baseIntent, merged)
      }

      val colonMatch = SEARCH_COLON_REGEX.matchEntire(trimmed)
      if (colonMatch != null) {
        val baseIntent = colonMatch.groupValues[1].trim().lowercase()
        val arg = colonMatch.groupValues[2].trim()
        val merged = LinkedHashMap<String, String>(explicitParams).apply {
          putIfAbsent("query", arg)
          putIfAbsent("text", arg)
          putIfAbsent("arg", arg)
        }
        return ParsedIntent(baseIntent, merged)
      }

      val normalized = trimmed.lowercase().replace(Regex("""\s+"""), " ")
      return ParsedIntent(normalized, explicitParams)
    }

    internal fun resolveTemplate(template: String, params: Map<String, String>): String {
      var out = template
      for ((k, v) in params) {
        out = out.replace("{{$k}}", v)
      }
      return out
    }

    internal fun parameterizeSteps(
      steps: List<PlaybookStep>,
      params: Map<String, String>,
    ): List<PlaybookStep> {
      val q = params["query"]?.takeIf { it.isNotBlank() }
      return steps.map { step ->
        if (step.action == "input_text" && q != null && step.inputTemplate?.contains(q, ignoreCase = true) == true) {
          step.copy(inputTemplate = step.inputTemplate.replace(q, "{{query}}", ignoreCase = true))
        } else {
          step
        }
      }
    }

    internal fun resolveTargetNode(tree: CompactUiTree, step: PlaybookStep): CompactUiNode? {
      val desiredId = step.viewId?.trim()?.takeIf { it.isNotEmpty() }
      val desiredText = step.targetText?.trim()?.takeIf { it.isNotEmpty() }
      val desiredDesc = step.targetDesc?.trim()?.takeIf { it.isNotEmpty() }

      if (desiredId != null) {
        tree.nodes.firstOrNull { it.viewId.equals(desiredId, ignoreCase = true) }?.let { return it }
      }
      if (desiredText != null) {
        tree.nodes.firstOrNull { it.text.equals(desiredText, ignoreCase = true) }?.let { return it }
      }
      if (desiredDesc != null) {
        tree.nodes.firstOrNull { it.contentDesc.equals(desiredDesc, ignoreCase = true) }?.let { return it }
      }
      if (desiredText != null) {
        tree.nodes.firstOrNull { it.text.contains(desiredText, ignoreCase = true) }?.let { return it }
      }
      if (desiredDesc != null) {
        tree.nodes.firstOrNull { it.contentDesc.contains(desiredDesc, ignoreCase = true) }?.let { return it }
      }
      if (step.action == "input_text") {
        tree.nodes.firstOrNull { it.editable }?.let { return it }
      }
      if (step.action == "scroll_node") {
        tree.nodes.firstOrNull { it.scrollable }?.let { return it }
      }
      return null
    }

    internal fun buildSkillId(intent: String, targetPackage: String): String {
      val cleanIntent = intent.lowercase().replace(Regex("""[^a-z0-9]+"""), "_").trim('_').take(32)
      val cleanPkg = targetPackage.lowercase().substringAfterLast('.').replace(Regex("""[^a-z0-9]+"""), "_")
      return listOf(cleanPkg, cleanIntent).filter { it.isNotBlank() }.joinToString("_").ifEmpty { "skill_ui" }
    }
  }
}
