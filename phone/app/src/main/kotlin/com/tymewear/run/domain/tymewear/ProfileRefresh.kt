package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.ZoneThresholds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How reading the thresholds from Tymewear went. A failure's [Failed.reason] is an HTTP code or
 * an exception class name, never text from a reply, so it is safe to show and to store.
 */
sealed class ProfileRefresh {
    /** What Tymewear holds; a null is a value Tymewear does not have, so the stored one stays. */
    data class Refreshed(val bikeThresholds: ZoneThresholds?, val runThresholds: ZoneThresholds?, val reserve: ReserveSettings?) : ProfileRefresh()
    data class Failed(val reason: String, val errorClass: String) : ProfileRefresh()
    /** Tymewear refused the saved sign-in. */
    data object AuthRefused : ProfileRefresh()
    /** Not signed in, or the sign-in was refused earlier: nothing was read. */
    data object NotSignedIn : ProfileRefresh()

    companion object {
        const val REFUSED_REASON = "Tymewear refused the sign-in"

        /** Snackbar after a sign-in that Tymewear accepted. */
        fun signInMessage(r: ProfileRefresh): String = when (r) {
            is Failed -> "Signed in to Tymewear, but couldn't read your thresholds: ${r.reason}"
            AuthRefused -> "Tymewear stopped accepting your sign-in. Sign in again."
            else -> "Signed in to Tymewear"
        }

        /** Snackbar after the Refresh from Tymewear button. */
        fun refreshMessage(r: ProfileRefresh): String = when (r) {
            is Refreshed -> "Thresholds read from Tymewear"
            is Failed -> "Couldn't read thresholds: ${r.reason}"
            AuthRefused -> "Tymewear stopped accepting your sign-in. Sign in again."
            NotSignedIn -> "Sign in to Tymewear first"
        }

        /** The Tymewear card's line about the last read; null before the first. */
        fun statusLine(s: Settings, zone: ZoneId): String? {
            s.tymewearRefreshError?.let { return "Couldn't read thresholds: $it" }
            val at = s.tymewearRefreshMs ?: return null
            return "Thresholds read ${HH_MM.withZone(zone).format(Instant.ofEpochMilli(at))}"
        }

        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
