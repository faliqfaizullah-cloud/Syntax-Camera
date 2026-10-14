package com.example.syntaxcam

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Transparent widget that shows only the camera bar. Tap it to open the camera. */
class BarWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bar)
            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            rv.setOnClickPendingIntent(
                R.id.bar_root,
                PendingIntent.getActivity(ctx, 1000 + id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            mgr.updateAppWidget(id, rv)
        }
    }
}
