// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.tiles

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tailscale.ipn.UninitializedApp
import com.victorious.mesh.R

/** Home-screen status widget. Disabled until Settings → Tiles & widget is on. */
class MeshStatusWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    ids.forEach { id -> manager.updateAppWidget(id, buildViews(context)) }
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ExtraTilesController.ACTION_WIDGET_TOGGLE) {
      val extras = runCatching { ExtraTilesController.store().enabled() }.getOrDefault(false)
      val vpn = ExtraTilesController.snapshot().connected
      val able = runCatching { UninitializedApp.get().isAbleToStartVPN() }.getOrDefault(false)
      ExtraTilesController.apply(
          context,
          ExtraTilesPlanner.decideWidgetClick(extras, vpn, able),
      )
      updateAll(context)
      return
    }
    super.onReceive(context, intent)
  }

  companion object {
    fun updateAll(context: Context) {
      val mgr = AppWidgetManager.getInstance(context)
      val ids = mgr.getAppWidgetIds(ComponentName(context, MeshStatusWidget::class.java))
      if (ids.isEmpty()) return
      val views = buildViews(context)
      ids.forEach { mgr.updateAppWidget(it, views) }
    }

    fun buildViews(context: Context): RemoteViews {
      val snap = ExtraTilesController.snapshot()
      val views = RemoteViews(context.packageName, R.layout.mesh_status_widget)
      views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_name))
      views.setTextViewText(
          R.id.widget_body,
          ExtraTilesPlanner.widgetBody(snap.connected, snap.meshIpv4, snap.proxyListening),
      )
      views.setOnClickPendingIntent(R.id.widget_root, ExtraTilesController.widgetToggleIntent(context))
      return views
    }
  }
}
