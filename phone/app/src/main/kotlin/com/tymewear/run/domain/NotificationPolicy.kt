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

    fun recordingBody(startMs: Long, ve: Double?, zone: ZoneId): String {
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(startMs))
        val veText = ve?.roundToInt()?.toString() ?: "--"
        return "Since $time · VE $veText L/min"
    }
}
