// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.shell.RemoteShellPlanner
import com.tailscale.ipn.shell.ShellClient
import com.tailscale.ipn.shell.ShellTarget
import com.tailscale.ipn.shell.ShellTargetStore
import com.tailscale.ipn.shell.TERMUX_PACKAGE
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ShellNode(val id: String, val name: String, val ip: String, val online: Boolean)

class RemoteShellViewModel(app: Application) : AndroidViewModel(app) {
  private val store = ShellTargetStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))

  val targets: StateFlow<List<ShellTarget>> = MutableStateFlow(store.list())
  val message: StateFlow<String?> = MutableStateFlow(null)

  fun nodes(): List<ShellNode> =
      Notifier.netmap.value?.Peers.orEmpty().mapNotNull { p ->
        RemoteShellPlanner.meshIPv4(p.Addresses)?.let { ShellNode(p.StableID, p.ComputedName ?: p.Name, it, p.Online != false) }
      }

  private fun nodeIp(id: String): String? = nodes().firstOrNull { it.id == id }?.ip

  fun hostFor(t: ShellTarget): String? = RemoteShellPlanner.resolveHost(t, ::nodeIp)

  fun problem(t: ShellTarget): String? = RemoteShellPlanner.problem(t, hostFor(t))

  fun commandFor(t: ShellTarget): String? {
    val host = hostFor(t) ?: return null
    if (problem(t) != null) return null
    return RemoteShellPlanner.command(t.user, host, t.port)
  }

  fun save(t: ShellTarget): Boolean {
    val p = problem(t)
    if (p != null) {
      message.set("Not saved: $p")
      return false
    }
    store.save(t)
    targets.set(store.list())
    message.set("Saved ${t.name.trim()}")
    return true
  }

  fun delete(id: String) {
    store.delete(id)
    targets.set(store.list())
  }

  private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("ssh command", text))
  }

  /** Opens the session in the chosen client; the result is shown in [message]. */
  fun open(ctx: Context, t: ShellTarget) {
    val host = hostFor(t)
    val p = RemoteShellPlanner.problem(t, host)
    if (p != null || host == null) {
      message.set("Cannot open: ${p ?: "no address"}")
      return
    }
    val cmd = RemoteShellPlanner.command(t.user, host, t.port)
    when (t.client) {
      ShellClient.COMMAND -> {
        copy(ctx, cmd)
        message.set("Copied: $cmd")
      }
      ShellClient.TERMUX -> {
        copy(ctx, cmd)
        val launch = ctx.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (launch == null) {
          message.set("Termux is not installed. Copied: $cmd")
        } else {
          runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
          message.set("Copied the command; paste it in Termux: $cmd")
        }
      }
      ShellClient.SSH_APP -> {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(RemoteShellPlanner.sshUri(t.user, host, t.port)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { ctx.startActivity(intent) }.isSuccess
        if (!ok) {
          copy(ctx, cmd)
          message.set("No SSH app opens ssh:// links. Copied: $cmd")
        } else {
          message.set(null)
        }
      }
    }
  }
}
