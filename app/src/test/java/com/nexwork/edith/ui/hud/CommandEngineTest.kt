package com.nexwork.edith.ui.hud

import com.nexwork.edith.model.AppEntry
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class CommandEngineTest {

  @Test
  fun parseHudCommand_evaluatesArithmeticAndPrecedence() {
    assertEquals(HudCommand.MathResult("24*7", "168"), parseHudCommand("=24*7"))
    assertEquals(HudCommand.MathResult("1850/4", "462.5"), parseHudCommand("1850/4"))
    assertEquals(HudCommand.MathResult("(12+8)*3.5", "70"), parseHudCommand("(12+8)*3.5"))
    assertEquals(HudCommand.MathResult("2^10", "1024"), parseHudCommand("2^10"))
    assertEquals(HudCommand.MathResult("15%200", "15"), parseHudCommand("15%200"))
    assertEquals(HudCommand.MathResult("200%15", "5"), parseHudCommand("200%15"))

    // Operator precedence & associativity
    assertEquals(HudCommand.MathResult("2+3*4", "14"), parseHudCommand("2+3*4"))
    assertEquals(HudCommand.MathResult("2*3^2", "18"), parseHudCommand("2*3^2"))
    assertEquals(HudCommand.MathResult("2^3^2", "512"), parseHudCommand("2^3^2"))
    assertEquals(HudCommand.MathResult("-2^2", "-4"), parseHudCommand("-2^2"))
    assertEquals(HudCommand.MathResult("(-2)^2", "4"), parseHudCommand("(-2)^2"))
    assertEquals(HudCommand.MathResult("2^-2", "0.25"), parseHudCommand("2^-2"))

    // Unary +/-, decimals, and clean 6-digit rounding without trailing zeros
    assertEquals(HudCommand.MathResult("-5 + +12.5", "7.5"), parseHudCommand("-5 + +12.5"))
    assertEquals(HudCommand.MathResult(".5 * 4", "2"), parseHudCommand(".5 * 4"))
    assertEquals(HudCommand.MathResult("1/3", "0.333333"), parseHudCommand("1/3"))
    assertEquals(HudCommand.MathResult("0.1+0.2", "0.3"), parseHudCommand("0.1+0.2"))
    assertEquals(HudCommand.MathResult("-5+5", "0"), parseHudCommand("-5+5"))
  }

  @Test
  fun parseHudCommand_rejectsBareNumbersUnlessPrefixedAndHandlesInvalidMath() {
    assertNull(parseHudCommand("123"))
    assertNull(parseHudCommand("2026"))
    assertNull(parseHudCommand("-2026"))
    assertNull(parseHudCommand("(2026)"))

    assertEquals(HudCommand.MathResult("2026", "2026"), parseHudCommand("=2026"))
    assertEquals(HudCommand.MathResult("-42", "-42"), parseHudCommand("= -42"))

    // Division/modulo by zero, NaN, and malformed expressions
    assertNull(parseHudCommand("10/0"))
    assertNull(parseHudCommand("10%0"))
    assertNull(parseHudCommand("=10/0"))
    assertNull(parseHudCommand("(-2)^0.5"))
    assertNull(parseHudCommand("(2+3"))
    assertNull(parseHudCommand("2+"))
    assertNull(parseHudCommand("="))
    assertNull(parseHudCommand("=1.2.3"))
    assertNull(parseHudCommand("7-Eleven"))
  }

  @Test
  fun parseHudCommand_parsesAllBangAndWebCommands() {
    assertEquals(HudCommand.OtaCheck(null), parseHudCommand("!ota"))
    assertEquals(
      HudCommand.OtaCheck("https://example.com/feed.json"),
      parseHudCommand("!OTA https://example.com/feed.json"),
    )
    assertEquals(HudCommand.Unknown("!monet"), parseHudCommand("!monet"))
    assertEquals(HudCommand.Unknown("!theme"), parseHudCommand("!theme"))
    assertEquals(HudCommand.Unknown("!Monet Apply"), parseHudCommand("!Monet Apply"))
    assertEquals(HudCommand.Unknown("!monet reset"), parseHudCommand("!monet reset"))
    assertEquals(HudCommand.Unknown("!reset"), parseHudCommand("!reset"))
    assertEquals(HudCommand.GrantUsage, parseHudCommand("!usage"))
    assertEquals(HudCommand.GrantNotifications, parseHudCommand("!notif"))
    assertEquals(HudCommand.OpenHomeChooser, parseHudCommand("!home"))
    assertEquals(HudCommand.PinToggle("WhatsApp"), parseHudCommand("!pin WhatsApp"))
    assertEquals(HudCommand.PinToggle("Google Drive"), parseHudCommand("!unpin Google Drive"))
    assertEquals(HudCommand.ShowBattery, parseHudCommand("!battery"))
    assertEquals(HudCommand.ShowBattery, parseHudCommand("!bat"))
    assertEquals(HudCommand.ShowSystem, parseHudCommand("!ram"))
    assertEquals(HudCommand.ShowSystem, parseHudCommand("!sys"))
    assertEquals(HudCommand.ShowWeather, parseHudCommand("!weather"))
    assertEquals(HudCommand.ShowWeather, parseHudCommand("!wx"))
    assertEquals(HudCommand.ToggleWifi, parseHudCommand("!wifi"))
    assertEquals(HudCommand.ToggleBluetooth, parseHudCommand("!bt"))
    assertEquals(HudCommand.ToggleBluetooth, parseHudCommand("!bluetooth"))
    assertEquals(HudCommand.ToggleDnd, parseHudCommand("!dnd"))
    assertEquals(HudCommand.SetRefreshRate(null), parseHudCommand("!refresh"))
    assertEquals(HudCommand.SetRefreshRate(120), parseHudCommand("!refresh 120"))
    assertEquals(HudCommand.SetRefreshRate(60), parseHudCommand("!refresh 60"))
    assertEquals(HudCommand.SetRefreshRate(90), parseHudCommand("!refresh 90"))
    assertEquals(HudCommand.Unknown("!refresh fast"), parseHudCommand("!refresh fast"))
    assertEquals(HudCommand.OpenJarvis, parseHudCommand("!jarvis"))
    assertEquals(HudCommand.OpenJarvis, parseHudCommand("!agent"))
    assertEquals(HudCommand.ShowHelp, parseHudCommand("!help"))
    assertEquals(HudCommand.ShowHelp, parseHudCommand("!cmd"))
    assertEquals(HudCommand.ShowHelp, parseHudCommand("!"))
    assertEquals(HudCommand.WebSearch("moto g45 specs"), parseHudCommand("?moto g45 specs"))
    assertEquals(HudCommand.WebSearch("kotlin coroutines"), parseHudCommand("!g kotlin coroutines"))
    assertEquals(HudCommand.Unknown("!foo"), parseHudCommand("!foo"))

    assertNull(parseHudCommand(""))
    assertNull(parseHudCommand("   "))
    assertNull(parseHudCommand("WhatsApp"))
  }

  @Test
  fun filterMatchingApps_matchesWordAndCamelCaseAcronyms_withoutFalsePositives() {
    val googleMaps = AppEntry("Google Maps", "com.google.android.apps.maps", "")
    val googleDrive = AppEntry("Google Drive", "com.google.android.apps.docs", "")
    val whatsApp = AppEntry("WhatsApp", "com.whatsapp", "")
    val youtubeMusic = AppEntry("YouTube Music", "com.google.android.apps.youtube.music", "")
    val gmail = AppEntry("Gmail", "com.google.android.gm", "")
    val camera = AppEntry("Camera", "com.motorola.camera3", "", usageTimeMillis14d = 50_000L)
    val calculator = AppEntry("Calculator", "com.google.android.calculator", "", usageTimeMillis14d = 1_000L)
    val calendar = AppEntry("Calendar", "com.google.android.calendar", "", usageTimeMillis14d = 10_000L)
    val chrome = AppEntry("Chrome", "com.android.chrome", "")
    val google = AppEntry("Google", "com.google.android.googlequicksearchbox", "")
    val youtube = AppEntry("YouTube", "com.google.android.youtube", "")

    val allApps = listOf(
      googleMaps,
      googleDrive,
      whatsApp,
      youtubeMusic,
      gmail,
      camera,
      calculator,
      calendar,
      chrome,
      google,
      youtube,
    )

    // Acronym hits
    val gmMatches = filterMatchingApps("gm", allApps)
    assertEquals(listOf("Gmail", "Google Maps"), gmMatches.map { it.label })

    assertEquals(listOf("WhatsApp"), filterMatchingApps("wa", allApps).map { it.label })
    assertEquals(listOf("YouTube Music"), filterMatchingApps("ytm", allApps).map { it.label })
    assertEquals(listOf("Google Drive"), filterMatchingApps("gd", allApps).map { it.label })

    // Invariant: "cal" must NOT match "Camera"
    val calMatches = filterMatchingApps("cal", listOf(calculator, camera, calendar))
    assertEquals(2, calMatches.size)
    assertEquals(listOf("Calendar", "Calculator"), calMatches.map { it.label })

    // Invariant: "google" must return [Google, Google Drive], not YouTube or Chrome
    val googleMatches = filterMatchingApps("google", listOf(youtube, chrome, google, googleDrive))
    assertEquals(listOf("Google", "Google Drive"), googleMatches.map { it.label })

    // Space in query disables acronym matching
    assertTrue(filterMatchingApps("g m", allApps).isEmpty())

    // Command/math/web prefixes yield zero app hits
    assertTrue(filterMatchingApps("!ota", allApps).isEmpty())
    assertTrue(filterMatchingApps("=24*7", allApps).isEmpty())
    assertTrue(filterMatchingApps("?weather", allApps).isEmpty())
  }
}
