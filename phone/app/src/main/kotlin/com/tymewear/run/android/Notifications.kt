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
import com.tymewear.run.domain.tymewear.TymewearOutcome

object Notifications {
    /** Low importance: status bar icon, no sound. The original minimum-importance channel hid the icon. */
    const val CHANNEL_SERVICE = "kbreathe_service_v2"
    private const val CHANNEL_SERVICE_V1 = "kbreathe_service"
    const val CHANNEL_SYNC = "kbreathe_sync"
    const val ID_SERVICE = 1
    private const val ID_TYMEWEAR_SIGN_IN = 2

    fun ensureChannels(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_SERVICE_V1) != null) mgr.deleteNotificationChannel(CHANNEL_SERVICE_V1)
        if (mgr.getNotificationChannel(CHANNEL_SERVICE) == null)
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Strap and recording", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        if (mgr.getNotificationChannel(CHANNEL_SYNC) == null)
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_SYNC, "Intervals.icu sync", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** [discardable] adds a "Discard" action that gives up on the sessions still waiting for an activity. */
    fun serviceNotification(ctx: Context, text: String, discardable: Boolean = false): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setContentTitle("Tyme4All")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .apply {
                if (discardable) addAction(0, "Discard, no activity coming", PendingIntent.getBroadcast(
                    ctx, 1, Intent(ctx, DiscardPendingReceiver::class.java).setAction(DiscardPendingReceiver.ACTION), PendingIntent.FLAG_IMMUTABLE))
            }
            .build()

    /** [tymewear] is the Tymewear step's outcome for this sync, or null when Tymewear is off. */
    fun synced(ctx: Context, sessionId: String, activityId: String, activityLabel: String, tymewear: TymewearOutcome?) {
        val title = if (tymewear == TymewearOutcome.Uploaded) "Breathing added to Intervals.icu and Tymewear" else "Breathing data synced to Intervals.icu"
        val tymewearLine = when (tymewear) {
            is TymewearOutcome.Skipped -> "Tymewear: ${tymewear.reason}"
            is TymewearOutcome.Failed -> "Tymewear: ${tymewear.reason}"
            else -> null
        }
        val text = listOfNotNull("Added to $activityLabel", tymewearLine).joinToString("\n")
        post(ctx, sessionId.hashCode(),
            NotificationCompat.Builder(ctx, CHANNEL_SYNC)
                .setContentTitle(title)
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(PendingIntent.getActivity(ctx, activityId.hashCode(),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://intervals.icu/activities/$activityId")), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build())
    }

    /**
     * A later Tymewear retry succeeded for a session already synced to Intervals.icu.
     * [label] is the activity's name, else its type and local start time (as in the sync notification).
     */
    fun tymewearUploaded(ctx: Context, sessionId: String, label: String) = post(ctx, sessionId.hashCode(),
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("Breathing added to Tymewear")
            .setContentText("Tymewear's copy of $label")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build())

    /** Tymewear refused the saved sign-in. One notification, replaced rather than stacked. */
    fun tymewearSignIn(ctx: Context) = post(ctx, ID_TYMEWEAR_SIGN_IN,
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("Sign in to Tymewear again")
            .setContentText("Tymewear stopped accepting your sign-in. Sign in again in Settings.")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build())

    fun syncFailed(ctx: Context, sessionId: String, message: String) = post(ctx, sessionId.hashCode(),
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("Intervals.icu sync failed")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build())

    fun unmatched(ctx: Context, sessionId: String) = post(ctx, sessionId.hashCode(),
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("No Intervals.icu activity found for a breathing session")
            .setContentText("Open Tyme4All to match it by hand")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build())

    private fun post(ctx: Context, id: Int, n: Notification) =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}
