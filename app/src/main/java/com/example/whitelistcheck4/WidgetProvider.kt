// === WidgetProvider.kt ===
package com.example.whitelistcheck4

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.*

class WidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateWidget(context: Context, isRestricted: Boolean?, statuses: List<ServiceStatus>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, WidgetProvider::class.java))
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            val statusText = when (isRestricted) {
                true -> "🚫 Ограничения"
                false -> "✅ Свобода"
                null -> "⏳ Неизвестно"
            }
            views.setTextViewText(R.id.widget_status, statusText)

            val date = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            views.setTextViewText(R.id.widget_time, date)

            val servicesText = statuses.take(3).joinToString("\n") { "${if (it.isAccessible) "✅" else "❌"} ${it.name}" }
            views.setTextViewText(R.id.widget_services, servicesText)

            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // При первом обновлении показываем заглушку
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            views.setTextViewText(R.id.widget_status, "⏳ Ожидание")
            views.setTextViewText(R.id.widget_time, "--:--")
            views.setTextViewText(R.id.widget_services, "Нажмите 'Проверить' в приложении")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
