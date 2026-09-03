package com.tymewear.run.domain

/** Where the paired strap is believed to be, from Companion Device Manager presence
 *  callbacks. UNKNOWN covers "not paired" and "no callback has fired yet" alike. */
enum class StrapPresence { NEARBY, AWAY, UNKNOWN }

/**
 * Decides whether the foreground service should keep running.
 *
 * A session in progress always wins: abandoning a recording is worse than an extra
 * notification, so presence alone never stops the service mid-session.
 */
object ServiceLifecycle {

    /** True when the foreground service should stop. */
    fun shouldStop(presence: StrapPresence, sessionOpen: Boolean, serviceEnabled: Boolean): Boolean {
        if (!serviceEnabled) return true
        if (sessionOpen) return false
        if (presence == StrapPresence.AWAY) return true
        return false
    }
}
