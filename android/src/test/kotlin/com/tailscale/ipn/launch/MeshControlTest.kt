// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.launch

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MeshControlTest {
  private val ns = "http://schemas.android.com/apk/res/android"

  private fun srcFile(rel: String): File =
      listOf(File("src/main/$rel"), File("android/src/main/$rel")).firstOrNull { it.exists() }
          ?: error("cannot find src/main/$rel from ${File(".").absolutePath}")

  private fun parse(rel: String) =
      DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(srcFile(rel))

  private fun Element.attr(name: String): String = getAttributeNS(ns, name)

  private fun elements(root: org.w3c.dom.Document, tag: String): List<Element> {
    val nl = root.getElementsByTagName(tag)
    return (0 until nl.length).map { nl.item(it) as Element }
  }

  @Test
  fun parseOnlyKnownActions() {
    assertEquals(MeshControlAction.CONNECT, MeshControlPlanner.parse("com.victorious.mesh.action.MESH_CONNECT"))
    assertEquals(MeshControlAction.DISCONNECT, MeshControlPlanner.parse("com.victorious.mesh.action.MESH_DISCONNECT"))
    assertNull(MeshControlPlanner.parse("com.tailscale.ipn.DISCONNECT_VPN"))
    assertNull(MeshControlPlanner.parse(null))
  }

  @Test
  fun nothingHappensWithoutConfirm() {
    for (a in MeshControlAction.entries) for (c in listOf(true, false)) for (p in listOf(true, false)) {
      assertEquals(MeshControlStep.NOTHING, MeshControlPlanner.afterConfirm(a, confirmed = false, connected = c, ableToStart = true, vpnPrepared = p))
    }
  }

  @Test
  fun connectFlow() {
    val a = MeshControlAction.CONNECT
    assertEquals(MeshControlStep.START_VPN, MeshControlPlanner.afterConfirm(a, true, connected = false, ableToStart = true, vpnPrepared = true))
    assertEquals(MeshControlStep.REQUEST_VPN_PERMISSION, MeshControlPlanner.afterConfirm(a, true, false, true, vpnPrepared = false))
    assertEquals(MeshControlStep.OPEN_APP_TO_SIGN_IN, MeshControlPlanner.afterConfirm(a, true, false, ableToStart = false, vpnPrepared = true))
    assertEquals(MeshControlStep.NOTHING, MeshControlPlanner.afterConfirm(a, true, connected = true, ableToStart = true, vpnPrepared = true))
    assertEquals(MeshControlStep.START_VPN, MeshControlPlanner.afterVpnPermission(true))
    assertEquals(MeshControlStep.NOTHING, MeshControlPlanner.afterVpnPermission(false))
  }

  @Test
  fun disconnectOnlyWhenConfirmedAndConnected() {
    val a = MeshControlAction.DISCONNECT
    assertEquals(MeshControlStep.STOP_VPN, MeshControlPlanner.afterConfirm(a, true, connected = true, ableToStart = true, vpnPrepared = true))
    assertEquals(MeshControlStep.NOTHING, MeshControlPlanner.afterConfirm(a, true, connected = false, ableToStart = true, vpnPrepared = true))
    assertTrue(MeshControlPlanner.warnKillSwitch(a, killSwitchEnabled = true, armed = true))
    assertFalse(MeshControlPlanner.warnKillSwitch(a, killSwitchEnabled = true, armed = false))
    assertFalse(MeshControlPlanner.warnKillSwitch(MeshControlAction.CONNECT, true, true))
  }

  @Test
  fun confirmButtonDelay() {
    assertFalse(MeshControlPlanner.canConfirm(0))
    assertFalse(MeshControlPlanner.canConfirm(MeshControlPlanner.CONFIRM_DELAY_MS - 1))
    assertTrue(MeshControlPlanner.canConfirm(MeshControlPlanner.CONFIRM_DELAY_MS))
  }

  @Test
  fun shortcutsMatchContract() {
    val doc = parse("res/xml/shortcuts.xml")
    val byId = elements(doc, "shortcut").associateBy { it.attr("shortcutId") }
    assertEquals(setOf("mesh_connect", "mesh_disconnect"), byId.keys)
    val want =
        mapOf(
            "mesh_connect" to MeshControlPlanner.ACTION_CONNECT,
            "mesh_disconnect" to MeshControlPlanner.ACTION_DISCONNECT,
        )
    for ((id, action) in want) {
      val intent = byId[id]!!.getElementsByTagName("intent").item(0) as Element
      assertEquals(action, intent.attr("action"))
      assertEquals("com.victorious.mesh", intent.attr("targetPackage"))
      assertEquals(MeshControlActivity::class.java.name, intent.attr("targetClass"))
    }
  }

  @Test
  fun manifestExportsActivityWithoutPermissionAndKeepsReceiverProtected() {
    val doc = parse("AndroidManifest.xml")
    val activity = elements(doc, "activity").firstOrNull { it.attr("name") == MeshControlActivity::class.java.name }
    assertNotNull("MeshControlActivity missing from manifest", activity)
    assertEquals("true", activity!!.attr("exported"))
    assertEquals("", activity.attr("permission"))
    val actions = (0 until activity.getElementsByTagName("action").length).map {
      (activity.getElementsByTagName("action").item(it) as Element).attr("name")
    }
    assertTrue(actions.containsAll(listOf(MeshControlPlanner.ACTION_CONNECT, MeshControlPlanner.ACTION_DISCONNECT)))

    val receiver = elements(doc, "receiver").first { it.attr("name") == "com.tailscale.ipn.IPNReceiver" }
    assertEquals("com.victorious.mesh.permission.CONTROL_VPN", receiver.attr("permission"))

    val main = elements(doc, "activity").first { it.attr("name") == "com.tailscale.ipn.MainActivity" }
    val meta = (0 until main.getElementsByTagName("meta-data").length).map { main.getElementsByTagName("meta-data").item(it) as Element }
    assertTrue(meta.any { it.attr("name") == "android.app.shortcuts" && it.attr("resource") == "@xml/shortcuts" })
  }
}
