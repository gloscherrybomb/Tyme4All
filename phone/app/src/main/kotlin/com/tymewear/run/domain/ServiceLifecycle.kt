package com.tymewear.run.domain

import com.tymewear.run.domain.session.SessionMeta

/** Where the paired strap is believed to be, from Companion Device Manager presence
 *  callbacks. UNKNOWN covers "not paired" and "no callback has fired yet" alike. */
enum class StrapPresence { NEARBY, AWAY, UNKNOWN }

/**
 * What the service knows about presence watching. [paired]: a Companion Device Manager
 * association exists. [observingCount]: how many associations presence observation started for.
 * [strapConnected]: the strap's BLE link is up now. [msSinceStrapSeen]: time since the strap was
 * last connected, or since the service started if it has not been connected since.
 */
data class PresenceWatch(val paired: Boolean, val observingCount: Int, val strapConnected: Boolean, val msSinceStrapSeen: Long)

/** What opening the app does: start the service, look for the strap with a short scan, or nothing. */
enum class AppOpenAction { START, SCAN, NOTHING }

/**
 * Decides whether the foreground service should keep running.
 *
 * A session in progress always wins: abandoning a recording is worse than an extra
 * notification, so presence alone never stops the service mid-session. A finished
 * session that is still waiting for its Intervals.icu activity keeps the service alive
 * too (up to the sync give-up window): the strap comes off before TPV or Zepp have
 * uploaded, and presence detection would otherwise stop the service before the sync.
 *
 * Companion Device Manager reports presence only when it changes, so after a restart with
 * the strap away no callback may ever come. With presence observed, the service therefore
 * also stops once the strap has not been connected for [Constants.PRESENCE_GRACE_MS] and
 * presence is not NEARBY; CDM starts it again when the strap appears. Unpaired, or paired
 * without observation, the service stays on all the time.
 */
object ServiceLifecycle {

    /** True when the foreground service should stop. */
    fun shouldStop(presence: StrapPresence, sessionOpen: Boolean, serviceEnabled: Boolean, syncPending: Boolean, watch: PresenceWatch): Boolean {
        if (!serviceEnabled) return true
        if (sessionOpen) return false
        if (syncPending) return false
        if (presence == StrapPresence.AWAY) return true
        val observed = watch.paired && watch.observingCount > 0
        if (observed && presence != StrapPresence.NEARBY && !watch.strapConnected &&
            watch.msSinceStrapSeen >= Constants.PRESENCE_GRACE_MS
        ) return true
        return false
    }

    /**
     * What opening the app (or signing in) does. Unpaired, or paired without observation: start
     * the service, as before. With presence observed, start it only when there is work (the strap
     * nearby, a session open, or a finished session still pending for Intervals.icu or Tymewear);
     * otherwise scan briefly for the strap from the app ([canScan]: the scan permission is held
     * and the caller is in the foreground), unless the strap is already being looked for
     * ([scanRunning]: the app's scan, or the running service's own). With no scan, the
     * system starts the service when the strap comes into range.
     */
    fun onAppOpen(paired: Boolean, observing: Boolean, presence: StrapPresence, sessionOpen: Boolean, syncPending: Boolean, canScan: Boolean, scanRunning: Boolean): AppOpenAction {
        if (!(paired && observing)) return AppOpenAction.START
        if (presence == StrapPresence.NEARBY || sessionOpen || syncPending) return AppOpenAction.START
        return if (canScan && !scanRunning) AppOpenAction.SCAN else AppOpenAction.NOTHING
    }

    /**
     * Whether a session is open: the one in memory, or one on disk with no end. A fresh process
     * has none in memory until the service recovers them, and a run left open by a reboot or a
     * killed process is not "pending" either, so without this it would miss its sync window.
     */
    fun sessionOpen(activeSessionId: String?, metas: List<SessionMeta>): Boolean =
        activeSessionId != null || metas.any { it.endMs == null }
}
