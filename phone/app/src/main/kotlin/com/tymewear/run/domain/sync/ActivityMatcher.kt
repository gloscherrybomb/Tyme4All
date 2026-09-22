package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Constants
import kotlin.math.max
import kotlin.math.min

/** Ranks Intervals.icu activities by how much of their time span overlaps the session. */
object ActivityMatcher {
    data class Ranked(val activity: ActivitySummary, val overlapMs: Long)

    fun rank(
        candidates: List<ActivitySummary>,
        sessionStartMs: Long,
        sessionEndMs: Long,
        minOverlapMs: Long = Constants.MIN_OVERLAP_MS,
    ): List<Ranked> =
        candidates
            .filter { it.source?.equals("STRAVA", ignoreCase = true) != true }
            // The Karoo records the same Tyme* fields itself; pushing phone data over them would
            // replace a complete recording with a partial one.
            .filter { it.deviceName?.contains("karoo", ignoreCase = true) != true }
            .map { Ranked(it, overlapMs(it, sessionStartMs, sessionEndMs)) }
            .filter { it.overlapMs >= minOverlapMs }
            .sortedWith(compareByDescending<Ranked> { it.overlapMs }.thenBy { it.activity.startDate })

    fun pick(candidates: List<ActivitySummary>, sessionStartMs: Long, sessionEndMs: Long, minOverlapMs: Long = Constants.MIN_OVERLAP_MS): ActivitySummary? =
        rank(candidates, sessionStartMs, sessionEndMs, minOverlapMs).firstOrNull()?.activity

    fun overlapMs(a: ActivitySummary, sessionStartMs: Long, sessionEndMs: Long): Long {
        val aStart = a.startDate.toEpochMilli()
        val aEnd = aStart + (a.elapsedTimeS ?: 0) * 1_000L
        return max(0L, min(aEnd, sessionEndMs) - max(aStart, sessionStartMs))
    }
}
