package com.nexwork.edith.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.nexwork.edith.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent { MainScreen(FAKE_DATA) }
  }

  @Test
  fun firstItem_exists() {
    FAKE_DATA.forEach { appName ->
      assertTrue(
        "Expected app '$appName' to be rendered",
        composeTestRule.onAllNodesWithText(appName).fetchSemanticsNodes().isNotEmpty(),
      )
    }
  }
}

private val FAKE_DATA = listOf("Sample1", "Sample2", "Sample3")
