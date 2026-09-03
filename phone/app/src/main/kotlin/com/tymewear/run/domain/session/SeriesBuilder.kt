package com.tymewear.run.domain.session

import com.tymewear.run.domain.Constants
import com.tymewear.run.domain.RollingBuffer
import com.tymewear.run.domain.ZoneClassifier
import com.tymewear.run.domain.ZoneThresholds

data class Sample(val epochSec: Long, val ve: Double?, val br: Double?, val tv: Double?, val ie: Double?, val zone: Int?)

class Series(val startEpochSec: Long, val samples: List<Sample>) {
    val endEpochSec: Long get() = startEpochSec + samples.size - 1
    fun at(epochSec: Long): Sample? {
        val i = (epochSec - startEpochSec).toInt()
        return if (i < 0 || i >= samples.size) null else samples[i]
    }
}

/** Replays a raw log into per-second samples using the same smoothing as LiveState. */
object SeriesBuilder {
    fun build(
        events: List<SessionEvent>,
        thresholds: ZoneThresholds,
        stalenessMs: Long = Constants.BLE_DATA_STALENESS_TIMEOUT_MS,
    ): Series {
        val sorted = events.sortedBy { it.tMs }
        val start = sorted.firstOrNull { it is SessionEvent.Start } ?: sorted.firstOrNull()
            ?: return Series(0, emptyList())
        val end = sorted.lastOrNull { it is SessionEvent.Stop } ?: sorted.last()
        val startSec = start.tMs / 1000
        val endSec = end.tMs / 1000

        val br = RollingBuffer(Constants.SMOOTHING_BREATHS)
        val tv = RollingBuffer(Constants.SMOOTHING_BREATHS)
        var lastBreathMs: Long? = null
        var lastIe: Double? = null
        var connected = true
        var idx = 0
        val out = ArrayList<Sample>((endSec - startSec + 1).toInt())

        for (sec in startSec..endSec) {
            val instantMs = sec * 1000 + 999   // state as of the end of this second
            while (idx < sorted.size && sorted[idx].tMs <= instantMs) {
                when (val e = sorted[idx]) {
                    is SessionEvent.Breath -> { br.add(e.br); tv.add(e.tv); lastIe = e.ie; lastBreathMs = e.tMs; connected = true }
                    is SessionEvent.Strap -> if (!e.connected) { connected = false; br.clear(); tv.clear(); lastBreathMs = null; lastIe = null }
                    else -> {}
                }
                idx++
            }
            val lb = lastBreathMs
            val fresh = connected && lb != null && (sec * 1000 - lb) <= stalenessMs
            if (fresh) {
                val b = br.average(); val v = tv.average(); val ve = b * v
                out.add(Sample(sec, ve, b, v, lastIe, ZoneClassifier.zoneFor(ve, thresholds)))
            } else {
                out.add(Sample(sec, null, null, null, null, null))
            }
        }
        return Series(startSec, out)
    }
}
