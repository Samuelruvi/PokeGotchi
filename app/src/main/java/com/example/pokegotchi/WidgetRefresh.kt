package com.example.pokegotchi

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Notifica al widget que se refresque (usado desde MainActivity y StarterActivity). */
object WidgetRefresh {
    fun updateWidgets(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PokeWidgetProvider::class.java))
        if (ids.isEmpty()) return
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_pokemon)
        context.sendBroadcast(Intent(context, PokeWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        })
    }
}
