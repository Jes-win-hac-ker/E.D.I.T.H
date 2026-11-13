package com.nexwork.edith.ui.hud

import com.nexwork.edith.model.AppEntry

/**
 * Filters apps matching the search query strictly by visible display label (not packageName).
 * Tiered priority ranking:
 * 1. Exact match on app label (e.g. "Google" -> "Google")
 * 2. Prefix / Starts-with match (e.g. "Google" -> "Google Maps")
 * 3. Word-boundary starts-with (e.g. "Drive" -> "Google Drive")
 * 4. Substring contains anywhere
 * Within each tier, apps are ordered by:
 * - 14-day foreground usage time
 * - Essential quick-access status
 * - Pinned status
 * - Alphabetical
 */
fun filterMatchingApps(query: String, apps: List<AppEntry>): List<AppEntry> {
  val q = query.trim()
  if (q.isEmpty()) return emptyList()

  val matching = apps.filter { it.label.contains(q, ignoreCase = true) }
  if (matching.isEmpty()) return emptyList()

  return matching.sortedWith(
    compareBy<AppEntry> { app ->
      when {
        app.label.equals(q, ignoreCase = true) -> 0
        app.label.startsWith(q, ignoreCase = true) -> 1
        app.label.split(" ").any { it.startsWith(q, ignoreCase = true) } -> 2
        else -> 3
      }
    }
      .thenByDescending { it.usageTimeMillis14d }
      .thenByDescending { it.isEssentialQuickAccess }
      .thenByDescending { it.isPinned }
      .thenBy { it.label.lowercase() }
  )
}

/** Idle ordering for the browse deck: every app, ranked the way search would rank it. */
fun rankAppsForBrowse(apps: List<AppEntry>): List<AppEntry> =
  apps.sortedWith(
    compareByDescending<AppEntry> { it.usageTimeMillis14d }
      .thenByDescending { it.isEssentialQuickAccess }
      .thenByDescending { it.isPinned }
      .thenBy { it.label.lowercase() }
  )
