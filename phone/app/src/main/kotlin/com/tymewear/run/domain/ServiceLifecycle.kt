package com.tymewear.run.domain

/** Where the paired strap is believed to be, from Companion Device Manager presence
 *  callbacks. UNKNOWN covers "not paired" and "no callback has fired yet" alike. */
enum class StrapPresence { NEARBY, AWAY, UNKNOWN }

/**
 * Decides whether the foreground service should keep running.
 *
 * A session in progress always wins: abandoning a recording is worse than an extra
 * notification, so presence alone never stops the service mid-session. A finished
 * session that is still waiting for its Intervals.icu activity keeps the service alive
 * too (up to the sync give-up window): the strap comes off before TPV or Zepp have
 * uploaded, and presence detection would otherwise stop the service before the sync.
 */
object ServiceLifecycle {

    /** True when the foreground service should stop. */
    fun shouldStop(presence: StrapPresence, sessionOpen: Boolean, serviceEnabled: Boolean, syncPending: Boolean): Boolean {
        if (!serviceEnabled) return true
        if (sessionOpen) return false
        if (syncPending) return false
        if (presence == StrapPresence.AWAY) return true
        return false
    }
}
