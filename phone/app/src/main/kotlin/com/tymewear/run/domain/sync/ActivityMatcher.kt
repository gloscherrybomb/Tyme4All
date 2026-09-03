package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Constants
import kotlin.math.abs

object ActivityMatcher {
    fun pick(candidates: List<ActivitySummary>, sessionStartMs: Long, windowMs: Long = Constants.MATCH_WINDOW_MS): ActivitySummary? =
        candidates
            .filter { it.source?.equals("STRAVA", ignoreCase = true) != true }
            .filter { abs(it.startDate.toEpochMilli() - sessionStartMs) <= windowMs }
            .sortedWith(compareBy<ActivitySummary>({ if (isAmazfit(it)) 0 else 1 }, { abs(it.startDate.toEpochMilli() - sessionStartMs) }))
            .firstOrNull()

    private fun isAmazfit(a: ActivitySummary) =
        a.deviceName?.contains("amazfit", ignoreCase = true) == true || a.source?.equals("ZEPP", ignoreCase = true) == true
}
