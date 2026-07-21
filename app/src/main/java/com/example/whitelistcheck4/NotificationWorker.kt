package com.example.whitelistcheck4

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class NotificationWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val CHANNEL_ID = "whitelist_check_channel"
        const val PREFS_NAME = "whitelist_prefs"
        const val KEY_LAST_RESTRICTED = "last_restricted"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Проверка каждые 15 минут (вместо 3 часов)
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "whitelist_check",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("whitelist_check")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val statuses = NetworkChecker.checkAll()
            val restricted = NetworkChecker.isRestricted(statuses)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastRestricted = prefs.getBoolean(KEY_LAST_RESTRICTED, true) // по умолчанию считаем, что были ограничения (чтобы при первом запуске не дублировать)

            // Если статус изменился – отправляем уведомление
            if (restricted != lastRestricted) {
                if (restricted) {
                    // Ограничения появились
                    sendNotification("ограничения включены", "youtube, telegram, whatsapp и vpn могут быть недоступны.")
                } else {
                    // Ограничения сняты
                    sendNotification("ограничения сняты", "youtube, telegram, whatsapp и vpn снова доступны.")
                }
            }

            // Сохраняем текущий статус
            prefs.edit().putBoolean(KEY_LAST_RESTRICTED, restricted).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun sendNotification(title: String, message: String) {
        createNotificationChannel()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(1, notification)
            }
        } else {
            NotificationManagerCompat.from(context).notify(1, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "оповещения о белых списках",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "уведомления при изменении статуса ограничений"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
