package com.nexwork.edith.jarvis.ui

import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexwork.edith.jarvis.core.JarvisEventRecord
import com.nexwork.edith.jarvis.core.JarvisRuntime
import com.nexwork.edith.jarvis.core.JarvisUiState
import com.nexwork.edith.jarvis.core.PlaceTag
import com.nexwork.edith.jarvis.core.SavedPlaceProfile
import com.nexwork.edith.jarvis.core.Tier3ApprovalRequest
import com.nexwork.edith.jarvis.core.TriageStatus
import com.nexwork.edith.jarvis.core.TrustedContact
import com.nexwork.edith.jarvis.core.UpcomingDestination
import com.nexwork.edith.jarvis.security.BiometricPromptHelper
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.GlassSurface
import com.nexwork.edith.theme.GlassSurfaceElevated
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.ObsidianBgTop
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import com.nexwork.edith.theme.WarmSunAccent
import java.util.Date

/**
 * Full-screen dark-glass Compose drawer overlay for launching Jarvis directly via `!jarvis`.
 */
@Composable
fun JarvisDrawerSheet(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.72f))
      .clickable(onClick = onDismiss),
  ) {
    Box(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .fillMaxSize(0.92f)
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .clip(RoundedCornerShape(18.dp))
        .background(ObsidianBgTop.copy(alpha = 0.94f))
        .border(1.dp, LocalLauncherStyle.current.accent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
        .clickable(enabled = false) {}
        .padding(14.dp),
    ) {
      JarvisBaseplatePanel(
        onClose = onDismiss,
        closeLabel = "CLOSE",
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

/**
 * Reusable Milestone 1 Jarvis Baseplate dashboard containing live status pills, the encrypted
 * KeyVault API key editor, and the scrolling triaged notification feed with RemoteInput testing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JarvisBaseplatePanel(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  closeLabel: String = "< BACK",
) {
  val context = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val accent = LocalLauncherStyle.current.accent

  LaunchedEffect(Unit) {
    JarvisRuntime.ensureInitialized(context)
    JarvisRuntime.refreshEnvironmentStatus(context)
  }

  val uiState by JarvisRuntime.uiState.collectAsStateWithLifecycle()

  var apiKeyInput by remember { mutableStateOf("") }
  var revealApiKey by remember { mutableStateOf(false) }
  var commandDraft by remember { mutableStateOf("") }
  var contactDraft by remember { mutableStateOf("") }
  var ssidDraft by remember { mutableStateOf("") }

  val bgLocLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    JarvisRuntime.refreshSensorsAfterPermissions(context)
    if (!granted) {
      JarvisRuntime.openAppDetailsSettings(context)
    }
  }
  val fgPermLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
  ) {
    JarvisRuntime.refreshSensorsAfterPermissions(context)
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
      JarvisRuntime.hasFineOrCoarseLocation(context) &&
      !JarvisRuntime.hasBackgroundLocation(context)
    ) {
      bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
  }
  val requestAmbientPermissions: () -> Unit = {
    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    if (!uiState.hasLocationPermission || !uiState.hasActivityRecognitionPermission || !uiState.hasBluetoothConnectPermission) {
      fgPermLauncher.launch(JarvisRuntime.foregroundContextPermissions())
    } else if (!uiState.hasBackgroundLocationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
      JarvisRuntime.refreshSensorsAfterPermissions(context)
    }
  }

  Column(
    modifier = modifier.imePadding(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Header row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "SYS.02 // JARVIS BASEPLATE",
          color = accent,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 1.2.sp,
        )
        Text(
          text = "${uiState.playbooks.size} PLAYBOOKS · ${uiState.activeReplyHandleCount} REPLY HANDLES",
          color = TextPureWhite,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .background(GlassSurfaceElevated.copy(alpha = 0.85f))
          .border(0.8.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
          .clickable {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            keyboardController?.hide()
            focusManager.clearFocus()
            onClose()
          }
          .padding(horizontal = 10.dp, vertical = 6.dp),
      ) {
        Text(
          text = closeLabel,
          color = accent,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f),
      verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      // Live Status Pills: FGS, LISTENER, ACCESSIBILITY HANDS, BATTERY
      item(key = "status_pills") {
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          StatusPill(
            label = "FGS",
            value = if (uiState.serviceStatus.isRunning) {
              "ACTIVE (${uiState.serviceStatus.processedCount})"
            } else {
              "STOPPED"
            },
            state = if (uiState.serviceStatus.isRunning) PillState.OK else PillState.FAULT,
            accent = accent,
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.startServiceSafely(context)
            },
          )

          val listenerState = when {
            uiState.notificationListenerEnabled && uiState.notificationListenerConnected -> PillState.OK
            uiState.notificationListenerEnabled -> PillState.WARN
            else -> PillState.FAULT
          }
          val listenerValue = when {
            uiState.notificationListenerEnabled && uiState.notificationListenerConnected -> "LIVE"
            uiState.notificationListenerEnabled -> "BINDING"
            else -> "GRANT"
          }
          StatusPill(
            label = "LISTENER",
            value = listenerValue,
            state = listenerState,
            accent = accent,
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.openNotificationListenerSettings(context)
            },
          )

          val a11yState = when {
            uiState.accessibilityEnabled && uiState.accessibilityConnected -> PillState.OK
            uiState.accessibilityEnabled -> PillState.WARN
            else -> PillState.FAULT
          }
          val a11yValue = when {
            uiState.accessibilityEnabled && uiState.accessibilityConnected -> "LIVE"
            uiState.accessibilityEnabled -> "BINDING"
            else -> "GRANT"
          }
          StatusPill(
            label = "A11Y HANDS",
            value = a11yValue,
            state = a11yState,
            accent = accent,
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.openAccessibilitySettings(context)
            },
          )

          StatusPill(
            label = "BATTERY",
            value = if (uiState.batteryUnrestricted) "UNRESTRICTED" else "OPTIMIZED",
            state = if (uiState.batteryUnrestricted) PillState.OK else PillState.WARN,
            accent = accent,
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.requestBatteryUnrestricted(context)
            },
          )

          val ctxAllOk = uiState.hasLocationPermission &&
            uiState.hasBackgroundLocationPermission &&
            uiState.hasActivityRecognitionPermission &&
            uiState.hasBluetoothConnectPermission
          val ctxAnyOk = uiState.hasLocationPermission || uiState.hasActivityRecognitionPermission
          StatusPill(
            label = "CTX SENSORS",
            value = when {
              ctxAllOk -> "LIVE"
              ctxAnyOk -> "PARTIAL"
              else -> "GRANT"
            },
            state = when {
              ctxAllOk -> PillState.OK
              ctxAnyOk -> PillState.WARN
              else -> PillState.FAULT
            },
            accent = accent,
            onClick = requestAmbientPermissions,
          )
        }
      }

      // Milestone 3 Saved Places & Context Debug Card
      item(key = "saved_places_context_debug") {
        SavedPlacesContextDebugCard(
          uiState = uiState,
          draftSsid = ssidDraft,
          accent = accent,
          onDraftSsidChange = { ssidDraft = it },
          onTagPlace = { ssid, tag ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.savePlaceProfile(context, ssid, tag, autoFocusQuiet = true)
            if (ssid == ssidDraft) ssidDraft = ""
            keyboardController?.hide()
            focusManager.clearFocus()
          },
          onDeletePlace = { ssidNorm ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.deletePlaceProfile(context, ssidNorm)
          },
          onRequestPermissions = requestAmbientPermissions,
          onSimStallMaps = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.simulateTrafficStallTrigger(context)
          },
          onSimDiningQr = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.simulatePostDiningQrTrigger(context)
          },
        )
      }

      // Interactive Tier-3 Biometric Approval Card (BiometricPrompt)
      item(key = "tier3_biometric_card") {
        Tier3BiometricApprovalCard(
          pending = uiState.pendingTier3Approval,
          accent = accent,
          onApprove = { req ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            BiometricPromptHelper.authenticate(
              context = context,
              title = req.actionTitle,
              subtitle = req.actionSummary,
              description = req.reason,
            ) { approved, detail ->
              if (!approved && !detail.isNullOrBlank()) {
                JarvisRuntime.resolveTier3Approval(req.requestId, false)
              } else {
                JarvisRuntime.resolveTier3Approval(req.requestId, approved)
              }
            }
          },
          onDeny = { req ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.resolveTier3Approval(req.requestId, false)
          },
          onTriggerTest = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.triggerTier3BiometricTest(context)
          },
        )
      }

      // Command Test Bar for end-to-end Playbook Replay & gemini-4-hydrogen Planner Execution
      item(key = "command_test_bar") {
        CommandTestBarCard(
          draftCommand = commandDraft,
          isExecuting = uiState.isExecutingCommand,
          playbooksCount = uiState.playbooks.size,
          traceLog = uiState.lastExecutionLog,
          accent = accent,
          onDraftChange = { commandDraft = it },
          onRun = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val cmd = commandDraft
            commandDraft = ""
            keyboardController?.hide()
            focusManager.clearFocus()
            JarvisRuntime.executeUiCommand(context, cmd)
          },
        )
      }

      // Trusted Contacts Toggle List
      item(key = "trusted_contacts") {
        TrustedContactsCard(
          contacts = uiState.trustedContacts,
          draftContact = contactDraft,
          accent = accent,
          onDraftChange = { contactDraft = it },
          onAdd = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.addOrUpdateTrustedContact(context, contactDraft)
            contactDraft = ""
            keyboardController?.hide()
            focusManager.clearFocus()
          },
          onToggle = { contact, enabled ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.toggleTrustedContact(context, contact.contactKey, enabled)
          },
          onDelete = { contact ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.deleteTrustedContact(context, contact.contactKey)
          },
        )
      }

      // Encrypted KeyVault API Key Input
      item(key = "api_key_vault") {
        ApiKeyVaultCard(
          uiState = uiState,
          draftKey = apiKeyInput,
          revealKey = revealApiKey,
          accent = accent,
          onDraftChange = { apiKeyInput = it },
          onToggleReveal = { revealApiKey = !revealApiKey },
          onSave = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.saveApiKey(context, apiKeyInput)
            apiKeyInput = ""
            keyboardController?.hide()
            focusManager.clearFocus()
          },
          onClear = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.clearApiKey(context)
            apiKeyInput = ""
          },
        )
      }

      // Optional live processing / feedback banner
      val bannerText = when {
        uiState.isExecutingCommand -> "EXECUTING UI PLAN..."
        uiState.isProcessing -> "TRIAGING VIA GEMINI 3.5 FLASH-LITE..."
        !uiState.lastError.isNullOrBlank() -> uiState.lastError
        !uiState.lastStatusBanner.isNullOrBlank() -> uiState.lastStatusBanner
        else -> null
      }
      if (bannerText != null) {
        item(key = "status_banner") {
          val busy = uiState.isProcessing || uiState.isExecutingCommand
          val isErr = !busy && !uiState.lastError.isNullOrBlank()
          val bannerColor = if (isErr) CrimsonBright else accent
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(bannerColor.copy(alpha = 0.12f))
              .border(0.8.dp, bannerColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
              .clickable { JarvisRuntime.clearBanner() }
              .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = bannerText,
              color = if (isErr) CrimsonBright else TextPureWhite,
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            if (!busy) {
              Text(
                text = "DISMISS",
                color = TextSilver,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 8.dp),
              )
            }
          }
        }
      }

      item(key = "event_feed_header") {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "TRIAGED EVENT FEED (${uiState.events.size})",
            color = TextSilver,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
          )
          Text(
            text = "SQLITE WAL",
            color = accent.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
          )
        }
      }

      if (uiState.events.isEmpty()) {
        item(key = "empty_events") {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(GlassSurface.copy(alpha = 0.55f))
              .border(0.8.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
              .padding(14.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "No notifications logged yet.\nEnable LISTENER & A11Y HANDS above, save your Gemini API key, and run commands or receive messages.",
              color = TextSilver,
              fontSize = 11.sp,
              lineHeight = 16.sp,
            )
          }
        }
      } else {
        items(items = uiState.events, key = { it.id }) { event ->
          JarvisEventCard(
            event = event,
            accent = accent,
            onSendTestReply = { replyText ->
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              keyboardController?.hide()
              focusManager.clearFocus()
              JarvisRuntime.sendTestReply(context, event, replyText)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun Tier3BiometricApprovalCard(
  pending: Tier3ApprovalRequest?,
  accent: Color,
  onApprove: (Tier3ApprovalRequest) -> Unit,
  onDeny: (Tier3ApprovalRequest) -> Unit,
  onTriggerTest: () -> Unit,
) {
  val borderTint = if (pending != null) WarmSunAccent else accent.copy(alpha = 0.40f)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(if (pending != null) WarmSunAccent.copy(alpha = 0.10f) else GlassSurface.copy(alpha = 0.68f))
      .border(0.9.dp, borderTint, RoundedCornerShape(10.dp))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = pending?.actionTitle ?: "TIER 3 // BIOMETRIC GATE",
        color = if (pending != null) WarmSunAccent else TextSilver,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
      )
      if (pending == null) {
        ActionChip(
          text = "TEST BIOMETRIC",
          tint = accent,
          enabled = true,
          onClick = onTriggerTest,
        )
      }
    }

    if (pending != null) {
      Text(
        text = pending.actionSummary,
        color = TextPureWhite,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = pending.reason,
        color = TextSilver,
        fontSize = 10.sp,
        lineHeight = 14.sp,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ActionChip(
          text = "APPROVE (BIOMETRIC)",
          tint = accent,
          enabled = true,
          onClick = { onApprove(pending) },
        )
        ActionChip(
          text = "DENY",
          tint = CrimsonBright,
          enabled = true,
          onClick = { onDeny(pending) },
        )
      }
    } else {
      Text(
        text = "High-risk clicks and untrusted replies suspend here for hardware BiometricPrompt confirmation.",
        color = TextSilver.copy(alpha = 0.80f),
        fontSize = 10.sp,
        lineHeight = 13.sp,
      )
    }
  }
}

@Composable
private fun CommandTestBarCard(
  draftCommand: String,
  isExecuting: Boolean,
  playbooksCount: Int,
  traceLog: List<String>,
  accent: Color,
  onDraftChange: (String) -> Unit,
  onRun: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(GlassSurface.copy(alpha = 0.72f))
      .border(0.8.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "COMMAND TEST BAR (PLAYBOOKS -> HYDROGEN)",
        color = TextSilver,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = "$playbooksCount SAVED",
        color = accent,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(7.dp))
          .background(ObsidianBgTop.copy(alpha = 0.85f))
          .border(0.8.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
          .padding(horizontal = 9.dp, vertical = 7.dp),
      ) {
        if (draftCommand.isEmpty()) {
          Text(
            text = "e.g. search youtube for jazz",
            color = TextSilver.copy(alpha = 0.50f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        BasicTextField(
          value = draftCommand,
          onValueChange = onDraftChange,
          singleLine = true,
          textStyle = TextStyle(
            color = TextPureWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
          ),
          cursorBrush = SolidColor(accent),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
          keyboardActions = KeyboardActions(onGo = { if (draftCommand.isNotBlank() && !isExecuting) onRun() }),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      ActionChip(
        text = if (isExecuting) "RUN..." else "RUN",
        tint = accent,
        enabled = !isExecuting && draftCommand.isNotBlank(),
        onClick = onRun,
      )
    }
    if (traceLog.isNotEmpty()) {
      Text(
        text = traceLog.takeLast(4).joinToString("\n"),
        color = TextSilver.copy(alpha = 0.88f),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        maxLines = 5,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun TrustedContactsCard(
  contacts: List<TrustedContact>,
  draftContact: String,
  accent: Color,
  onDraftChange: (String) -> Unit,
  onAdd: () -> Unit,
  onToggle: (TrustedContact, Boolean) -> Unit,
  onDelete: (TrustedContact) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(GlassSurface.copy(alpha = 0.72f))
      .border(0.8.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = "TRUSTED CONTACTS (${contacts.size})",
      color = TextSilver,
      fontFamily = FontFamily.Monospace,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.SemiBold,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(7.dp))
          .background(ObsidianBgTop.copy(alpha = 0.85f))
          .border(0.8.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(7.dp))
          .padding(horizontal = 9.dp, vertical = 7.dp),
      ) {
        if (draftContact.isEmpty()) {
          Text(
            text = "Contact name (Tier 2 auto-reply)",
            color = TextSilver.copy(alpha = 0.50f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        BasicTextField(
          value = draftContact,
          onValueChange = onDraftChange,
          singleLine = true,
          textStyle = TextStyle(
            color = TextPureWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
          ),
          cursorBrush = SolidColor(accent),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { if (draftContact.isNotBlank()) onAdd() }),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      ActionChip(
        text = "+ TRUST",
        tint = accent,
        enabled = draftContact.isNotBlank(),
        onClick = onAdd,
      )
    }

    if (contacts.isNotEmpty()) {
      contacts.forEach { contact ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = contact.displayName + if (contact.packageName.isNotBlank()) " (${contact.packageName.substringAfterLast('.')})" else "",
            color = TextPureWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ActionChip(
              text = if (contact.autoReplyEnabled) "TRUSTED" else "OFF",
              tint = if (contact.autoReplyEnabled) accent else WarmSunAccent,
              enabled = true,
              onClick = { onToggle(contact, !contact.autoReplyEnabled) },
            )
            ActionChip(
              text = "DEL",
              tint = CrimsonBright,
              enabled = true,
              onClick = { onDelete(contact) },
            )
          }
        }
      }
    }
  }
}

private enum class PillState { OK, WARN, FAULT }

@Composable
private fun StatusPill(
  label: String,
  value: String,
  state: PillState,
  accent: Color,
  onClick: () -> Unit,
) {
  val tint = when (state) {
    PillState.OK -> accent
    PillState.WARN -> WarmSunAccent
    PillState.FAULT -> CrimsonBright
  }
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(999.dp))
      .background(GlassSurfaceElevated.copy(alpha = 0.78f))
      .border(0.9.dp, tint.copy(alpha = 0.68f), RoundedCornerShape(999.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 9.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Box(
      modifier = Modifier
        .size(6.dp)
        .clip(CircleShape)
        .background(tint),
    )
    Text(
      text = "$label: $value",
      color = TextPureWhite,
      fontFamily = FontFamily.Monospace,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
  }
}

@Composable
private fun ApiKeyVaultCard(
  uiState: JarvisUiState,
  draftKey: String,
  revealKey: Boolean,
  accent: Color,
  onDraftChange: (String) -> Unit,
  onToggleReveal: () -> Unit,
  onSave: () -> Unit,
  onClear: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(GlassSurface.copy(alpha = 0.72f))
      .border(0.8.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "GEMINI API KEY (ENCRYPTED VAULT)",
        color = TextSilver,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = if (uiState.hasApiKey) "SEALED ${uiState.maskedApiKey}" else "UNSET",
        color = if (uiState.hasApiKey) accent else WarmSunAccent,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(7.dp))
          .background(ObsidianBgTop.copy(alpha = 0.85f))
          .border(0.8.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
          .padding(horizontal = 9.dp, vertical = 7.dp),
      ) {
        if (draftKey.isEmpty()) {
          Text(
            text = if (uiState.hasApiKey) "Replace AIza... key" else "Paste Google AI Studio key",
            color = TextSilver.copy(alpha = 0.52f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        BasicTextField(
          value = draftKey,
          onValueChange = onDraftChange,
          singleLine = true,
          textStyle = TextStyle(
            color = TextPureWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
          ),
          cursorBrush = SolidColor(accent),
          visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
          ),
          keyboardActions = KeyboardActions(
            onDone = { if (draftKey.isNotBlank()) onSave() },
          ),
          modifier = Modifier.fillMaxWidth(),
        )
      }

      ActionChip(
        text = if (revealKey) "HIDE" else "SHOW",
        tint = TextSilver,
        enabled = true,
        onClick = onToggleReveal,
      )
      ActionChip(
        text = "SAVE",
        tint = accent,
        enabled = draftKey.isNotBlank(),
        onClick = onSave,
      )
      if (uiState.hasApiKey) {
        ActionChip(
          text = "CLR",
          tint = CrimsonBright,
          enabled = true,
          onClick = onClear,
        )
      }
    }
  }
}

@Composable
private fun JarvisEventCard(
  event: JarvisEventRecord,
  accent: Color,
  onSendTestReply: (String) -> Unit,
) {
  val suggested = event.triage?.suggestedReply?.takeIf { it.isNotBlank() } ?: "Test reply from Jarvis"
  var replyDraft by remember(event.id, suggested) { mutableStateOf(suggested) }

  val urgencyLabel = when (event.triageStatus) {
    TriageStatus.PENDING -> "PENDING"
    TriageStatus.SKIPPED_NO_KEY -> "NO KEY"
    TriageStatus.FAILED -> "FAULT"
    TriageStatus.TRIAGED -> event.triage?.urgency?.uppercase() ?: "LOW"
  }
  val urgencyColor = when (urgencyLabel) {
    "CRITICAL", "FAULT" -> CrimsonBright
    "HIGH", "NO KEY" -> WarmSunAccent
    "MEDIUM" -> accent
    else -> TextSilver
  }
  val timeFormatted = remember(event.createdAtMs) {
    DateFormat.format("HH:mm:ss", Date(event.createdAtMs)).toString()
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(GlassSurface.copy(alpha = 0.78f))
      .border(0.8.dp, urgencyColor.copy(alpha = 0.42f), RoundedCornerShape(10.dp))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(urgencyColor.copy(alpha = 0.16f))
            .border(0.7.dp, urgencyColor.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
          Text(
            text = urgencyLabel,
            color = urgencyColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
          )
        }
        Text(
          text = "${event.appLabel} · ${event.triage?.sender?.ifBlank { event.sender } ?: event.sender}",
          color = TextPureWhite,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = timeFormatted,
        color = TextSilver.copy(alpha = 0.75f),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
      )
    }

    if (event.body.isNotBlank()) {
      Text(
        text = event.body,
        color = TextSilver,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }

    event.triage?.let { triage ->
      Text(
        text = "INTENT: ${triage.intent.uppercase()}  ·  SUGGESTED: ${triage.suggestedReply.ifBlank { "(none)" }}",
        color = accent.copy(alpha = 0.92f),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (!event.errorMessage.isNullOrBlank()) {
      Text(
        text = event.errorMessage,
        color = CrimsonBright,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (!event.replySent.isNullOrBlank()) {
      Text(
        text = "SENT VIA REMOTEINPUT: \"${event.replySent}\"",
        color = accent,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(6.dp))
          .background(ObsidianBgTop.copy(alpha = 0.78f))
          .border(
            0.7.dp,
            if (event.canReplyNow) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.1f),
            RoundedCornerShape(6.dp),
          )
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        if (event.canReplyNow) {
          BasicTextField(
            value = replyDraft,
            onValueChange = { replyDraft = it },
            singleLine = true,
            textStyle = TextStyle(
              color = TextPureWhite,
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
            ),
            cursorBrush = SolidColor(accent),
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          Text(
            text = if (event.hasRemoteInput) "Inline reply handle expired" else "No RemoteInput action",
            color = TextSilver.copy(alpha = 0.5f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      ActionChip(
        text = "Send Test Reply",
        tint = if (event.canReplyNow) accent else TextSilver.copy(alpha = 0.45f),
        enabled = event.canReplyNow,
        onClick = { onSendTestReply(replyDraft) },
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedPlacesContextDebugCard(
  uiState: JarvisUiState,
  draftSsid: String,
  accent: Color,
  onDraftSsidChange: (String) -> Unit,
  onTagPlace: (String, PlaceTag) -> Unit,
  onDeletePlace: (String) -> Unit,
  onRequestPermissions: () -> Unit,
  onSimStallMaps: () -> Unit,
  onSimDiningQr: () -> Unit,
) {
  val ambient = uiState.ambientContext
  val currentSsid = ambient.wifiSsid?.trim().orEmpty()
  val awayMins = ambient.awayDwellSinceMs?.let { ((System.currentTimeMillis() - it) / 60_000L).coerceAtLeast(0L) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(GlassSurface.copy(alpha = 0.72f))
      .border(0.8.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "SAVED PLACES & CONTEXT DEBUG",
        color = TextSilver,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = if (ambient.focusQuietActive) "${ambient.placeTag.name} · QUIET" else "${ambient.motionState.name}",
        color = if (ambient.focusQuietActive) WarmSunAccent else accent,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
      )
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(7.dp))
        .background(ObsidianBgTop.copy(alpha = 0.75f))
        .padding(8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = "MOTION: ${ambient.motionState.name} (prev: ${ambient.previousMotionState.name}) · BT: ${ambient.bluetoothConnectedCount}",
        color = TextPureWhite,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "WIFI: ${currentSsid.ifEmpty { "<disconnected>" }} · ZONE: ${ambient.placeTag.name} · QUIET: ${if (ambient.focusQuietActive) "ON" else "OFF"}",
        color = TextSilver,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "PERMS: LOC=${uiState.hasLocationPermission} BG=${uiState.hasBackgroundLocationPermission} ACT=${uiState.hasActivityRecognitionPermission} BT=${uiState.hasBluetoothConnectPermission}",
        color = TextSilver.copy(alpha = 0.85f),
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "AWAY DWELL: ${awayMins?.let { "${it}m" } ?: "none"} · 24H DESTINATIONS: ${uiState.upcomingDestinations.size}",
        color = accent.copy(alpha = 0.9f),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      uiState.upcomingDestinations.firstOrNull()?.let { nextDest ->
        Text(
          text = "NEXT DEST: ${nextDest.placeName} (${nextDest.sourceSummary})",
          color = TextPureWhite.copy(alpha = 0.9f),
          fontFamily = FontFamily.Monospace,
          fontSize = 8.8.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    if (currentSsid.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        ActionChip(
          text = "TAG '${currentSsid.take(12)}' HOME",
          tint = accent,
          enabled = true,
          onClick = { onTagPlace(currentSsid, PlaceTag.HOME) },
        )
        ActionChip(
          text = "TAG '${currentSsid.take(12)}' CAMPUS",
          tint = WarmSunAccent,
          enabled = true,
          onClick = { onTagPlace(currentSsid, PlaceTag.CAMPUS) },
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(7.dp))
          .background(ObsidianBgTop.copy(alpha = 0.85f))
          .border(0.8.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
          .padding(horizontal = 9.dp, vertical = 7.dp),
      ) {
        if (draftSsid.isEmpty()) {
          Text(
            text = "Wi-Fi SSID (e.g. Home_5G)",
            color = TextSilver.copy(alpha = 0.50f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        BasicTextField(
          value = draftSsid,
          onValueChange = onDraftSsidChange,
          singleLine = true,
          textStyle = TextStyle(
            color = TextPureWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
          ),
          cursorBrush = SolidColor(accent),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = {
            if (draftSsid.isNotBlank()) onTagPlace(draftSsid, PlaceTag.HOME)
          }),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      ActionChip(
        text = "+HOME",
        tint = accent,
        enabled = draftSsid.isNotBlank(),
        onClick = { onTagPlace(draftSsid, PlaceTag.HOME) },
      )
      ActionChip(
        text = "+CAMPUS",
        tint = WarmSunAccent,
        enabled = draftSsid.isNotBlank(),
        onClick = { onTagPlace(draftSsid, PlaceTag.CAMPUS) },
      )
    }

    if (uiState.savedPlaces.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        uiState.savedPlaces.forEach { place ->
          ActionChip(
            text = "${place.placeTag.name}:${place.displaySsid} ×",
            tint = if (place.placeTag == PlaceTag.HOME) accent else WarmSunAccent,
            enabled = true,
            onClick = { onDeletePlace(place.ssidNormalized) },
          )
        }
      }
    }

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      ActionChip(
        text = "GRANT SENSORS",
        tint = accent,
        enabled = true,
        onClick = onRequestPermissions,
      )
      ActionChip(
        text = "SIM STALL -> MAPS",
        tint = accent,
        enabled = true,
        onClick = onSimStallMaps,
      )
      ActionChip(
        text = "SIM DINING -> QR",
        tint = WarmSunAccent,
        enabled = true,
        onClick = onSimDiningQr,
      )
    }
  }
}

@Composable
private fun ActionChip(
  text: String,
  tint: Color,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(
        if (enabled) GlassSurfaceElevated.copy(alpha = 0.9f) else GlassSurface.copy(alpha = 0.45f)
      )
      .border(
        0.8.dp,
        if (enabled) tint.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.1f),
        RoundedCornerShape(6.dp),
      )
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    Text(
      text = text,
      color = tint,
      fontFamily = FontFamily.Monospace,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}
