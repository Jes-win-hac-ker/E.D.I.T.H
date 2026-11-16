package com.nexwork.edith.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyVaultTest {

  @Test
  fun maskApiKey_masksSafelyWithoutExposingSecret() {
    assertEquals("", KeyVault.maskApiKey(""))
    assertEquals("", KeyVault.maskApiKey("   "))
    assertEquals("••••", KeyVault.maskApiKey("12345678"))
    assertEquals("AIza••••Wxyz", KeyVault.maskApiKey("  AIzaSySecret123456Wxyz  "))
  }
}
