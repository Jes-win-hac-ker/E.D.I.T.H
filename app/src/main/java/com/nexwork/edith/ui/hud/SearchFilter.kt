package com.nexwork.edith.ui.hud

import com.nexwork.edith.model.AppEntry

/**
 * Filters apps matching the search query strictly by visible display label (not packageName).
 * Returns an empty list for command/math/web prefixes (`!`, `=`, `?`).
 * Tiered priority ranking:
 * 0. Exact match on app label (e.g. "Google" -> "Google")
 * 1. Prefix / Starts-with match (e.g. "Google" -> "Google Maps")
 * 2. Word-boundary starts-with (e.g. "Drive" -> "Google Drive")
 * 3. Substring contains anywhere
 * 4. Word / CamelCase initials acronym match (e.g. "gm" -> "Google Maps", "wa" -> "WhatsApp")
 * Within each tier, apps are ordered by:
 * - 14-day foreground usage time
 * - Essential quick-access status
 * - Pinned status
 * - Alphabetical
 */
fun filterMatchingApps(query: String, apps: List<AppEntry>): List<AppEntry> {
  val q = query.trim()
  if (q.isEmpty() || q.startsWith("!") || q.startsWith("=") || q.startsWith("?")) {
    return emptyList()
  }

  val ranked = apps.mapNotNull { app ->
    matchTier(q, app.label)?.let { tier -> app to tier }
  }
  if (ranked.isEmpty()) return emptyList()

  return ranked.sortedWith(
    compareBy<Pair<AppEntry, Int>> { it.second }
      .thenByDescending { it.first.usageTimeMillis14d }
      .thenByDescending { it.first.isEssentialQuickAccess }
      .thenByDescending { it.first.isPinned }
      .thenBy { it.first.label.lowercase() }
  ).map { it.first }
}

private fun matchTier(q: String, label: String): Int? =
  when {
    label.equals(q, ignoreCase = true) -> 0
    label.startsWith(q, ignoreCase = true) -> 1
    label.split(" ").any { it.startsWith(q, ignoreCase = true) } -> 2
    label.contains(q, ignoreCase = true) -> 3
    matchesAcronym(q, label) -> 4
    else -> null
  }

private fun matchesAcronym(q: String, label: String): Boolean {
  if (q.length < 2 || q.any { it.isWhitespace() }) return false
  val initials = buildAcronymInitials(label)
  if (initials.length < q.length) return false
  if (initials.startsWith(q, ignoreCase = true)) return true

  var qi = 0
  for (ch in initials) {
    if (ch.equals(q[qi], ignoreCase = true)) {
      qi++
      if (qi == q.length) return true
    }
  }
  return false
}

private fun buildAcronymInitials(label: String): String {
  val initials = StringBuilder()
  var inToken = false
  for (i in label.indices) {
    val c = label[i]
    if (!c.isLetterOrDigit()) {
      inToken = false
      continue
    }
    if (!inToken) {
      initials.append(c)
      inToken = true
      continue
    }
    val prev = label[i - 1]
    val next = label.getOrNull(i + 1)
    val isCamelBoundary =
      (prev.isLowerCase() && c.isUpperCase()) ||
        (prev.isUpperCase() && c.isUpperCase() && next?.isLowerCase() == true) ||
        (prev.isDigit() != c.isDigit())
    if (isCamelBoundary) {
      initials.append(c)
    }
  }
  return initials.toString()
}

/** Idle ordering for the browse deck: every app, ranked the way search would rank it. */
fun rankAppsForBrowse(apps: List<AppEntry>): List<AppEntry> =
  apps.sortedWith(
    compareByDescending<AppEntry> { it.usageTimeMillis14d }
      .thenByDescending { it.isEssentialQuickAccess }
      .thenByDescending { it.isPinned }
      .thenBy { it.label.lowercase() }
  )
