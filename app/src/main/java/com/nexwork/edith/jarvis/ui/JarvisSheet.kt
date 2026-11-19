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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import com.nexwork.edith.jarvis.core.ClashSeverity
import com.nexwork.edith.jarvis.core.EventClash
import com.nexwork.edith.jarvis.core.JarvisEventRecord
import com.nexwork.edith.jarvis.core.JarvisRuntime
import com.nexwork.edith.jarvis.core.JarvisUiState
import com.nexwork.edith.jarvis.core.PlaceTag
import com.nexwork.edith.jarvis.core.SavedPlaceProfile
import com.nexwork.edith.jarvis.core.ScheduledEvent
import com.nexwork.edith.jarvis.core.Tier3ApprovalRequest
import com.nexwork.edith.jarvis.core.TriageStatus
import com.nexwork.edith.jarvis.core.TrustedContact
import com.nexwork.edith.jarvis.core.UpcomingDestination
import com.nexwork.edith.jarvis.security.BiometricPromptHelper
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.LocalLauncherStyle
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import com.nexwork.edith.ui.hud.starkHudPlate
import java.util.Date
import java.util.Locale

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
      .background(Color.Black.copy(alpha = 0.80f))
      .clickable(onClick = onDismiss),
  ) {
    val sheetStyle = LocalLauncherStyle.current
    Box(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .fillMaxSize(0.92f)
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .starkHudPlate(
          accent = sheetStyle.accent,
          focused = true,
          showBrackets = true,
          bracketScale = 0.85f,
          plateAlpha = 0.96f,
          style = sheetStyle,
        )
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
  val style = LocalLauncherStyle.current
  val accent = style.accent

  LaunchedEffect(Unit) {
    JarvisRuntime.ensureInitialized(context)
    JarvisRuntime.refreshEnvironmentStatus(context)
  }

  val uiState by JarvisRuntime.uiState.collectAsStateWithLifecycle()

  var apiKeyInput by remember { mutableStateOf("") }
  var revealApiKey by remember { mutableStateOf(false) }
  var contactDraft by remember { mutableStateOf("") }
  var ssidDraft by remember { mutableStateOf("") }
  var eventTitleDraft by remember { mutableStateOf("") }
  var eventTimeDraft by remember { mutableStateOf("") }
  var eventLocationDraft by remember { mutableStateOf("") }

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
  val calPermLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) {
    JarvisRuntime.syncCalendarAndEvents(context)
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
          fontFamily = style.telemetryFontFamily,
          fontSize = (10f * style.fontScale).sp,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 1.2.sp,
        )
        Text(
          text = "${uiState.scheduledEvents.size} EVENTS · ${uiState.eventClashes.size} CLASHES · ${uiState.activeReplyHandleCount} REPLIES",
          color = TextPureWhite,
          fontFamily = style.fontFamily,
          fontSize = (11.5f * style.fontScale).sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      val hasActiveJobs = uiState.isProcessing ||
        uiState.pendingTier3Approval != null
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .starkHudPlate(
              accent = Crimson,
              focused = hasActiveJobs,
              showBrackets = hasActiveJobs,
              bracketScale = 0.42f,
              plateAlpha = 0.92f,
              style = style,
            )
            .clickable {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              keyboardController?.hide()
              focusManager.clearFocus()
              JarvisRuntime.emergencyStop(context, "Jarvis E-STOP tapped")
            }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
          Text(
            text = if (hasActiveJobs) "■ STOP" else "■ E-STOP",
            color = if (hasActiveJobs) TextPureWhite else CrimsonBright,
            fontFamily = style.fontFamily,
            fontSize = (10f * style.fontScale).sp,
            fontWeight = FontWeight.Bold,
          )
        }

        Box(
          modifier = Modifier
            .starkHudPlate(
              accent = accent,
              focused = false,
              showBrackets = true,
              bracketScale = 0.42f,
              plateAlpha = 0.90f,
              style = style,
            )
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
            fontFamily = style.fontFamily,
            fontSize = (10f * style.fontScale).sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }

    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f),
      verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      // Live Status Pills: FGS, LISTENER, SHIZUKU HANDS, BATTERY
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

          val shizukuState = when {
            uiState.shizukuRunning && uiState.shizukuAuthorized -> PillState.OK
            uiState.shizukuRunning -> PillState.WARN
            else -> PillState.FAULT
          }
          val shizukuValue = when {
            uiState.shizukuRunning && uiState.shizukuAuthorized -> "UID 2000"
            uiState.shizukuRunning -> "GRANT"
            else -> "OFFLINE"
          }
          StatusPill(
            label = "SHIZUKU",
            value = shizukuValue,
            state = shizukuState,
            accent = accent,
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.requestOrOpenShizukuHands(context)
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

          StatusPill(
            label = "CALENDAR",
            value = if (uiState.hasCalendarPermission) "SYNC" else "GRANT",
            state = if (uiState.hasCalendarPermission) PillState.OK else PillState.WARN,
            accent = accent,
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              if (uiState.hasCalendarPermission) {
                JarvisRuntime.syncCalendarAndEvents(context)
              } else {
                calPermLauncher.launch(Manifest.permission.READ_CALENDAR)
              }
            },
          )

          if (uiState.serviceStatus.isRunning) {
            StatusPill(
              label = "FGS",
              value = "STOP",
              state = PillState.FAULT,
              accent = CrimsonBright,
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                JarvisRuntime.stopForegroundService(context)
              },
            )
          }
        }
      }

      // Event Planner: Active Clashes + Upcoming Schedule
      if (uiState.eventClashes.isNotEmpty()) {
        item(key = "event_clashes_card") {
          EventClashesCard(
            clashes = uiState.eventClashes,
            accent = accent,
            onKeepEvent = { clashId, keepId ->
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.resolveClashKeep(context, clashId, keepId)
            },
            onIgnoreClash = { clashId ->
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              JarvisRuntime.ignoreClash(context, clashId)
            },
          )
        }
      }

      item(key = "event_planner_card") {
        EventPlannerCard(
          events = uiState.scheduledEvents,
          draftTitle = eventTitleDraft,
          draftTime = eventTimeDraft,
          draftLocation = eventLocationDraft,
          accent = accent,
          onTitleChange = { eventTitleDraft = it },
          onTimeChange = { eventTimeDraft = it },
          onLocationChange = { eventLocationDraft = it },
          onAddEvent = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val startMs = parseDraftEventTimeMs(eventTimeDraft)
            JarvisRuntime.addManualEvent(
              context = context,
              title = eventTitleDraft,
              startEpochMs = startMs,
              endEpochMs = startMs + 3_600_000L,
              location = eventLocationDraft,
            )
            eventTitleDraft = ""
            eventTimeDraft = ""
            eventLocationDraft = ""
            keyboardController?.hide()
            focusManager.clearFocus()
          },
          onDismissEvent = { eventId ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            JarvisRuntime.dismissScheduledEvent(context, eventId)
          },
        )
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
        uiState.isProcessing -> "TRIAGING VIA GEMINI 3.5 FLASH-LITE..."
        !uiState.lastError.isNullOrBlank() -> uiState.lastError
        !uiState.lastStatusBanner.isNullOrBlank() -> uiState.lastStatusBanner
        else -> null
      }
      if (bannerText != null) {
        item(key = "status_banner") {
          val busy = uiState.isProcessing
          val isErr = !busy && !uiState.lastError.isNullOrBlank()
          val bannerColor = if (isErr) CrimsonBright else accent
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(style.voidTop.copy(alpha = 0.92f))
              .border(0.8.dp, (if (isErr) Crimson else style.graphicAccent).copy(alpha = 0.78f))
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
              .starkHudPlate(
                accent = accent.copy(alpha = 0.55f),
                focused = false,
                showBrackets = false,
                plateAlpha = 0.80f,
                style = style,
              )
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
  val style = LocalLauncherStyle.current
  val warnAccent = style.semanticWarn
  val cardAccent = if (pending != null) warnAccent else accent
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .starkHudPlate(
        accent = cardAccent,
        focused = pending != null,
        showBrackets = true,
        bracketScale = 0.56f,
        plateAlpha = 0.90f,
        style = style,
      )
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
        color = if (pending != null) warnAccent else TextSilver,
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
private fun TrustedContactsCard(
  contacts: List<TrustedContact>,
  draftContact: String,
  accent: Color,
  onDraftChange: (String) -> Unit,
  onAdd: () -> Unit,
  onToggle: (TrustedContact, Boolean) -> Unit,
  onDelete: (TrustedContact) -> Unit,
) {
  val style = LocalLauncherStyle.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .starkHudPlate(
        accent = accent.copy(alpha = 0.75f),
        focused = false,
        showBrackets = true,
        bracketScale = 0.52f,
        plateAlpha = 0.88f,
        style = style,
      )
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
          .jarvisInputPlate(accent)
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
              tint = if (contact.autoReplyEnabled) accent else style.semanticWarn,
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
  val style = LocalLauncherStyle.current
  val tint = when (state) {
    PillState.OK -> accent
    PillState.WARN -> style.semanticWarn
    PillState.FAULT -> CrimsonBright
  }
  val plateAccent = if (state == PillState.FAULT) Crimson else tint
  Row(
    modifier = Modifier
      .starkHudPlate(
        accent = plateAccent,
        focused = state != PillState.OK,
        showBrackets = true,
        bracketScale = 0.36f,
        plateAlpha = 0.86f,
        style = style,
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 9.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      modifier = Modifier
        .size(5.dp)
        .background(if (state == PillState.FAULT) Crimson else tint),
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
  val style = LocalLauncherStyle.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .starkHudPlate(
        accent = accent.copy(alpha = 0.75f),
        focused = false,
        showBrackets = true,
        bracketScale = 0.52f,
        plateAlpha = 0.88f,
        style = style,
      )
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
        color = if (uiState.hasApiKey) accent else style.semanticWarn,
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
          .jarvisInputPlate(accent)
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
  val style = LocalLauncherStyle.current
  val urgencyColor = when (urgencyLabel) {
    "CRITICAL", "FAULT" -> CrimsonBright
    "HIGH", "NO KEY" -> style.semanticWarn
    "MEDIUM" -> accent
    else -> TextSilver
  }
  val timeFormatted = remember(event.createdAtMs) {
    DateFormat.format("HH:mm:ss", Date(event.createdAtMs)).toString()
  }

  val borderUrgencyAccent = if (urgencyColor == CrimsonBright) Crimson else urgencyColor
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .starkHudPlate(
        accent = borderUrgencyAccent,
        focused = urgencyLabel == "CRITICAL" || urgencyLabel == "HIGH",
        showBrackets = true,
        bracketScale = 0.48f,
        plateAlpha = 0.88f,
        style = style,
      )
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
            .background(urgencyColor.copy(alpha = 0.14f))
            .border(0.7.dp, borderUrgencyAccent.copy(alpha = 0.72f))
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
          .jarvisInputPlate(if (event.canReplyNow) accent else Color.White.copy(alpha = 0.25f))
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
  val style = LocalLauncherStyle.current
  val warnAccent = style.semanticWarn
  val ambient = uiState.ambientContext
  val currentSsid = ambient.wifiSsid?.trim().orEmpty()
  val awayMins = ambient.awayDwellSinceMs?.let { ((System.currentTimeMillis() - it) / 60_000L).coerceAtLeast(0L) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .starkHudPlate(
        accent = if (ambient.focusQuietActive) warnAccent else accent,
        focused = ambient.focusQuietActive,
        showBrackets = true,
        bracketScale = 0.56f,
        plateAlpha = 0.88f,
        style = style,
      )
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
        color = if (ambient.focusQuietActive) warnAccent else accent,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
      )
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(style.voidTop.copy(alpha = 0.88f))
        .border(0.7.dp, accent.copy(alpha = 0.30f))
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
        text = "AWAY DWELL: ${awayMins?.let { "${it}m" } ?: "none"} · DESTS: ${uiState.upcomingDestinations.size} · PERSONAS: ${uiState.personaCount} · MUTED GRP: ${uiState.suppressedGroupCount}",
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
          tint = warnAccent,
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
          .jarvisInputPlate(accent)
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
        tint = warnAccent,
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
            tint = if (place.placeTag == PlaceTag.HOME) accent else warnAccent,
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
        tint = warnAccent,
        enabled = true,
        onClick = onSimDiningQr,
      )
    }
  }
}

@Composable
private fun EventClashesCard(
  clashes: List<EventClash>,
  accent: Color,
  onKeepEvent: (clashId: String, keepEventId: Long) -> Unit,
  onIgnoreClash: (clashId: String) -> Unit,
) {
  val style = LocalLauncherStyle.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .starkHudPlate(
        accent = CrimsonBright,
        focused = true,
        showBrackets = true,
        bracketScale = 0.56f,
        plateAlpha = 0.92f,
        style = style,
      )
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "SCHEDULE CLASHES (${clashes.size})",
        color = CrimsonBright,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = "ACTION NEEDED",
        color = style.semanticWarn,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
      )
    }

    clashes.take(4).forEach { clash ->
      val reason = if (clash.severity == ClashSeverity.HARD_OVERLAP) {
        "${clash.overlapMinutes}M OVERLAP"
      } else {
        "${clash.gapMinutes}M GAP · DIFF LOCATIONS"
      }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(style.voidTop.copy(alpha = 0.85f))
          .border(0.8.dp, Crimson.copy(alpha = 0.65f))
          .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = reason,
          color = style.semanticWarn,
          fontFamily = FontFamily.Monospace,
          fontSize = 8.5.sp,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = "A: ${clash.existingEvent.title} (${formatEventTimeRange(clash.existingEvent.startEpochMs, clash.existingEvent.endEpochMs)})",
          color = TextPureWhite,
          fontFamily = FontFamily.Monospace,
          fontSize = 9.5.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "B: ${clash.incomingEvent.title} (${formatEventTimeRange(clash.incomingEvent.startEpochMs, clash.incomingEvent.endEpochMs)})",
          color = TextPureWhite,
          fontFamily = FontFamily.Monospace,
          fontSize = 9.5.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          if (clash.incomingEvent.id > 0L) {
            ActionChip(
              text = "KEEP NEW",
              tint = accent,
              enabled = true,
              onClick = { onKeepEvent(clash.clashId, clash.incomingEvent.id) },
            )
          }
          if (clash.existingEvent.id > 0L) {
            ActionChip(
              text = "KEEP OLD",
              tint = accent,
              enabled = true,
              onClick = { onKeepEvent(clash.clashId, clash.existingEvent.id) },
            )
          }
          ActionChip(
            text = "IGNORE",
            tint = TextSilver,
            enabled = true,
            onClick = { onIgnoreClash(clash.clashId) },
          )
        }
      }
    }
  }
}

@Composable
private fun EventPlannerCard(
  events: List<ScheduledEvent>,
  draftTitle: String,
  draftTime: String,
  draftLocation: String,
  accent: Color,
  onTitleChange: (String) -> Unit,
  onTimeChange: (String) -> Unit,
  onLocationChange: (String) -> Unit,
  onAddEvent: () -> Unit,
  onDismissEvent: (Long) -> Unit,
) {
  val style = LocalLauncherStyle.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .starkHudPlate(
        accent = accent,
        focused = events.isNotEmpty(),
        showBrackets = true,
        bracketScale = 0.56f,
        plateAlpha = 0.90f,
        style = style,
      )
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "EVENT PLANNER (MSG + CALENDAR)",
        color = TextSilver,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = "${events.size} UPCOMING",
        color = accent,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
      )
    }

    // Quick manual event add row
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .weight(1.4f)
          .jarvisInputPlate(accent)
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        if (draftTitle.isEmpty()) {
          Text(
            text = "Event title",
            color = TextSilver.copy(alpha = 0.50f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
          )
        }
        BasicTextField(
          value = draftTitle,
          onValueChange = onTitleChange,
          singleLine = true,
          textStyle = TextStyle(color = TextPureWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
          cursorBrush = SolidColor(accent),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      Box(
        modifier = Modifier
          .weight(0.8f)
          .jarvisInputPlate(accent)
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        if (draftTime.isEmpty()) {
          Text(
            text = "18:30 / +2h",
            color = TextSilver.copy(alpha = 0.50f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
          )
        }
        BasicTextField(
          value = draftTime,
          onValueChange = onTimeChange,
          singleLine = true,
          textStyle = TextStyle(color = TextPureWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
          cursorBrush = SolidColor(accent),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      ActionChip(
        text = "+ ADD",
        tint = accent,
        enabled = draftTitle.isNotBlank(),
        onClick = onAddEvent,
      )
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .jarvisInputPlate(accent)
        .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
      if (draftLocation.isEmpty()) {
        Text(
          text = "Location (optional, flags tight travel gaps)",
          color = TextSilver.copy(alpha = 0.50f),
          fontFamily = FontFamily.Monospace,
          fontSize = 9.5.sp,
          maxLines = 1,
        )
      }
      BasicTextField(
        value = draftLocation,
        onValueChange = onLocationChange,
        singleLine = true,
        textStyle = TextStyle(color = TextPureWhite, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (draftTitle.isNotBlank()) onAddEvent() }),
        modifier = Modifier.fillMaxWidth(),
      )
    }

    if (events.isEmpty()) {
      Text(
        text = "No upcoming events yet. Incoming message plans & synced calendar events appear here.",
        color = TextSilver.copy(alpha = 0.58f),
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
      )
    } else {
      events.take(8).forEach { ev ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(style.voidTop.copy(alpha = 0.72f))
            .border(0.6.dp, accent.copy(alpha = 0.28f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "[${ev.source.label}] ${ev.title}",
              color = TextPureWhite,
              fontFamily = FontFamily.Monospace,
              fontSize = 9.5.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            val locSuffix = if (ev.location.isNotBlank()) " · ${ev.location}" else ""
            Text(
              text = "${formatEventTimeRange(ev.startEpochMs, ev.endEpochMs)}$locSuffix",
              color = TextSilver.copy(alpha = 0.82f),
              fontFamily = FontFamily.Monospace,
              fontSize = 8.5.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (ev.id > 0L) {
            Spacer(modifier = Modifier.width(6.dp))
            ActionChip(
              text = "X",
              tint = CrimsonBright,
              enabled = true,
              onClick = { onDismissEvent(ev.id) },
            )
          }
        }
      }
    }
  }
}

private fun parseDraftEventTimeMs(raw: String, nowMs: Long = System.currentTimeMillis()): Long {
  val trimmed = raw.trim().lowercase()
  if (trimmed.isEmpty()) return nowMs + 60 * 60_000L
  Regex("""^\+(\d+)\s*h$""").matchEntire(trimmed)?.let { m ->
    val hours = m.groupValues[1].toLongOrNull() ?: 1L
    return nowMs + hours.coerceIn(1L, 168L) * 3_600_000L
  }
  Regex("""^\+(\d+)\s*m$""").matchEntire(trimmed)?.let { m ->
    val mins = m.groupValues[1].toLongOrNull() ?: 30L
    return nowMs + mins.coerceIn(1L, 1440L) * 60_000L
  }
  Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(trimmed)?.let { m ->
    val h = m.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: 18
    val min = m.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
    val cal = java.util.Calendar.getInstance().apply {
      timeInMillis = nowMs
      set(java.util.Calendar.HOUR_OF_DAY, h)
      set(java.util.Calendar.MINUTE, min)
      set(java.util.Calendar.SECOND, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= nowMs - 5 * 60_000L) {
      cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    return cal.timeInMillis
  }
  return nowMs + 60 * 60_000L
}

private fun formatEventTimeRange(startMs: Long, endMs: Long): String {
  val dayFmt = java.text.SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
  val timeFmt = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
  return "${dayFmt.format(java.util.Date(startMs))}-${timeFmt.format(java.util.Date(endMs))}"
}

@Composable
private fun Modifier.jarvisInputPlate(accent: Color): Modifier {
  val style = LocalLauncherStyle.current
  return this
    .background(style.voidTop.copy(alpha = 0.94f))
    .border(0.8.dp, accent.copy(alpha = style.idleBorderAlpha))
}

@Composable
private fun ActionChip(
  text: String,
  tint: Color,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val style = LocalLauncherStyle.current
  val borderTint = if (tint == CrimsonBright) Crimson else tint
  Box(
    modifier = Modifier
      .background(if (enabled) style.voidBottom.copy(alpha = 0.92f) else style.voidTop.copy(alpha = 0.65f))
      .border(
        0.8.dp,
        if (enabled) borderTint.copy(alpha = style.focusedBorderAlpha * 0.85f) else Color.White.copy(alpha = 0.16f),
      )
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    Text(
      text = text,
      color = if (enabled) tint else tint.copy(alpha = 0.42f),
      fontFamily = FontFamily.Monospace,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}
