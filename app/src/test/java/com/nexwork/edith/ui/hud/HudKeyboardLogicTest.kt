package com.nexwork.edith.ui.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class HudKeyboardLogicTest {

  @Test
  fun appendAndBackspace_editQueryCleanly() {
    var q = ""
    q = HudKeyboardLogic.appendToken(q, "j")
    q = HudKeyboardLogic.appendToken(q, "a")
    assertEquals("ja", q)
    q = HudKeyboardLogic.backspace(q)
    assertEquals("j", q)
    q = HudKeyboardLogic.backspace(q)
    assertEquals("", q)
    assertEquals("", HudKeyboardLogic.backspace(""))
  }

  @Test
  fun applyCommandChip_replacesExistingPrefixOrPrependsToSearch() {
    assertEquals("!jarvis ", HudKeyboardLogic.applyCommandChip("", "!jarvis"))
    assertEquals("!monet ", HudKeyboardLogic.applyCommandChip("!jarvis test", "!monet"))
    assertEquals("? weather", HudKeyboardLogic.applyCommandChip("weather", "?"))
  }

  @Test
  fun actionButtonLabel_tracksCommandMathAndSingleMatch() {
    assertEquals("SEARCH", HudKeyboardLogic.actionButtonLabel("", 12))
    assertEquals("GO", HudKeyboardLogic.actionButtonLabel("cam", 1))
    assertEquals("EXEC", HudKeyboardLogic.actionButtonLabel("!jarvis", 0))
    assertEquals("EXEC", HudKeyboardLogic.actionButtonLabel("? test", 0))
    assertEquals("CALC", HudKeyboardLogic.actionButtonLabel("24*7", 0))
  }
}
