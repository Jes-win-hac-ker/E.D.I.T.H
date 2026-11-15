package com.nexwork.edith.ui.hud

import android.content.pm.ApplicationInfo
import com.nexwork.edith.model.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupingTest {

  private fun app(
    label: String,
    pkg: String,
    usage: Long = 0L,
    system: Boolean = false,
    moto: Boolean = false,
    androidCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED,
  ) = AppEntry(
    label = label,
    packageName = pkg,
    activityName = "$pkg.Main",
    isSystemApp = system,
    isMotoApp = moto,
    usageTimeMillis14d = usage,
    androidCategory = androidCategory,
  )

  @Test
  fun knownPackagesLandInTheirCategory() {
    assertEquals("COMMS", categoryOf(app("WhatsApp", "com.whatsapp")))
    assertEquals("SOCIAL", categoryOf(app("Instagram", "com.instagram.android")))
    assertEquals("MEDIA", categoryOf(app("YouTube", "com.google.android.youtube")))
    assertEquals("FINANCE", categoryOf(app("GPay", "com.google.android.apps.nbu.paisa.user")))
    assertEquals("TOOLS", categoryOf(app("Chrome", "com.android.chrome")))
  }

  @Test
  fun keywordsCatchUnknownPackages() {
    assertEquals("FINANCE", categoryOf(app("Some Bank", "com.example.bankapp")))
    assertEquals("TOOLS", categoryOf(app("Files", "com.example.filemanager")))
    assertEquals("MEDIA", categoryOf(app("Player", "com.example.videoplayer")))
    assertEquals("GAMES", categoryOf(app("Clash", "com.supercell.clashofclans")))
  }

  @Test
  fun unknownAppsFallToSystemOrOther() {
    assertEquals("SYSTEM", categoryOf(app("Moto Care", "com.motorola.motocare", moto = true)))
    assertEquals("SYSTEM", categoryOf(app("Settings", "com.android.settings", system = true)))
    assertEquals("OTHER", categoryOf(app("Mystery", "com.example.mystery")))
  }

  @Test
  fun everyAppIsFiledExactlyOnce() {
    val apps = listOf(
      app("WhatsApp", "com.whatsapp"),
      app("Instagram", "com.instagram.android"),
      app("Mystery", "com.example.mystery"),
      app("Settings", "com.android.settings", system = true),
    )
    val filed = groupApps(apps).flatMap { it.apps }
    assertEquals(apps.size, filed.size)
    assertEquals(apps.map { it.packageName }.toSet(), filed.map { it.packageName }.toSet())
  }

  @Test
  fun groupsAreOrderedByAggregateUsage() {
    val apps = listOf(
      app("A", "com.whatsapp", usage = 1_000L),
      app("B", "com.google.android.youtube", usage = 5_000L),
      app("C", "com.google.android.youtube", usage = 1_000L),
    )
    val groups = groupApps(apps)
    assertEquals("MEDIA", groups.first().name)
    assertEquals("COMMS", groups.last().name)
    assertEquals(6_000L, groups.first().usageMillis)
  }

  @Test
  fun membersAreRankedByUsage() {
    val apps = listOf(
      app("Low", "com.whatsapp", usage = 10L),
      app("High", "org.telegram.messenger", usage = 900L),
    )
    val comms = groupApps(apps).single { it.name == "COMMS" }
    assertEquals(listOf("High", "Low"), comms.apps.map { it.label })
  }

  @Test
  fun emptyInputYieldsNoGroups() {
    assertTrue(groupApps(emptyList()).isEmpty())
  }

  @Test
  fun androidCategoryMapsUnknownPackagesBeforeFallback() {
    assertEquals(
      "GAMES",
      categoryOf(app("Puzzle", "com.example.puzzle", androidCategory = ApplicationInfo.CATEGORY_GAME)),
    )
    assertEquals(
      "MEDIA",
      categoryOf(app("Tune", "com.example.tune", androidCategory = ApplicationInfo.CATEGORY_AUDIO)),
    )
    assertEquals(
      "MEDIA",
      categoryOf(app("Stream", "com.example.stream", androidCategory = ApplicationInfo.CATEGORY_VIDEO)),
    )
    assertEquals(
      "MEDIA",
      categoryOf(app("Canvas", "com.example.canvas", androidCategory = ApplicationInfo.CATEGORY_IMAGE)),
    )
    assertEquals(
      "SOCIAL",
      categoryOf(app("Circle", "com.example.circle", androidCategory = ApplicationInfo.CATEGORY_SOCIAL)),
    )
    assertEquals(
      "TOOLS",
      categoryOf(app("Atlas", "com.example.atlas", androidCategory = ApplicationInfo.CATEGORY_MAPS)),
    )
    assertEquals(
      "TOOLS",
      categoryOf(app("Notes", "com.example.notes", androidCategory = ApplicationInfo.CATEGORY_PRODUCTIVITY)),
    )
    assertEquals(
      "TOOLS",
      categoryOf(app("Daily", "com.example.daily", androidCategory = ApplicationInfo.CATEGORY_NEWS)),
    )
    assertEquals(
      "TOOLS",
      categoryOf(app("Reader", "com.example.reader", androidCategory = ApplicationInfo.CATEGORY_ACCESSIBILITY)),
    )
  }

  @Test
  fun knownCategoriesAndKeywordsOverrideAndroidCategory() {
    assertEquals(
      "COMMS",
      categoryOf(app("WhatsApp", "com.whatsapp", androidCategory = ApplicationInfo.CATEGORY_SOCIAL)),
    )
    assertEquals(
      "FINANCE",
      categoryOf(app("Ledger", "com.example.bankapp", androidCategory = ApplicationInfo.CATEGORY_PRODUCTIVITY)),
    )
  }

  @Test
  fun androidCategoryOverridesSystemFlagWhenDeclared() {
    assertEquals(
      "MEDIA",
      categoryOf(
        app(
          "System Player",
          "com.vendor.proprietary",
          system = true,
          androidCategory = ApplicationInfo.CATEGORY_AUDIO,
        )
      ),
    )
  }
}
