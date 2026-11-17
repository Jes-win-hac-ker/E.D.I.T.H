package com.nexwork.edith.jarvis.reflex

import com.nexwork.edith.jarvis.core.GroupEngagementRecord

/**
 * Evaluates historical group engagement (`received_count` vs `user_replied_count`) so low-signal
 * noisy group chats are dropped locally without wasting LLM quota, while preserving explicit
 * user mentions and newly observed groups.
 */
object GroupRelevanceScorer {

  const val MIN_RECEIVED_BEFORE_SUPPRESS = 10
  const val MIN_ENGAGEMENT_RATIO = 0.05

  fun shouldSuppressLowEngagement(
    engagement: GroupEngagementRecord?,
    isGroupConversation: Boolean,
    isUserMentioned: Boolean,
    minReceivedThreshold: Int = MIN_RECEIVED_BEFORE_SUPPRESS,
    minReplyRatio: Double = MIN_ENGAGEMENT_RATIO,
  ): Boolean {
    if (!isGroupConversation) return false
    if (isUserMentioned) return false
    if (engagement == null) return false
    if (engagement.receivedCount < minReceivedThreshold) return false
    val ratio = engagement.userRepliedCount.toDouble() / engagement.receivedCount.coerceAtLeast(1).toDouble()
    return engagement.userRepliedCount <= 0 || ratio < minReplyRatio
  }
}
