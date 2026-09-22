package com.tymewear.run.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/** "Discard" action on the service notification: stop waiting for an activity that will never come. */
class DiscardPendingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        val ids = Graph.sessionStore.discardPending(System.currentTimeMillis())
        Timber.i("Discarded pending sessions: $ids")
        // The service's housekeeping loop refreshes the notification within its 10 s tick and then
        // stops itself if the strap is away; poking it here shortens the wait.
        RecorderService.start(context)
    }

    companion object {
        const val ACTION = "com.tymewear.run.DISCARD_PENDING"
    }
}
