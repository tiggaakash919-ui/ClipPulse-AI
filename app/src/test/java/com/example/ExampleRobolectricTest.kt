package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val stringsFile = listOf(
      File("src/main/res/values/strings.xml"),
      File("app/src/main/res/values/strings.xml")
    ).firstOrNull { it.exists() }

    assertTrue("strings.xml should exist in module resources", stringsFile != null)

    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
    val stringNodes = doc.getElementsByTagName("string")
    var appName: String? = null
    for (i in 0 until stringNodes.length) {
      val node = stringNodes.item(i)
      val nameAttr = node.attributes?.getNamedItem("name")?.nodeValue
      if (nameAttr == "app_name") {
        appName = node.textContent
        break
      }
    }

    assertEquals("ClipPulse AI", appName)
  }
}
