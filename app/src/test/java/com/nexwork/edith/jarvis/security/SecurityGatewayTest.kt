package com.nexwork.edith.jarvis.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityGatewayTest {

  @Test
  fun restrictedPackages_blockSelfFinancialBankingAndInstallers() {
    assertTrue(SecurityGateway.isRestrictedPackage("com.nexwork.edith"))
    assertTrue(SecurityGateway.isRestrictedPackage("com.google.android.apps.nbu.paisa.user"))
    assertTrue(SecurityGateway.isRestrictedPackage("com.phonepe.app"))
    assertTrue(SecurityGateway.isRestrictedPackage("net.one97.paytm"))
    assertTrue(SecurityGateway.isRestrictedPackage("com.sbi.lotusintouch"))
    assertTrue(SecurityGateway.isRestrictedPackage("com.android.packageinstaller"))
    assertTrue(SecurityGateway.isRestrictedPackage("com.google.android.permissioncontroller"))
    assertTrue(SecurityGateway.isRestrictedPackage("com.example.mobilebanking.pro"))

    assertTrue(!SecurityGateway.isRestrictedPackage("com.whatsapp"))
    assertTrue(!SecurityGateway.isRestrictedPackage("com.google.android.youtube"))
  }

  @Test
  fun dlpFirewall_blocksOtpsPinsCardsAndApiKeys_allowsNormalText() {
    assertNotNull(SecurityGateway.inspectOutboundText("1234"))
    assertNotNull(SecurityGateway.inspectOutboundText("Your verification code is 482910"))
    assertNotNull(SecurityGateway.inspectOutboundText("PIN: 98765432"))
    assertNotNull(SecurityGateway.inspectOutboundText("Here is AIzaSyD1234567890abcdefghij"))
    assertNotNull(SecurityGateway.inspectOutboundText("Card 4111 2222 3333 4444"))

    assertNull(SecurityGateway.inspectOutboundText("On my way home, see you at 5pm!"))
    assertNull(SecurityGateway.inspectOutboundText("Let's grab coffee"))
  }

  @Test
  fun autonomyTiersAndUntrustedReplyEscalationWorkCorrectly() {
    val gw = SecurityGateway("com.nexwork.edith")

    val treeVerdict = gw.evaluateAndConsume(
      SecurityActionRequest(JarvisActionType.GET_SCREEN_TREE, activePackage = "com.whatsapp"),
    )
    assertTrue(treeVerdict is SecurityVerdict.Allow)
    assertEquals(AutonomyTier.TIER_0_READ_ONLY, (treeVerdict as SecurityVerdict.Allow).tier)

    val untrustedReply = gw.evaluateAndConsume(
      SecurityActionRequest(
        actionType = JarvisActionType.REPLY_NOTIFICATION,
        activePackage = "com.whatsapp",
        outboundText = "Hi there",
        contactName = "Stranger",
        isTrustedContact = false,
      ),
    )
    assertTrue(untrustedReply is SecurityVerdict.RequireBiometric)

    val sensitiveClick = gw.evaluateAndConsume(
      SecurityActionRequest(
        actionType = JarvisActionType.CLICK_NODE,
        activePackage = "com.shop.app",
        targetLabel = "Confirm Payment",
      ),
    )
    assertTrue(sensitiveClick is SecurityVerdict.RequireBiometric)
  }

  @Test
  fun perContactRateLimiter_enforcesMax2Per5Minutes() {
    var nowMs = 1_000_000L
    val gw = SecurityGateway("com.nexwork.edith", clockMs = { nowMs })

    fun req(): SecurityActionRequest = SecurityActionRequest(
      actionType = JarvisActionType.REPLY_NOTIFICATION,
      activePackage = "com.whatsapp",
      outboundText = "On it",
      contactName = "Alex",
      isTrustedContact = true,
    )

    assertTrue(gw.evaluateAndConsume(req()) is SecurityVerdict.Allow)
    gw.recordTrustedAutoReply("com.whatsapp", "Alex")

    nowMs += 60_000L
    assertTrue(gw.evaluateAndConsume(req()) is SecurityVerdict.Allow)
    gw.recordTrustedAutoReply("com.whatsapp", "Alex")

    nowMs += 30_000L
    val third = gw.evaluateAndConsume(req())
    assertTrue(third is SecurityVerdict.Deny)
    assertEquals(DenyCode.CONTACT_RATE_LIMITED, (third as SecurityVerdict.Deny).code)

    // Advance past 5-minute window from first reply -> quota recovers cleanly
    nowMs = 1_000_000L + 301_000L
    assertTrue(gw.evaluateAndConsume(req()) is SecurityVerdict.Allow)
  }

  @Test
  fun velocityCapAndPackageLock_blockExceeding10StepsOrWindowMismatch() {
    var nowMs = 10_000L
    val gw = SecurityGateway("com.nexwork.edith", clockMs = { nowMs })

    // TOCTOU Package-Lock check
    val mismatch = gw.evaluateAndConsume(
      SecurityActionRequest(
        actionType = JarvisActionType.CLICK_NODE,
        activePackage = "com.android.chrome",
        expectedPackage = "com.google.android.youtube",
        targetLabel = "Search",
      ),
    )
    assertEquals(DenyCode.PACKAGE_LOCK_MISMATCH, (mismatch as SecurityVerdict.Deny).code)

    // Consume all 10 global UI steps
    repeat(10) { stepIdx ->
      val v = gw.evaluateAndConsume(
        SecurityActionRequest(
          actionType = JarvisActionType.CLICK_NODE,
          activePackage = "com.google.android.youtube",
          expectedPackage = "com.google.android.youtube",
          targetLabel = "Item $stepIdx",
          runStepIndex = stepIdx,
        ),
      )
      assertTrue(v is SecurityVerdict.Allow)
    }

    // 11th step must be denied by velocity cap
    val overCap = gw.evaluateAndConsume(
      SecurityActionRequest(
        actionType = JarvisActionType.CLICK_NODE,
        activePackage = "com.google.android.youtube",
        expectedPackage = "com.google.android.youtube",
        targetLabel = "Overflow",
        runStepIndex = 0,
      ),
    )
    assertEquals(DenyCode.VELOCITY_CAP_EXCEEDED, (overCap as SecurityVerdict.Deny).code)
  }
}
