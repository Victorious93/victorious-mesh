// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tailscale.ipn.App
import com.tailscale.ipn.proxy.ProxyPlanner
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import com.tailscale.ipn.wireguard.WireGuardManager
import java.io.File
import libtailscale.Libtailscale

data class SharedFile(val name: String, val size: Long)

/** Read-only file share on this phone's mesh IP. Off by default. */
object MeshShareController {
  private const val TAG = "MeshShare"
  @Volatile private var lastKey: String? = null

  fun store(): MeshShareStore = MeshShareStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))

  /** App-private folder; only files the user adds here are shared. */
  fun folder(ctx: Context): File = File(ctx.filesDir, "mesh-share").apply { mkdirs() }

  fun files(ctx: Context): List<SharedFile> =
      folder(ctx).listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }
          .sortedBy { it.name }
          .map { SharedFile(it.name, it.length()) }

  fun meshIp(): String? {
    val addrs = Notifier.netmap.value?.SelfNode?.Addresses.orEmpty()
    val ip = ProxyPlanner.firstMeshIPv4(addrs) ?: return null
    return ip.takeIf { ProxyPlanner.allowedMeshBind(it, addrs) }
  }

  fun sync(ctx: Context) {
    val cfg = store().config()
    if (!cfg.enabled || WireGuardManager.isWireGuardActive()) {
      stop()
      return
    }
    val ip = meshIp()
    if (ip == null || !MeshSharePlanner.validPort(cfg.port)) {
      TSLog.w(TAG, "no mesh IP or bad port; share stays off")
      stop()
      return
    }
    val dir = folder(ctx).absolutePath
    val key = "$ip|${cfg.port}|${cfg.password.hashCode()}|$dir"
    val running =
        try {
          Libtailscale.meshShareRunning() && Libtailscale.meshShareAddr() == "$ip:${cfg.port}"
        } catch (_: Exception) {
          false
        }
    if (running && key == lastKey) return
    try {
      Libtailscale.startMeshShare(ip, cfg.port, dir, cfg.password)
      lastKey = key
    } catch (e: Exception) {
      TSLog.e(TAG, "start failed: ${e.message}")
      stop()
    }
  }

  fun stop() {
    lastKey = null
    try {
      Libtailscale.stopMeshShare()
    } catch (e: Exception) {
      TSLog.w(TAG, "stop: ${e.javaClass.simpleName}")
    }
  }

  fun running(): Boolean =
      try {
        Libtailscale.meshShareRunning()
      } catch (_: Exception) {
        false
      }

  /** Copies picked documents into the share folder. Returns a short status. */
  fun addFiles(ctx: Context, uris: List<Uri>): String {
    val dir = folder(ctx)
    var used = files(ctx).sumOf { it.size }
    var added = 0
    var skipped = 0
    for (uri in uris) {
      try {
        var display: String? = null
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
          if (c.moveToFirst()) {
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0) display = c.getString(ni)
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
          }
        }
        val safe = MeshSharePlanner.safeFileName(display) ?: "file"
        if (size >= 0 && !MeshSharePlanner.fitsCap(used, size)) {
          skipped++
          continue
        }
        val name = MeshSharePlanner.uniqueName(safe, dir.list().orEmpty().toSet())
        val tmp = File(dir, ".part-$name")
        var copied = 0L
        ctx.contentResolver.openInputStream(uri)?.use { input ->
          tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
              val n = input.read(buf)
              if (n < 0) break
              copied += n
              if (!MeshSharePlanner.fitsCap(used, copied)) throw IllegalStateException("share folder is full")
              out.write(buf, 0, n)
            }
          }
        } ?: throw IllegalStateException("cannot open")
        if (!tmp.renameTo(File(dir, name))) throw IllegalStateException("rename failed")
        used += copied
        added++
      } catch (e: Exception) {
        TSLog.w(TAG, "add failed: ${e.javaClass.simpleName}")
        dir.listFiles().orEmpty().filter { it.name.startsWith(".part-") }.forEach { it.delete() }
        skipped++
      }
    }
    return if (skipped == 0) "Added $added file(s)" else "Added $added file(s), skipped $skipped (too big or unreadable)"
  }

  fun remove(ctx: Context, name: String) {
    val safe = MeshSharePlanner.safeFileName(name) ?: return
    if (safe != name) return
    File(folder(ctx), name).delete()
  }
}
