// Victorious Mesh
// Exported bound Messenger service for the fleet IPC bridge (see docs/FLEET_BRIDGE.md).

package com.tailscale.ipn.fleet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.MainActivity
import com.tailscale.ipn.R
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

/**
 * Process-wide replay cache and pending destructive confirmations. Authorization is never derived
 * from these — only from the caller package + cert allowlist.
 */
object FleetRuntime {
  val nonceCache = NonceCache()
  val confirmations = ConfirmationRegistry()

  fun dispatcher(ctx: Context): FleetDispatcher =
      FleetDispatcher(
          nodeId = FleetSettings.nodeId(ctx),
          backend = AppMeshBackend(ctx),
          nonceCache = nonceCache,
          confirmations = confirmations,
          confirmationSink = FleetConfirmationNotifier(ctx.applicationContext),
      )

  fun confirmAndExecute(ctx: Context, pendingId: String): BridgeResponse =
      dispatcher(ctx).confirmAndExecute(pendingId)

  fun deny(pendingId: String) = confirmations.deny(pendingId)
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
    FleetConfirmationNotifier.ensureChannel(this)
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
      val allowed = isCallerAllowed(uid)
      val source = callerIdentity(uid)
      val resp =
          try {
            FleetRuntime.dispatcher(this@FleetBridgeService)
                .handle(
                    raw,
                    callerAllowed = allowed,
                    controlEnabled = FleetSettings.isExternalControlEnabled(this@FleetBridgeService),
                    sourceNodeRef = source,
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

  private fun callerIdentity(uid: Int): String {
    if (uid < 0) return ""
    val pkgs = packageManager.getPackagesForUid(uid) ?: return ""
    return pkgs.sorted().joinToString(",")
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
    const val ACTION_APPROVE = "com.victorious.mesh.action.FLEET_CONFIRM_APPROVE"
    const val ACTION_DENY = "com.victorious.mesh.action.FLEET_CONFIRM_DENY"
    const val EXTRA_PENDING_ID = "pending_id"
  }
}

/** Posts an on-phone Approve/Deny notification. Never auto-approves a remote trigger. */
class FleetConfirmationNotifier(private val ctx: Context) : ConfirmationSink {
  override fun onConfirmationRequired(pending: PendingConfirmation) {
    ensureChannel(ctx)
    val approve =
        pendingIntent(ctx, FleetBridgeService.ACTION_APPROVE, pending.id, pending.id.hashCode())
    val deny =
        pendingIntent(ctx, FleetBridgeService.ACTION_DENY, pending.id, pending.id.hashCode() + 1)
    val open =
        PendingIntent.getActivity(
            ctx,
            pending.id.hashCode() + 2,
            Intent(ctx, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val body =
        if (pending.actionRef == FleetProtocol.OP_DOWN) {
          ctx.getString(R.string.fleet_confirm_down)
        } else {
          ctx.getString(R.string.fleet_confirm_generic, pending.actionRef)
        }
    val notification =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.fleet_confirm_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(false)
            .addAction(0, ctx.getString(R.string.fleet_confirm_approve), approve)
            .addAction(0, ctx.getString(R.string.fleet_confirm_deny), deny)
            .build()
    if (
        ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      TSLog.w(TAG, "POST_NOTIFICATIONS not granted; confirmation still required on-phone")
      return
    }
    NotificationManagerCompat.from(ctx).notify(notificationId(pending.id), notification)
  }

  companion object {
    const val CHANNEL_ID = "victorious-mesh-fleet-confirm"
    private const val TAG = "FleetConfirm"

    fun ensureChannel(ctx: Context) {
      val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
      mgr.createNotificationChannel(
          NotificationChannel(
              CHANNEL_ID,
              ctx.getString(R.string.fleet_confirm_channel),
              NotificationManager.IMPORTANCE_HIGH,
          ).apply { description = ctx.getString(R.string.fleet_confirm_channel_desc) }
      )
    }

    fun cancel(ctx: Context, pendingId: String) {
      NotificationManagerCompat.from(ctx).cancel(notificationId(pendingId))
    }

    fun notificationId(pendingId: String) = 0x77000000 or (pendingId.hashCode() and 0x00FFFFFF)

    private fun pendingIntent(ctx: Context, action: String, pendingId: String, requestCode: Int): PendingIntent {
      val intent =
          Intent(ctx, FleetConfirmationReceiver::class.java).apply {
            this.action = action
            putExtra(FleetBridgeService.EXTRA_PENDING_ID, pendingId)
          }
      return PendingIntent.getBroadcast(
          ctx,
          requestCode,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }
  }
}

/** Handles Approve / Deny from the confirmation notification. Never auto-approves. */
class FleetConfirmationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra(FleetBridgeService.EXTRA_PENDING_ID) ?: return
    when (intent.action) {
      FleetBridgeService.ACTION_APPROVE -> {
        try {
          FleetRuntime.confirmAndExecute(context.applicationContext, id)
        } catch (e: Exception) {
          TSLog.w(TAG, "approve failed: ${e.javaClass.simpleName}")
        }
      }
      FleetBridgeService.ACTION_DENY -> FleetRuntime.deny(id)
      else -> return
    }
    FleetConfirmationNotifier.cancel(context, id)
  }

  companion object {
    private const val TAG = "FleetConfirm"
  }
}
