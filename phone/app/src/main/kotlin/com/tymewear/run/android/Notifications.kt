package com.tymewear.run.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.tymewear.run.android.ui.MainActivity

object Notifications {
    const val CHANNEL_SERVICE = "kbreathe_service"
    const val CHANNEL_SYNC = "kbreathe_sync"
    const val ID_SERVICE = 1
    const val ID_RECORDING = 2

    fun ensureChannels(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_SERVICE) == null)
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Strap connection", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        if (mgr.getNotificationChannel(CHANNEL_SYNC) == null)
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_SYNC, "Intervals.icu sync", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun serviceNotification(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setContentTitle("K-Breathe Run")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true).setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .build()

    fun recording(ctx: Context, body: String) = post(ctx, ID_RECORDING,
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("Recording breathing data")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .build())

    fun clearRecording(ctx: Context) =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_RECORDING)

    fun synced(ctx: Context, sessionId: String, activityId: String, activityLabel: String) = post(ctx, sessionId.hashCode(),
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("Breathing data synced to Intervals.icu")
            .setContentText("Added to $activityLabel")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(PendingIntent.getActivity(ctx, activityId.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://intervals.icu/activities/$activityId")), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build())

    fun syncFailed(ctx: Context, sessionId: String, message: String) = post(ctx, sessionId.hashCode(),
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("Intervals.icu sync failed")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build())

    fun unmatched(ctx: Context, sessionId: String) = post(ctx, sessionId.hashCode(),
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("No Intervals.icu activity found for a breathing session")
            .setContentText("Open K-Breathe Run to match it by hand")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build())

    private fun post(ctx: Context, id: Int, n: Notification) =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}
