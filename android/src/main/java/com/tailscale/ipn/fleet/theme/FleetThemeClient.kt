// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.fleet.theme

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.tailscale.ipn.util.TSLog
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads FLEET_THEME schemaVersion 1 from VictorLaunch. Never writes.
 *
 * Auth is package + cert pin of the *provider*, not the signature
 * permission (the fleet does not share a signing key).
 */
object FleetThemeClient {
  private const val TAG = "FleetThemeClient"

  private val uri: Uri = Uri.parse(FleetThemeContract.URI)
  private val _snapshot = MutableStateFlow(FleetThemePalettes.builtinNeonSnapshot())
  val snapshot: StateFlow<FleetThemeSnapshot> = _snapshot

  @Volatile private var observer: ContentObserver? = null

  @Synchronized
  fun start(context: Context) {
    val app = context.applicationContext
    refresh(app)
    if (observer != null) return
    val obs =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
          override fun onChange(selfChange: Boolean) {
            refresh(app)
          }

          override fun onChange(selfChange: Boolean, uri: Uri?) {
            refresh(app)
          }
        }
    try {
      app.contentResolver.registerContentObserver(uri, true, obs)
      observer = obs
    } catch (e: SecurityException) {
      TSLog.d(TAG, "theme observer refused: ${e.javaClass.simpleName}")
      applyFallback(FleetThemeFallbackReason.SECURITY_EXCEPTION)
    } catch (e: Exception) {
      TSLog.d(TAG, "theme observer not registered: ${e.javaClass.simpleName}")
    }
  }

  @Synchronized
  fun stop(context: Context) {
    val obs = observer ?: return
    runCatching { context.applicationContext.contentResolver.unregisterContentObserver(obs) }
    observer = null
  }

  fun refresh(context: Context) {
    _snapshot.value = snapshotOrBuiltin(read(context.applicationContext))
  }

  internal fun read(context: Context): FleetThemeDecision {
    val pm = context.packageManager
    val provider =
        try {
          pm.resolveContentProvider(FleetThemeContract.AUTHORITY, 0)
        } catch (_: Exception) {
          null
        }
    if (provider == null) {
      return FleetThemePlanner.decide(
          FleetThemeQuery(
              providerPackage = null,
              providerCerts = emptyList(),
              providerMissing = true,
          )
      )
    }
    val certs = signingCerts(pm, provider.packageName)
    if (!FleetThemePlanner.providerTrusted(provider.packageName, certs)) {
      return FleetThemePlanner.decide(
          FleetThemeQuery(
              providerPackage = provider.packageName,
              providerCerts = certs,
          )
      )
    }
    return try {
      queryPinned(context.contentResolver, provider.packageName, certs)
    } catch (_: SecurityException) {
      FleetThemePlanner.decide(
          FleetThemeQuery(
              providerPackage = provider.packageName,
              providerCerts = certs,
              securityException = true,
          )
      )
    } catch (e: Exception) {
      TSLog.d(TAG, "theme query failed: ${e.javaClass.simpleName}")
      FleetThemePlanner.decide(
          FleetThemeQuery(
              providerPackage = provider.packageName,
              providerCerts = certs,
              schemaVersion = null,
          )
      )
    }
  }

  private fun queryPinned(
      resolver: ContentResolver,
      providerPackage: String,
      certs: List<String>,
  ): FleetThemeDecision {
    resolver.query(uri, null, null, null, null).use { cursor ->
      if (cursor == null || !cursor.moveToFirst()) {
        return FleetThemePlanner.decide(
            FleetThemeQuery(
                providerPackage = providerPackage,
                providerCerts = certs,
            )
        )
      }
      val columns = linkedMapOf<String, String>()
      for (i in 0 until cursor.columnCount) {
        val name = cursor.getColumnName(i) ?: continue
        columns[name] = runCatching { cursor.getString(i) }.getOrNull().orEmpty()
      }
      val schema = intColumn(cursor, columns, FleetThemeContract.COL_SCHEMA_VERSION)
      val updated = longColumn(cursor, columns, FleetThemeContract.COL_UPDATED_AT)
      val json = columns[FleetThemeContract.COL_JSON]?.ifBlank { null }
      return FleetThemePlanner.decide(
          FleetThemeQuery(
              providerPackage = providerPackage,
              providerCerts = certs,
              schemaVersion = schema,
              json = json,
              columns = columns,
              updatedAtMs = updated,
          )
      )
    }
  }

  private fun intColumn(
      cursor: Cursor,
      columns: Map<String, String>,
      name: String,
  ): Int? {
    val idx = cursor.getColumnIndex(name)
    if (idx >= 0) {
      runCatching {
            if (!cursor.isNull(idx)) return cursor.getInt(idx)
          }
          .getOrNull()
    }
    return columns[name]?.toIntOrNull()
  }

  private fun longColumn(
      cursor: Cursor,
      columns: Map<String, String>,
      name: String,
  ): Long {
    val idx = cursor.getColumnIndex(name)
    if (idx >= 0) {
      runCatching {
            if (!cursor.isNull(idx)) return cursor.getLong(idx)
          }
          .getOrNull()
    }
    return columns[name]?.toLongOrNull() ?: 0L
  }

  private fun applyFallback(reason: FleetThemeFallbackReason) {
    _snapshot.value =
        FleetThemePlanner.snapshotOrBuiltin(FleetThemeDecision.Fallback(reason))
  }

  private fun snapshotOrBuiltin(decision: FleetThemeDecision): FleetThemeSnapshot =
      FleetThemePlanner.snapshotOrBuiltin(decision)

  @Suppress("DEPRECATION")
  private fun signingCerts(pm: PackageManager, pkg: String): List<String> =
      try {
        val sigs =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
              val si = info.signingInfo ?: return emptyList()
              si.apkContentsSigners
            } else {
              pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
        sigs.orEmpty().map { bytes ->
          FleetThemePlanner.normalize(
              MessageDigest.getInstance("SHA-256")
                  .digest(bytes.toByteArray())
                  .joinToString("") { "%02x".format(it) }
          )
        }
      } catch (_: Exception) {
        emptyList()
      }
}
