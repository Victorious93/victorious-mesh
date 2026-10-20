package com.tailscale.ipn.ui

import com.tailscale.ipn.ui.util.ManualAsset
import com.tailscale.ipn.ui.util.ManualMarkdown
import com.tailscale.ipn.ui.util.MdBlock
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAssetTest {
  private val requiredHeadings =
      listOf(
          "What it is and its fleet role",
          "Install and first run",
          "Features",
          "Permissions",
          "Fleet integration",
          "Troubleshooting and FAQ",
          "Privacy and data handling",
      )

  @Test
  fun bundledManualExistsAndContainsAllSectionHeadings() {
    val stream = javaClass.classLoader!!.getResourceAsStream(ManualAsset.PATH)
    assertNotNull("bundled asset ${ManualAsset.PATH} is missing from the test classpath", stream)
    val text = stream!!.bufferedReader().use { it.readText() }
    requiredHeadings.forEach { heading ->
      val marker = "## $heading"
      assertTrue("manual is missing heading: $marker", text.contains(marker))
    }
  }

  @Test
  fun parserSeesAllSevenHeadings() {
    val stream = javaClass.classLoader!!.getResourceAsStream(ManualAsset.PATH)
    assertNotNull(stream)
    val blocks = ManualMarkdown.parse(stream!!.bufferedReader().use { it.readText() })
    val headings =
        blocks.filterIsInstance<MdBlock.Heading>().filter { it.level == 2 }.map { it.text }
    requiredHeadings.forEach { heading ->
      assertTrue("parser missed H2 '$heading' (got $headings)", headings.contains(heading))
    }
  }

  @Test
  fun forcedProxyDocumentsDnsAndIpv6() {
    val stream = javaClass.classLoader!!.getResourceAsStream(ManualAsset.PATH)
    assertNotNull(stream)
    val text = stream!!.bufferedReader().use { it.readText() }
    assertTrue(
        "manual should document DNS through the proxy",
        text.contains("public DNS (port 53) is sent through the proxy"),
    )
    assertTrue(
        "manual should document IPv6 via proxy or block",
        text.contains("IPv6 is sent through the proxy when the upstream supports it"),
    )
    assertTrue(
        "manual should say IPv6 is never leaked",
        text.contains("never leaked"),
    )
    assertTrue(
        "old DNS gap wording must be gone",
        !text.contains("System DNS may still use the mesh or platform resolver"),
    )
  }

  @Test
  fun parserHandlesCommonManualMarkup() {
    val blocks =
        ManualMarkdown.parse(
            """
            # Title
            ## Features
            A **bold** and `code` word.
            - item
            1. first
            ![Main](images/manual/main.png)
            | A | B |
            |---|---|
            | 1 | 2 |
            """.trimIndent()
        )
    assertTrue(blocks.any { it is MdBlock.Heading && it.level == 1 && it.text == "Title" })
    assertTrue(blocks.any { it is MdBlock.Heading && it.text == "Features" })
    assertTrue(blocks.any { it is MdBlock.BulletList })
    assertTrue(blocks.any { it is MdBlock.OrderedList })
    assertTrue(blocks.any { it is MdBlock.Image && it.path.contains("main.png") })
    assertTrue(blocks.any { it is MdBlock.Table && it.headers == listOf("A", "B") })
  }
}
