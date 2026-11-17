package com.nexwork.edith.jarvis.senses

import com.nexwork.edith.data.ShizukuControlService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeFilterTest {

  @Test
  fun filterAndAssignNodes_keepsInteractiveOrReadableNodesAndFormatsCompactJson() {
    val raw = listOf(
      ShizukuControlService.RawNodeSnapshot(
        packageName = "com.google.android.youtube",
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
      ShizukuControlService.RawNodeSnapshot(
        packageName = "com.google.android.youtube",
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
      ShizukuControlService.RawNodeSnapshot(
        packageName = "com.google.android.youtube",
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

    val nodes = ShizukuControlService.filterAndAssignNodes(raw)
    assertEquals(2, nodes.size)
    assertEquals(1, nodes[0].id)
    assertEquals("search_edit_text", nodes[0].viewId)
    assertEquals(360, nodes[0].centerX)
    assertEquals(140, nodes[0].centerY)
    assertEquals(2, nodes[1].id)
    assertEquals("Go", nodes[1].text)

    val formatted = ShizukuControlService.formatCompactTree("com.google.android.youtube", "Home", nodes)
    assertTrue(formatted.contains("\"package\":\"com.google.android.youtube\""))
    assertTrue(formatted.contains("\"res_id\":\"search_edit_text\""))
    assertTrue(formatted.contains("\"center\":[360,140]"))
  }

  @Test
  fun parseUiHierarchyXml_parsesXmlFiltersBlockedPackagesAndCapsAt50() {
    val youtubeXml = """
      <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
      <hierarchy rotation="0">
        <node index="0" text="" resource-id="com.google.android.youtube:id/search_box" class="android.widget.EditText" package="com.google.android.youtube" content-desc="Search YouTube" clickable="true" scrollable="false" visible-to-user="true" bounds="[20,80][700,160]" />
        <node index="1" text="Jazz Classics" resource-id="com.google.android.youtube:id/title" class="android.widget.TextView" package="com.google.android.youtube" content-desc="" clickable="true" scrollable="false" visible-to-user="true" bounds="[20,200][700,320]" />
      </hierarchy>
    """.trimIndent()
    val tree = ShizukuControlService.parseUiHierarchyXml(youtubeXml)
    assertNull(tree.blockedReason)
    assertEquals("com.google.android.youtube", tree.packageName)
    assertEquals(2, tree.nodes.size)
    assertEquals(360, tree.nodes[0].centerX)
    assertEquals(120, tree.nodes[0].centerY)

    val gpayXml = """
      <hierarchy rotation="0">
        <node index="0" text="Pay" resource-id="" class="android.widget.Button" package="com.google.android.apps.nbu.paisa.user" content-desc="" clickable="true" scrollable="false" bounds="[0,0][720,1600]" />
      </hierarchy>
    """.trimIndent()
    val blockedTree = ShizukuControlService.parseUiHierarchyXml(gpayXml)
    assertNotNull(blockedTree.blockedReason)
    assertTrue(blockedTree.nodes.isEmpty())

    assertEquals("play%sjazz%s\\&%sblues", ShizukuControlService.escapeShellInputText("play jazz & blues"))
  }
}
