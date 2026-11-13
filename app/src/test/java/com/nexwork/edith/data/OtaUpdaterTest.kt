package com.nexwork.edith.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaUpdaterTest {

  @Test
  fun parseReleaseJson_parsesDirectManifest() {
    val json = """
      {
        "versionCode": 4,
        "versionName": "1.3.0",
        "apkUrl": "https://example.com/edith-1.3.0.apk",
        "sha256": "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"
      }
    """.trimIndent()

    val release = OtaUpdater.parseReleaseJson(json)
    assertEquals(4L, release?.versionCode)
    assertEquals("1.3.0", release?.versionName)
    assertEquals("https://example.com/edith-1.3.0.apk", release?.apkUrl)
    assertEquals(
      "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
      release?.sha256,
    )
  }

  @Test
  fun parseReleaseJson_parsesGitHubReleasePayload() {
    val json = """
      {
        "tag_name": "v1.2.0+3",
        "body": "OTA release\nversionCode: 3\nsha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "assets": [
          {
            "name": "notes.txt",
            "browser_download_url": "https://github.com/Jes-win-hac-ker/E.D.I.T.H/releases/download/v1.2.0+3/notes.txt"
          },
          {
            "name": "app-debug.apk",
            "browser_download_url": "https://github.com/Jes-win-hac-ker/E.D.I.T.H/releases/download/v1.2.0+3/app-debug.apk"
          }
        ]
      }
    """.trimIndent()

    val release = OtaUpdater.parseReleaseJson(json)
    assertEquals(3L, release?.versionCode)
    assertEquals("1.2.0", release?.versionName)
    assertEquals(
      "https://github.com/Jes-win-hac-ker/E.D.I.T.H/releases/download/v1.2.0+3/app-debug.apk",
      release?.apkUrl,
    )
    assertEquals(
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      release?.sha256,
    )
  }

  @Test
  fun parseReleaseJson_returnsNullWhenNoApkAsset() {
    val json = """{"tag_name": "v2", "body": "versionCode: 2", "assets": []}"""
    assertNull(OtaUpdater.parseReleaseJson(json))
  }

  @Test
  fun shouldDownload_skipsWhenInstalledMatchesOrExceedsRemote() {
    val release = OtaRelease(versionCode = 2L, versionName = "1.1.0", apkUrl = "https://example.com/a.apk", sha256 = "abc")
    assertFalse(OtaUpdater.shouldDownload(2L, release, cachedCode = -1L, cachedSha = null, cachedFileExists = false))
    assertFalse(OtaUpdater.shouldDownload(3L, release, cachedCode = -1L, cachedSha = null, cachedFileExists = false))
  }

  @Test
  fun shouldDownload_skipsWhenValidApkAlreadyCached() {
    val release = OtaRelease(versionCode = 3L, versionName = "1.2.0", apkUrl = "https://example.com/a.apk", sha256 = "deadbeef")
    assertFalse(OtaUpdater.shouldDownload(2L, release, cachedCode = 3L, cachedSha = "DEADBEEF", cachedFileExists = true))
  }

  @Test
  fun shouldDownload_downloadsWhenCacheMissingOrShaMismatch() {
    val release = OtaRelease(versionCode = 3L, versionName = "1.2.0", apkUrl = "https://example.com/a.apk", sha256 = "deadbeef")
    assertTrue(OtaUpdater.shouldDownload(2L, release, cachedCode = 3L, cachedSha = "deadbeef", cachedFileExists = false))
    assertTrue(OtaUpdater.shouldDownload(2L, release, cachedCode = 3L, cachedSha = "0000", cachedFileExists = true))
  }
}
