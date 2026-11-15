package com.nexwork.edith.ui.hud

import android.content.pm.ApplicationInfo
import com.nexwork.edith.model.AppEntry

/** One category bucket of the browse deck: a name and the apps filed under it. */
data class AppGroup(val name: String, val apps: List<AppEntry>) {
  val usageMillis: Long get() = apps.sumOf { it.usageTimeMillis14d }
}

/**
 * Files every installed app into one category bucket and orders the buckets by how much they
 * were actually used over the last 14 days, so the group the user lives in leads the deck.
 * Unknown system packages fall to SYSTEM and unknown third-party packages to OTHER — no app
 * is ever dropped from the index.
 */
fun groupApps(apps: List<AppEntry>): List<AppGroup> {
  val buckets = LinkedHashMap<String, MutableList<AppEntry>>()
  for (app in apps) buckets.getOrPut(categoryOf(app)) { mutableListOf() } += app
  return buckets
    .map { (name, members) -> AppGroup(name, rankAppsForBrowse(members)) }
    .sortedWith(
      compareByDescending<AppGroup> { it.usageMillis }
        .thenByDescending { it.apps.size }
        .thenBy { it.name }
    )
}

/**
 * Category name for one app. Package identity wins over keywords, keywords over Android
 * `ApplicationInfo.category`, and Android category over SYSTEM/OTHER fallback.
 */
internal fun categoryOf(app: AppEntry): String {
  val pkg = app.packageName.lowercase()
  KnownCategories[pkg]?.let { return it }
  return when {
    GameRoots.any { pkg.startsWith(it) } || "game" in pkg -> "GAMES"
    FinanceHints.any { it in pkg } -> "FINANCE"
    CommsHints.any { it in pkg } -> "COMMS"
    SocialHints.any { it in pkg } -> "SOCIAL"
    MediaHints.any { it in pkg } -> "MEDIA"
    ToolHints.any { it in pkg } -> "TOOLS"
    else -> mapAndroidCategory(app.androidCategory)
      ?: if (app.isMotoApp || app.isSystemApp) "SYSTEM" else "OTHER"
  }
}

private fun mapAndroidCategory(androidCategory: Int): String? = when (androidCategory) {
  ApplicationInfo.CATEGORY_GAME -> "GAMES"
  ApplicationInfo.CATEGORY_AUDIO,
  ApplicationInfo.CATEGORY_VIDEO,
  ApplicationInfo.CATEGORY_IMAGE -> "MEDIA"
  ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL"
  ApplicationInfo.CATEGORY_MAPS,
  ApplicationInfo.CATEGORY_PRODUCTIVITY,
  ApplicationInfo.CATEGORY_NEWS,
  ApplicationInfo.CATEGORY_ACCESSIBILITY -> "TOOLS"
  else -> null
}

private val KnownCategories: Map<String, String> = buildMap {
  // Communications
  listOf(
    "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
    "org.thoughtcrime.securesms", "com.google.android.apps.messaging",
    "com.android.messaging", "com.google.android.dialer", "com.android.dialer",
    "com.google.android.contacts", "com.android.contacts", "com.google.android.gm",
    "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
    "com.discord", "com.slack", "com.microsoft.teams", "com.skype.raider",
    "com.viber.voip", "us.zoom.videomeetings", "com.google.android.apps.tachyon",
    "com.facebook.orca",
  ).forEach { put(it, "COMMS") }

  // Social
  listOf(
    "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana",
    "com.twitter.android", "com.snapchat.android", "com.reddit.frontpage",
    "com.zhiliaoapp.musically", "com.linkedin.android", "com.pinterest",
    "com.google.android.apps.plus",
  ).forEach { put(it, "SOCIAL") }

  // Media
  listOf(
    "com.google.android.youtube", "com.google.android.apps.youtube.music",
    "com.google.android.apps.photos", "com.google.android.apps.photosgo",
    "com.google.android.apps.magazines", "com.spotify.music",
    "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient",
    "com.motorola.camera3", "com.motorola.camera2", "com.google.android.GoogleCamera",
    "com.android.camera", "com.android.camera2", "org.videolan.vlc",
    "com.google.android.videos", "com.google.android.apps.podcasts",
  ).forEach { put(it, "MEDIA") }

  // Finance
  listOf(
    "com.google.android.apps.nbu.paisa.user", "com.google.android.apps.walletnfcrel",
    "com.google.android.gms.wallet", "com.paypal.android.p2pmobile",
  ).forEach { put(it, "FINANCE") }

  // Tools
  listOf(
    "com.android.chrome", "com.google.android.googlequicksearchbox",
    "com.google.android.apps.nbu.files", "com.android.documentsui",
    "com.google.android.documentsui", "com.google.android.deskclock",
    "com.android.deskclock", "com.google.android.calculator", "com.android.calculator2",
    "com.google.android.calendar", "com.android.calendar", "com.google.android.apps.maps",
    "com.google.android.keep", "com.google.android.apps.docs",
    "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets",
    "com.google.android.apps.docs.editors.slides", "com.google.android.apps.translate",
    "com.google.android.apps.authenticator2",
  ).forEach { put(it, "TOOLS") }
}

private val GameRoots = listOf(
  "com.supercell", "com.roblox", "com.mojang", "com.rockstargames", "com.ea.",
  "com.eamobile", "com.gameloft", "com.king.", "com.zynga", "com.dream11",
  "com.epicgames", "com.nianticlabs", "com.innersloth", "com.activision",
  "com.tencent", "com.playrix", "com.outfit7", "com.mihoyo", "com.dts.freefireth",
  "com.garena", "com.kiloo", "com.rovio", "com.halfbrick", "com.sega",
  "com.ubisoft", "com.miniclip", "com.elex", "com.netmarble", "com.nexon",
)

private val FinanceHints = listOf(
  "bank", "wallet", "paytm", "phonepe", "paise", "upi", "finance",
  "trading", "stocks", "invest", "mutual", "insurance", "loan", "hdfc", "icici",
  "axisbank", "kotak", "cred", "groww", "zerodha", "kite", "razorpay",
)

private val CommsHints = listOf(
  "whatsapp", "telegram", "messag", "dialer", "telecom", "contacts", "mail",
  "discord", "slack", "signal", "viber", "skype", "teams",
)

private val SocialHints = listOf(
  "instagram", "facebook", "katana", "twitter", "snapchat", "reddit",
  "musically", "tiktok", "linkedin", "pinterest", "threads", "barcelona",
)

private val MediaHints = listOf(
  "youtube", "spotify", "netflix", "photo", "gallery", "camera", "music",
  "video", "player", "podcast", "vlc", "hotstar", "primevideo", "recorder",
)

private val ToolHints = listOf(
  "calculator", "calendar", "clock", "documentsui", "file", "browser",
  "chrome", "firefox", "maps", "translate", "scanner", "vpn",
)
