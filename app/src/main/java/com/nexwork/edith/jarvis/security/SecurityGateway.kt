package com.nexwork.edith.jarvis.security

import java.util.Locale

/**
 * Tier 0–3 Autonomy Matrix governing all Jarvis senses and hands.
 */
enum class AutonomyTier(val level: Int, val label: String) {
  TIER_0_READ_ONLY(0, "READ ONLY"),
  TIER_1_AUTO_SAFE(1, "AUTO SAFE"),
  TIER_2_AUTO_TRUSTED(2, "AUTO TRUSTED"),
  TIER_3_BIOMETRIC_CONFIRM(3, "BIOMETRIC REQUIRED"),
}

enum class JarvisActionType(val countsTowardVelocity: Boolean) {
  GET_SCREEN_TREE(false),
  OPEN_APP(true),
  SCROLL_NODE(true),
  CLICK_NODE(true),
  INPUT_TEXT(true),
  REPLY_NOTIFICATION(false),
  ASK_USER_CONFIRMATION(false),
}

enum class DenyCode {
  RESTRICTED_PACKAGE,
  PACKAGE_LOCK_MISMATCH,
  VELOCITY_CAP_EXCEEDED,
  CONTACT_RATE_LIMITED,
  DLP_BLOCKED,
}

sealed interface SecurityVerdict {
  data class Allow(val tier: AutonomyTier, val reason: String) : SecurityVerdict
  data class RequireBiometric(
    val tier: AutonomyTier = AutonomyTier.TIER_3_BIOMETRIC_CONFIRM,
    val actionSummary: String,
    val reason: String,
  ) : SecurityVerdict

  data class Deny(val code: DenyCode, val reason: String) : SecurityVerdict
}

data class SecurityActionRequest(
  val actionType: JarvisActionType,
  val activePackage: String = "",
  val expectedPackage: String? = null,
  val targetPackage: String? = null,
  val targetLabel: String = "",
  val outboundText: String? = null,
  val contactName: String = "",
  val isTrustedContact: Boolean = false,
  val runStepIndex: Int = 0,
  val reason: String = "",
)

/**
 * Central Milestone 2 security firewall enforcing:
 * - Hardcoded launcher self-ignore & financial/installer package blacklist
 * - TOCTOU package-lock verification
 * - Tier 0–3 Autonomy Matrix with biometric escalation for sensitive UI actions & untrusted replies
 * - Outbound DLP regex firewall blocking 4–8 digit OTPs, cards, and API/bearer secrets
 * - 10-step global UI velocity cap + 2-replies-per-5-minutes per-contact rate limiter
 */
class SecurityGateway(
  private val selfPackageName: String,
  private val contactMaxReplies: Int = DEFAULT_CONTACT_MAX_REPLIES,
  private val contactWindowMs: Long = DEFAULT_CONTACT_WINDOW_MS,
  private val maxGlobalUiSteps: Int = DEFAULT_MAX_GLOBAL_UI_STEPS,
  private val velocityWindowMs: Long = DEFAULT_VELOCITY_WINDOW_MS,
  private val clockMs: () -> Long = System::currentTimeMillis,
) {

  private val lock = Any()
  private val contactReplyLog = HashMap<String, ArrayDeque<Long>>()
  private val globalUiTimestamps = ArrayDeque<Long>()

  fun evaluateAndConsume(request: SecurityActionRequest): SecurityVerdict {
    val inspectedPkg = (request.targetPackage?.takeIf { it.isNotBlank() } ?: request.activePackage).trim()

    // 1. Package Blacklist & Self-Launcher Firewall (checked before any read or write)
    if (inspectedPkg.isNotEmpty() && isRestrictedPackage(inspectedPkg, selfPackageName)) {
      return SecurityVerdict.Deny(
        code = DenyCode.RESTRICTED_PACKAGE,
        reason = "Security firewall blocked restricted package: $inspectedPkg",
      )
    }
    if (request.activePackage.isNotBlank() && isRestrictedPackage(request.activePackage, selfPackageName)) {
      return SecurityVerdict.Deny(
        code = DenyCode.RESTRICTED_PACKAGE,
        reason = "Active window switched to restricted package: ${request.activePackage}",
      )
    }

    // 2. TOCTOU Package-Lock Check
    val expected = request.expectedPackage?.trim()?.takeIf { it.isNotEmpty() }
    if (expected != null && request.actionType != JarvisActionType.OPEN_APP) {
      val active = request.activePackage.trim()
      if (active.isNotEmpty() && !active.equals(expected, ignoreCase = true)) {
        return SecurityVerdict.Deny(
          code = DenyCode.PACKAGE_LOCK_MISMATCH,
          reason = "Package-lock mismatch: expected $expected, active window is $active",
        )
      }
    }

    // 3. Outbound DLP Regex Firewall (inputs and replies)
    val outbound = request.outboundText
    if (outbound != null &&
      (request.actionType == JarvisActionType.INPUT_TEXT || request.actionType == JarvisActionType.REPLY_NOTIFICATION)
    ) {
      val dlpViolation = inspectOutboundText(outbound)
      if (dlpViolation != null) {
        return SecurityVerdict.Deny(
          code = DenyCode.DLP_BLOCKED,
          reason = dlpViolation,
        )
      }
    }

    // 4. 10-Step Global UI Velocity Cap
    if (request.actionType.countsTowardVelocity) {
      val now = clockMs()
      synchronized(lock) {
        pruneDequeLocked(globalUiTimestamps, now, velocityWindowMs)
        if (request.runStepIndex >= maxGlobalUiSteps || globalUiTimestamps.size >= maxGlobalUiSteps) {
          return SecurityVerdict.Deny(
            code = DenyCode.VELOCITY_CAP_EXCEEDED,
            reason = "10-step global UI velocity cap reached ($maxGlobalUiSteps steps / ${velocityWindowMs / 1000}s)",
          )
        }
        globalUiTimestamps.addLast(now)
      }
    }

    // 5. Per-Contact Rate-Limiter + Tier 0–3 Autonomy Matrix
    return when (request.actionType) {
      JarvisActionType.GET_SCREEN_TREE -> {
        SecurityVerdict.Allow(AutonomyTier.TIER_0_READ_ONLY, "Tier 0 passive tree inspection permitted")
      }

      JarvisActionType.OPEN_APP,
      JarvisActionType.SCROLL_NODE -> {
        SecurityVerdict.Allow(AutonomyTier.TIER_1_AUTO_SAFE, "Tier 1 navigation action permitted")
      }

      JarvisActionType.CLICK_NODE,
      JarvisActionType.INPUT_TEXT -> {
        if (isSensitiveUiTarget(request.targetLabel)) {
          val verb = if (request.actionType == JarvisActionType.CLICK_NODE) "Tap" else "Input"
          SecurityVerdict.RequireBiometric(
            tier = AutonomyTier.TIER_3_BIOMETRIC_CONFIRM,
            actionSummary = "$verb '${request.targetLabel.ifBlank { inspectedPkg }}'",
            reason = "Sensitive UI action requires explicit Tier 3 biometric approval",
          )
        } else {
          SecurityVerdict.Allow(AutonomyTier.TIER_2_AUTO_TRUSTED, "Tier 2 UI step within security bounds")
        }
      }

      JarvisActionType.REPLY_NOTIFICATION -> {
        val contact = normalizeContactKey(inspectedPkg, request.contactName)
        if (!request.isTrustedContact) {
          SecurityVerdict.RequireBiometric(
            tier = AutonomyTier.TIER_3_BIOMETRIC_CONFIRM,
            actionSummary = "Reply to ${request.contactName.ifBlank { "contact" }}",
            reason = "Untrusted contact reply escalated to Tier 3 biometric confirmation",
          )
        } else {
          val now = clockMs()
          synchronized(lock) {
            val history = contactReplyLog.getOrPut(contact) { ArrayDeque() }
            pruneDequeLocked(history, now, contactWindowMs)
            if (history.size >= contactMaxReplies) {
              return SecurityVerdict.Deny(
                code = DenyCode.CONTACT_RATE_LIMITED,
                reason = "Rate limit reached for ${request.contactName}: max $contactMaxReplies auto-replies per 5 mins",
              )
            }
          }
          SecurityVerdict.Allow(AutonomyTier.TIER_2_AUTO_TRUSTED, "Tier 2 auto-reply allowed for trusted contact")
        }
      }

      JarvisActionType.ASK_USER_CONFIRMATION -> {
        SecurityVerdict.RequireBiometric(
          tier = AutonomyTier.TIER_3_BIOMETRIC_CONFIRM,
          actionSummary = request.targetLabel.ifBlank { request.reason.ifBlank { "Confirm sensitive action" } },
          reason = request.reason.ifBlank { "Planner requested Tier 3 biometric confirmation" },
        )
      }
    }
  }

  fun recordTrustedAutoReply(packageName: String, contactName: String) {
    val key = normalizeContactKey(packageName, contactName)
    val now = clockMs()
    synchronized(lock) {
      val history = contactReplyLog.getOrPut(key) { ArrayDeque() }
      pruneDequeLocked(history, now, contactWindowMs)
      history.addLast(now)
    }
  }

  fun remainingAutoReplyQuota(packageName: String, contactName: String): Int {
    val key = normalizeContactKey(packageName, contactName)
    val now = clockMs()
    synchronized(lock) {
      val history = contactReplyLog[key] ?: return contactMaxReplies
      pruneDequeLocked(history, now, contactWindowMs)
      return (contactMaxReplies - history.size).coerceAtLeast(0)
    }
  }

  fun remainingUiVelocityBudget(): Int {
    val now = clockMs()
    synchronized(lock) {
      pruneDequeLocked(globalUiTimestamps, now, velocityWindowMs)
      return (maxGlobalUiSteps - globalUiTimestamps.size).coerceAtLeast(0)
    }
  }

  private fun pruneDequeLocked(deque: ArrayDeque<Long>, now: Long, windowMs: Long) {
    while (deque.isNotEmpty() && (now - deque.first()) >= windowMs) {
      deque.removeFirst()
    }
  }

  companion object {
    const val DEFAULT_CONTACT_MAX_REPLIES = 2
    const val DEFAULT_CONTACT_WINDOW_MS = 5L * 60L * 1000L
    const val DEFAULT_MAX_GLOBAL_UI_STEPS = 10
    const val DEFAULT_VELOCITY_WINDOW_MS = 60L * 1000L

    private val RESTRICTED_EXACT_PACKAGES = setOf(
      "com.nexwork.edith",
      // Google Pay & Wallet
      "com.google.android.apps.nbu.paisa.user",
      "com.google.android.apps.walletnfcrel",
      // PhonePe
      "com.phonepe.app",
      "com.phonepe.app.business",
      // Paytm
      "net.one97.paytm",
      "com.paytmmall",
      "com.paytm.business",
      // BHIM / CRED / Major Indian & Global Banking Apps
      "in.org.npci.upiapp",
      "com.dreamplug.androidapp",
      "com.csam.icici.bank.imobile",
      "com.sbi.lotusintouch",
      "com.sbi.sbifreedomplus",
      "com.snapwork.hdfc",
      "com.axis.mobile",
      "com.msf.kbank.mobile",
      "com.freecharge.android",
      "com.mobikwik_new",
      "com.paypal.android.p2pmobile",
      "com.venmo",
      "com.revolut.revolut",
      // Installers, Permission Controllers, SystemUI lock/biometric surfaces
      "com.android.packageinstaller",
      "com.google.android.packageinstaller",
      "com.android.permissioncontroller",
      "com.google.android.permissioncontroller",
      "com.android.systemui",
    )

    private val RESTRICTED_PACKAGE_TOKENS = listOf(
      "packageinstaller",
      "permissioncontroller",
      "mobilebanking",
      ".bank.",
      ".banking",
      "paisa",
      "phonepe",
      "paytm",
      "upiapp",
      "walletnfcrel",
      "biometricprompt",
      "credentialmanager",
    )

    private val OTP_PIN_REGEX = Regex("""(?<!\d)\d{4,8}(?!\d)""")
    private val API_SECRET_REGEX = Regex(
      """(?:AIza[0-9A-Za-z\-_]{20,}|sk-[A-Za-z0-9\-_]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|Bearer\s+[A-Za-z0-9\-_.~+/]+=*)""",
      RegexOption.IGNORE_CASE,
    )
    private val CARD_NUMBER_REGEX = Regex("""(?<!\d)(?:\d[ -]?){13,19}(?!\d)""")
    private val LABELED_SECRET_REGEX = Regex(
      """\b(?:otp|pin|cvv|password|passcode|secret|api[_-]?key)\b\s*[:=\-]?\s*\S{3,}""",
      RegexOption.IGNORE_CASE,
    )

    private val SENSITIVE_UI_KEYWORDS = listOf(
      "pay ", "pay now", "send money", "transfer", "checkout", "confirm payment",
      "uninstall", "delete account", "erase", "factory reset", "authorize",
    )

    /** Returns true if [packageName] is our own launcher, a financial app, or installer/security UI. */
    fun isRestrictedPackage(packageName: String, selfPackageName: String = "com.nexwork.edith"): Boolean {
      val norm = packageName.trim().lowercase(Locale.ROOT)
      if (norm.isEmpty()) return false
      val selfNorm = selfPackageName.trim().lowercase(Locale.ROOT)
      if (norm == selfNorm || norm == "com.nexwork.edith") return true
      if (norm in RESTRICTED_EXACT_PACKAGES) return true
      return RESTRICTED_PACKAGE_TOKENS.any { token -> norm.contains(token) }
    }

    /**
     * Outbound DLP Regex Firewall: returns a human-readable block reason when [text] contains
     * 4–8 digit OTP/PINs, card numbers, API keys, or labeled secrets; returns null when safe.
     */
    fun inspectOutboundText(text: String): String? {
      if (text.isBlank()) return null
      if (API_SECRET_REGEX.containsMatchIn(text)) {
        return "DLP blocked outbound transmission: API key or bearer secret detected"
      }
      if (CARD_NUMBER_REGEX.containsMatchIn(text)) {
        return "DLP blocked outbound transmission: payment card digits detected"
      }
      if (LABELED_SECRET_REGEX.containsMatchIn(text)) {
        return "DLP blocked outbound transmission: labeled secret/credential detected"
      }
      if (OTP_PIN_REGEX.containsMatchIn(text)) {
        return "DLP blocked outbound transmission: 4–8 digit OTP or PIN code detected"
      }
      return null
    }

    internal fun isSensitiveUiTarget(label: String): Boolean {
      val norm = label.trim().lowercase(Locale.ROOT)
      if (norm.isEmpty()) return false
      return SENSITIVE_UI_KEYWORDS.any { kw -> norm.contains(kw) || norm == kw.trim() }
    }

    internal fun normalizeContactKey(packageName: String, contactName: String): String {
      val pkg = packageName.trim().lowercase(Locale.ROOT)
      val name = contactName.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
      return "$pkg|$name"
    }
  }
}
