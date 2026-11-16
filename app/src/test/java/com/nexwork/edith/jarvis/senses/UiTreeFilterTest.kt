package com.nexwork.edith.jarvis.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeFilterTest {

  @Test
  fun filterAndAssignNodes_keepsInteractiveOrReadableNodesAndFormatsCompactTree() {
    val raw = listOf(
      JarvisAccessibilityService.RawNodeSnapshot(
        className = "FrameLayout",
        viewId = "decor",
        text = "",
        contentDesc = "",
        clickable = false,
        editable = false,
        scrollable = false,
        left = 0,
        top = 0,
        right = 720,
        bottom = 1600,
      ),
      JarvisAccessibilityService.RawNodeSnapshot(
        className = "EditText",
        viewId = "search_edit_text",
        text = "",
        contentDesc = "Search YouTube",
        clickable = true,
        editable = true,
        scrollable = false,
        left = 40,
        top = 100,
        right = 680,
        bottom = 180,
      ),
      JarvisAccessibilityService.RawNodeSnapshot(
        className = "Button",
        viewId = "submit_btn",
        text = "Go",
        contentDesc = "",
        clickable = true,
        editable = false,
        scrollable = false,
        left = 600,
        top = 100,
        right = 700,
        bottom = 180,
      ),
    )

    val nodes = JarvisAccessibilityService.filterAndAssignNodes(raw)
    assertEquals(2, nodes.size)
    assertEquals(1, nodes[0].id)
    assertEquals("search_edit_text", nodes[0].viewId)
    assertEquals(360, nodes[0].centerX)
    assertEquals(2, nodes[1].id)
    assertEquals("Go", nodes[1].text)

    val formatted = JarvisAccessibilityService.formatCompactTree("com.google.android.youtube", "Home", nodes)
    assertTrue(formatted.contains("pkg=com.google.android.youtube"))
    assertTrue(formatted.contains("[1] EditText id=search_edit_text desc=\"Search YouTube\" clickable,editable"))
    assertTrue(formatted.contains("[2] Button id=submit_btn text=\"Go\" clickable"))
  }
}
