// Victorious Mesh
// Exported bound Messenger service for the fleet IPC bridge (see docs/FLEET_BRIDGE.md).

package com.tailscale.ipn.fleet

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.tailscale.ipn.App
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wireguard.WireGuardManager

/** Persistent fleet settings: node id + external-control toggle (default OFF). */
object FleetSettings {
  private const val PREFS = "fleet_bridge"
  private const val KEY_NODE_ID = "node_id"
  private const val KEY_ENABLED = "external_control_enabled"

  private fun prefs(ctx: Context): SharedPreferences =
      ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @Synchronized
  fun nodeId(ctx: Context): String {
    val p = prefs(ctx)
    p.getString(KEY_NODE_ID, null)?.let { if (FleetProtocol.isValidNodeId(it)) return it }
    val id = FleetProtocol.newNodeId()
    p.edit().putString(KEY_NODE_ID, id).commit()
    return id
  }

  fun isExternalControlEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)

  fun setExternalControlEnabled(ctx: Context, enabled: Boolean) {
    prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
  }
}

/** MeshBackend backed by the in-process Notifier state flows and App start/stop paths. */
class AppMeshBackend(private val ctx: Context) : MeshBackend {
  override fun wireGuardModeActive(): Boolean = WireGuardManager.isWireGuardActive()

  override fun status(): MeshStatus {
    val state = Notifier.state.value
    val (v4, v6) = FleetDispatcher.splitAddresses(Notifier.netmap.value?.SelfNode?.Addresses)
    return MeshStatus(
        backendState = state.name,
        connected = state == Ipn.State.Running,
        controlUrl = Notifier.prefs.value?.ControlURL?.takeIf { it.isNotEmpty() },
        ipv4 = v4,
        ipv6 = v6,
    )
  }

  // Same gate StartVPNWorker (used by IPNReceiver) applies.
  override fun vpnConsentGranted(): Boolean =
      UninitializedApp.get().isAbleToStartVPN() && VpnService.prepare(ctx) == null

  override fun up() = UninitializedApp.get().startVPN()

  override fun down() = UninitializedApp.get().stopVPN()
}

class FleetBridgeService : Service() {
  private lateinit var messenger: Messenger

  override fun onCreate() {
    super.onCreate()
    App.get() // ensure IPN/Notifier are initialised in-process
    messenger = Messenger(IncomingHandler(Looper.getMainLooper()))
  }

  override fun onBind(intent: Intent?): IBinder = messenger.binder

  private inner class IncomingHandler(looper: Looper) : Handler(looper) {
    override fun handleMessage(msg: Message) {
      if (msg.what != FleetProtocol.MSG_REQUEST) return
      // Messenger delivers on our handler, so Binder.getCallingUid() is not the caller here;
      // Message.sendingUid (API 22+) is set by the system to the sender's uid.
      val uid = msg.sendingUid
      val reply = msg.replyTo ?: return
      val raw = msg.data?.getString(FleetProtocol.KEY_REQUEST)
      val resp =
          try {
            FleetDispatcher(FleetSettings.nodeId(this@FleetBridgeService), AppMeshBackend(this@FleetBridgeService))
                .handle(
                    raw,
                    callerAllowed = isCallerAllowed(uid),
                    controlEnabled = FleetSettings.isExternalControlEnabled(this@FleetBridgeService),
                )
          } catch (e: Exception) {
            BridgeResponse(ok = false, error = BridgeError(ErrorCategory.INTERNAL, "internal error"))
          }
      val out = Message.obtain(null, FleetProtocol.MSG_RESPONSE)
      out.data =
          Bundle().apply {
            putString(
                FleetProtocol.KEY_RESPONSE,
                FleetProtocol.json.encodeToString(BridgeResponse.serializer(), resp),
            )
          }
      try {
        reply.send(out)
      } catch (e: Exception) {
        TSLog.w(TAG, "fleet bridge reply failed: ${e.javaClass.simpleName}")
      }
    }
  }

  private fun isCallerAllowed(uid: Int): Boolean {
    if (uid < 0) return false
    val pm = packageManager
    val pkgs = pm.getPackagesForUid(uid) ?: return false
    val candidates = pkgs.associateWith { signingCertSha256s(pm, it) }
    return CallerAllowlist.isAllowed(candidates)
  }

  @Suppress("DEPRECATION")
  private fun signingCertSha256s(pm: PackageManager, pkg: String): List<String> =
      try {
        val sigs =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
              val si = info.signingInfo ?: return emptyList()
              // Current signer(s) only; rotated-away (possibly compromised) certs are not honoured.
              si.apkContentsSigners
            } else {
              // API 26-27
              pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
        sigs.orEmpty().map { CallerAllowlist.sha256Hex(it.toByteArray()) }
      } catch (e: Exception) {
        emptyList()
      }

  companion object {
    private const val TAG = "FleetBridgeService"
  }
}
