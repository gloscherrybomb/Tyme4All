package com.tymewear.run.domain

import com.tymewear.run.domain.session.SessionMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Pure decisions behind the Android notifications, so they can be tested on the JVM. */
object NotificationPolicy {
    const val UNMATCHED_MIN_MS = 15 * 60 * 1000L
    const val RECORDING_REFRESH_MS = 30_000L

    /** Short strap-driven sessions (fitting the strap, a false start) end unmatched and should not nag. */
    fun notifyUnmatched(meta: SessionMeta): Boolean {
        val end = meta.endMs ?: return false
        return end - meta.startMs >= UNMATCHED_MIN_MS
    }

    /**
     * Body of the single persistent service notification. While a session is open it carries the
     * recording state (start time and live VE); with the strap away and a finished session still
     * waiting for its Intervals.icu activity it says so, because that wait is the only reason the
     * service is still running; otherwise it reports the strap connection.
     */
    fun serviceText(status: StrapStatus, recordingStartMs: Long?, ve: Double?, syncPending: Boolean, relayDown: Boolean, zone: ZoneId): String {
        val strapAway = status == StrapStatus.DISCONNECTED || status == StrapStatus.OFF
        val base = if (recordingStartMs != null) {
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(recordingStartMs))
            val veText = ve?.roundToInt()?.toString() ?: "--"
            "Recording since $time · VE $veText L/min"
        } else if (syncPending && strapAway) {
            "Waiting for a matching Intervals.icu activity"
        } else when (status) {
            StrapStatus.CONNECTED -> "Strap connected"
            StrapStatus.STALE -> "Strap data stale"
            StrapStatus.DISCONNECTED -> "Waiting for strap"
            StrapStatus.OFF -> "Service off"
        }
        return if (relayDown) "$base · relay down" else base
    }
}
