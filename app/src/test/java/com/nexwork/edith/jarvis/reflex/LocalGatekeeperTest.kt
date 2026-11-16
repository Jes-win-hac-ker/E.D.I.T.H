package com.nexwork.edith.jarvis.reflex

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGatekeeperTest {

  private var fakeNowMs = 1_000_000L
  private val gatekeeper = LocalGatekeeper(
    selfPackageName = "com.nexwork.edith",
    windowMs = 10_000L,
    clockMs = { fakeNowMs },
  )

  @Test
  fun evaluate_ignoresSelfAndOngoingAndEmptyNotifications() {
    val selfDecision = gatekeeper.evaluate(
      candidate(packageName = "com.nexwork.edith", body = "Jarvis active")
    )
    assertEquals(GatekeeperDecision.Ignore(IgnoreReason.SELF_PACKAGE), selfDecision)

    val ongoingFlagDecision = gatekeeper.evaluate(
      candidate(flags = Notification.FLAG_ONGOING_EVENT)
    )
    assertEquals(GatekeeperDecision.Ignore(IgnoreReason.ONGOING), ongoingFlagDecision)

    val fgsFlagDecision = gatekeeper.evaluate(
      candidate(flags = Notification.FLAG_FOREGROUND_SERVICE)
    )
    assertEquals(GatekeeperDecision.Ignore(IgnoreReason.ONGOING), fgsFlagDecision)

    val isOngoingDecision = gatekeeper.evaluate(
      candidate(isOngoing = true)
    )
    assertEquals(GatekeeperDecision.Ignore(IgnoreReason.ONGOING), isOngoingDecision)

    val summaryDecision = gatekeeper.evaluate(
      candidate(flags = Notification.FLAG_GROUP_SUMMARY, hasMessagingStyle = false, hasRemoteInput = false)
    )
    assertEquals(GatekeeperDecision.Ignore(IgnoreReason.GROUP_SUMMARY), summaryDecision)

    val emptyDecision = gatekeeper.evaluate(
      candidate(title = "", sender = "  ", body = "\n")
    )
    assertEquals(GatekeeperDecision.Ignore(IgnoreReason.EMPTY_CONTENT), emptyDecision)
  }

  @Test
  fun evaluate_deduplicatesWithin10SecondsAndAllowsAfterWindow() {
    val msg = candidate(sender = "Alex", body = "Landing at 5pm")

    val first = gatekeeper.evaluate(msg)
    assertTrue(first is GatekeeperDecision.Allow)

    // Duplicate with whitespace variation within 10s must be ignored
    fakeNowMs += 4_500L
    val duplicate = gatekeeper.evaluate(msg.copy(body = "  Landing   at 5pm "))
    assertTrue(duplicate is GatekeeperDecision.Ignore)
    assertEquals(IgnoreReason.DUPLICATE_10S, (duplicate as GatekeeperDecision.Ignore).reason)

    // Distinct message from same sender within 10s must be allowed
    val distinct = gatekeeper.evaluate(msg.copy(body = "Actually 5:15pm"))
    assertTrue(distinct is GatekeeperDecision.Allow)

    // Same original message after >= 10s window expires must be allowed again
    fakeNowMs += 5_500L // total elapsed since `first` = 10_000ms
    val afterWindow = gatekeeper.evaluate(msg)
    assertTrue(afterWindow is GatekeeperDecision.Allow)
  }

  private fun candidate(
    packageName: String = "com.whatsapp",
    title: String = "Alex",
    sender: String = "Alex",
    body: String = "Hello",
    flags: Int = 0,
    isOngoing: Boolean = false,
    hasMessagingStyle: Boolean = true,
    hasRemoteInput: Boolean = true,
  ) = GatekeeperCandidate(
    packageName = packageName,
    title = title,
    sender = sender,
    body = body,
    flags = flags,
    isOngoing = isOngoing,
    hasMessagingStyle = hasMessagingStyle,
    hasRemoteInput = hasRemoteInput,
  )
}
