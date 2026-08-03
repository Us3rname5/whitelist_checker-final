package com.example.whitelistcheck4

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.*

class WidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateWidget(context: Context, isRestricted: Boolean?, statuses: List<ServiceStatus>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, isRestricted, statuses)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context, isRestricted: Boolean?, statuses: List<ServiceStatus>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val restricted = isRestricted == true

            // Ограничения = белый, свобода/по умолчанию = чёрный
            views.setInt(R.id.widget_root, "setBackgroundResource",
                if (restricted) R.drawable.widget_background_white else R.drawable.widget_background_black)
            views.setTextColor(R.id.widget_status, if (restricted) Color.BLACK else Color.WHITE)
            views.setTextColor(R.id.widget_time, if (restricted) Color.parseColor("#555555") else Color.parseColor("#999999"))
            views.setTextColor(R.id.widget_services, if (restricted) Color.parseColor("#333333") else Color.parseColor("#BBBBBB"))
            views.setTextColor(R.id.widget_check_btn, if (restricted) Color.BLACK else Color.WHITE)

            views.setTextViewText(R.id.widget_status, when (isRestricted) {
                true -> "🚫 ограничения"
                false -> "✅ свобода"
                null -> "⏳ неизвестно"
            })
            views.setTextViewText(R.id.widget_time, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
            views.setTextViewText(R.id.widget_services,
                statuses.take(3).joinToString("\n") { "${if (it.isAccessible) "✅" else "❌"} ${it.name}" })

            // Кнопка "ПРОВЕРИТЬ" открывает приложение и сразу запускает проверку
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("AUTO_START_CHECK", true)
            }
            val pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_check_btn, pending)
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context, null, emptyList())
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }
}
