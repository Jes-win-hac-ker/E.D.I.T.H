package com.nexwork.edith.jarvis.senses

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisAccessibilityServiceTest {

  @Test
  fun shouldConsumePhysicalAbortKey_onlyConsumesVolumeDownWhileAutomationActive() {
    assertTrue(
      JarvisAccessibilityService.shouldConsumePhysicalAbortKey(
        keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
        action = KeyEvent.ACTION_DOWN,
        repeatCount = 0,
        isAutomationActive = true,
      ),
    )

    // Normal media volume must stay completely untouched when Jarvis is idle.
    assertFalse(
      JarvisAccessibilityService.shouldConsumePhysicalAbortKey(
        keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
        action = KeyEvent.ACTION_DOWN,
        repeatCount = 0,
        isAutomationActive = false,
      ),
    )

    // Repeated long-hold key events and other hardware keys must not retrigger abort.
    assertFalse(
      JarvisAccessibilityService.shouldConsumePhysicalAbortKey(
        keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
        action = KeyEvent.ACTION_DOWN,
        repeatCount = 1,
        isAutomationActive = true,
      ),
    )
    assertFalse(
      JarvisAccessibilityService.shouldConsumePhysicalAbortKey(
        keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
        action = KeyEvent.ACTION_UP,
        repeatCount = 0,
        isAutomationActive = true,
      ),
    )
    assertFalse(
      JarvisAccessibilityService.shouldConsumePhysicalAbortKey(
        keyCode = KeyEvent.KEYCODE_VOLUME_UP,
        action = KeyEvent.ACTION_DOWN,
        repeatCount = 0,
        isAutomationActive = true,
      ),
    )
  }
}
