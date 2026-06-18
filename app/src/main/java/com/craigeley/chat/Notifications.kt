package com.craigeley.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Local notifications. There is no FCM/remote push — the device has no Google Play
 * services — so the socket service posts these itself: a high-importance one per
 * incoming message ([post]), and a quiet ongoing one ([foregroundNotification])
 * that keeps the live-socket service alive.
 */
object Notifications {
    private const val MESSAGE_CHANNEL = "messages"
    private const val SERVICE_CHANNEL = "service"
    private const val MESSAGE_ID = 1
    const val SERVICE_ID = 2

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(MESSAGE_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(MESSAGE_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New iMessages"
                },
            )
        }
        if (manager.getNotificationChannel(SERVICE_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(SERVICE_CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps chat connected for new messages"
                },
            )
        }
    }

    /** The quiet ongoing notification the foreground service runs under. */
    fun foregroundNotification(context: Context): Notification =
        Notification.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reply)
            .setContentTitle("chat")
            .setContentText("Connected")
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    /** A per-message notification, posted when a message arrives while backgrounded. */
    fun post(context: Context, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val snippet = text.trim().take(300)
        val notification = Notification.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reply)
            .setContentTitle(title)
            .setContentText(snippet)
            .setStyle(Notification.BigTextStyle().bigText(snippet))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        manager.notify(MESSAGE_ID, notification)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(MESSAGE_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
