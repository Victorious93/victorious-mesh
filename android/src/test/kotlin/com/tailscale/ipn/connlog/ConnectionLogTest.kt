// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.connlog

import com.tailscale.ipn.fleet.FleetRedaction
import com.tailscale.ipn.fleet.MeshBackup
import com.tailscale.ipn.fleet.MeshNonSecretPrefs
import com.tailscale.ipn.fleet.MeshSettingsPayload
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogTest {
  private val json = Json

  @Test
  fun neverKeepsAddresses() {
    val t = ConnectionLogPlanner.clean("Exit node 100.64.0.7 via fd7a:115c:a1e0::7 and 192.168.1.4 / 2001:db8::1")
    assertFalse(FleetRedaction.containsMeshAddress(t))
    assertFalse(t.contains("192.168"))
    assertFalse(t.contains("2001:db8"))
    assertTrue(t.contains("[mesh-ip]"))
  }

  @Test
  fun textIsOneShortLine() {
    val t = ConnectionLogPlanner.clean("a\nb" + "x".repeat(500))
    assertFalse(t.contains('\n'))
    assertEquals(ConnectionLogPlanner.MAX_TEXT, t.length)
  }

  @Test
  fun capsByCountAndBytes() {
    var l = emptyList<LogEntry>()
    repeat(ConnectionLogPlanner.MAX_ENTRIES + 50) { i -> l = ConnectionLogPlanner.append(l, LogEntry(i.toLong() * 10_000, LogKind.CONNECT, "c$i"), json) }
    assertEquals(ConnectionLogPlanner.MAX_ENTRIES, l.size)
    assertEquals("c${ConnectionLogPlanner.MAX_ENTRIES + 49}", l.last().text)

    var big = emptyList<LogEntry>()
    repeat(ConnectionLogPlanner.MAX_ENTRIES) { i -> big = ConnectionLogPlanner.append(big, LogEntry(i.toLong(), LogKind.PROXY, "p$i " + "y".repeat(150)), json) }
    val bytes = big.sumOf { json.encodeToString(LogEntry.serializer(), it).length + 1 }
    assertTrue(bytes <= ConnectionLogPlanner.MAX_BYTES)
    assertTrue(big.last().text.startsWith("p${ConnectionLogPlanner.MAX_ENTRIES - 1} "))
  }

  @Test
  fun repeatsAreFolded() {
    val a = LogEntry(1_000, LogKind.DISCONNECT, "Connection dropped")
    assertTrue(ConnectionLogPlanner.isRepeat(a, a.copy(atMs = 2_000)))
    assertFalse(ConnectionLogPlanner.isRepeat(a, a.copy(atMs = 10_000)))
    assertFalse(ConnectionLogPlanner.isRepeat(a, a.copy(kind = LogKind.CONNECT)))
  }

  @Test
  fun fileRoundTripAndClear() {
    val dir = Files.createTempDirectory("connlog").toFile()
    val f = File(dir, "log.jsonl")
    val log = ConnectionLogFile(f)
    log.add(LogEntry(1, LogKind.CONNECT, "Connected to the mesh"))
    log.add(LogEntry(100_000, LogKind.KILL_SWITCH, "Kill switch armed"))
    assertEquals(2, ConnectionLogFile(f).entries().size)
    assertFalse(f.readText().contains("100.6"))
    log.clear()
    assertTrue(ConnectionLogFile(f).entries().isEmpty())
    dir.deleteRecursively()
  }

  @Test
  fun backupPayloadHasNoLog() {
    val text =
        MeshBackup.json.encodeToString(
            MeshSettingsPayload.serializer(), MeshSettingsPayload(controlUrl = "https://hs.example", prefs = MeshNonSecretPrefs()))
    assertFalse(text.contains("connlog"))
    assertFalse(text.contains("log"))
    FleetRedaction.BACKUP_FORBIDDEN_KEYS.forEach { assertFalse(text.contains("\"$it\"")) }
  }
}
