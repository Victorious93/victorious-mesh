// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.connlog

import com.tailscale.ipn.fleet.FleetRedaction
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class LogKind {
  INFO,
  CONNECT,
  DISCONNECT,
  EXIT_NODE,
  PROXY,
  KILL_SWITCH,
}

@Serializable data class LogEntry(val atMs: Long, val kind: LogKind, val text: String)

object ConnectionLogPlanner {
  const val MAX_ENTRIES = 500
  const val MAX_BYTES = 64 * 1024
  const val MAX_TEXT = 160

  private val ANY_V4 = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")
  private val ANY_V6 = Regex("""(?i)\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f:]*""")

  /**
   * Entries never carry addresses. Mesh IPs are redacted the same way fleet
   * backups are ([FleetRedaction]); any other IP literal is redacted too.
   */
  fun clean(text: String): String {
    val noMesh = FleetRedaction.redact(text)
    val noIp = ANY_V6.replace(ANY_V4.replace(noMesh, "[ip]"), "[ip]")
    return noIp.replace('\n', ' ').trim().take(MAX_TEXT)
  }

  /** Keep the newest entries within both caps. */
  fun capped(entries: List<LogEntry>, json: Json): List<LogEntry> {
    var out = entries.takeLast(MAX_ENTRIES)
    var size = out.sumOf { json.encodeToString(LogEntry.serializer(), it).length + 1 }
    var drop = 0
    while (size > MAX_BYTES && drop < out.size) {
      size -= json.encodeToString(LogEntry.serializer(), out[drop]).length + 1
      drop++
    }
    if (drop > 0) out = out.drop(drop)
    return out
  }

  fun append(entries: List<LogEntry>, e: LogEntry, json: Json): List<LogEntry> =
      capped(entries + e.copy(text = clean(e.text)), json)

  /** Drop a repeat of the same event within a few seconds (flapping callbacks). */
  fun isRepeat(last: LogEntry?, e: LogEntry, windowMs: Long = 3_000): Boolean =
      last != null && last.kind == e.kind && last.text == clean(e.text) && e.atMs - last.atMs in 0..windowMs
}

/** A size-capped JSON-lines file in no-backup storage. Thread-safe. */
class ConnectionLogFile(private val file: File) {
  private val json = Json { ignoreUnknownKeys = true }
  private var cache: MutableList<LogEntry>? = null

  @Synchronized
  fun entries(): List<LogEntry> = load().toList()

  @Synchronized
  fun add(e: LogEntry) {
    val cur = load()
    if (ConnectionLogPlanner.isRepeat(cur.lastOrNull(), e)) return
    val next = ConnectionLogPlanner.append(cur, e, json)
    cache = next.toMutableList()
    write(next)
  }

  @Synchronized
  fun clear() {
    cache = mutableListOf()
    file.delete()
  }

  private fun load(): MutableList<LogEntry> {
    cache?.let { return it }
    val list =
        if (!file.exists()) mutableListOf()
        else
            file.readLines()
                .mapNotNull { line -> runCatching { json.decodeFromString(LogEntry.serializer(), line) }.getOrNull() }
                .toMutableList()
    cache = list
    return list
  }

  private fun write(list: List<LogEntry>) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(list.joinToString("") { json.encodeToString(LogEntry.serializer(), it) + "\n" })
    if (!tmp.renameTo(file)) {
      file.writeText(tmp.readText())
      tmp.delete()
    }
  }
}
