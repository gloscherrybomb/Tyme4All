package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Reserve
import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.session.Sample
import com.tymewear.run.domain.session.Series
import java.time.Instant
import kotlin.math.floor

object StreamCodes {
    const val VE = "TymeVentilation"
    const val BR = "TymeBreathRate"
    const val TV = "TymeTidalVolume"
    const val IE = "TymeIERatio"
    const val ZONE = "TymeVeZone"
    const val BRR = "TymeBreathReserve"
    const val MI = "TymeMobilizationIndex"
    val ALL = listOf(VE, BR, TV, IE, ZONE, BRR, MI)
}

/** Lays the per-second series onto the activity's own time axis. Never changes the row count. */
object StreamAligner {
    fun align(timeStream: List<Double?>, hrStream: List<Double?>?, activityStart: Instant, series: List<Series>, reserve: ReserveSettings): List<Stream> {
        val n = timeStream.size
        val ve = ArrayList<Double?>(n); val br = ArrayList<Double?>(n); val tv = ArrayList<Double?>(n)
        val ie = ArrayList<Double?>(n); val zone = ArrayList<Double?>(n); val brr = ArrayList<Double?>(n); val mi = ArrayList<Double?>(n)
        for (i in 0 until n) {
            val t = timeStream[i]
            val s = if (t == null) null else sampleAt(series, activityStart.epochSecond + floor(t).toLong())
            val hr = hrStream?.getOrNull(i)
            ve.add(s?.ve); br.add(s?.br); tv.add(s?.tv); ie.add(s?.ie); zone.add(s?.zone?.toDouble())
            brr.add(Reserve.percentBrr(s?.br, reserve))
            mi.add(Reserve.mobilizationIndex(s?.br, hr, reserve))
        }
        return listOf(
            Stream(StreamCodes.VE, ve, true), Stream(StreamCodes.BR, br, true), Stream(StreamCodes.TV, tv, true),
            Stream(StreamCodes.IE, ie, true), Stream(StreamCodes.ZONE, zone, true),
            Stream(StreamCodes.BRR, brr, true), Stream(StreamCodes.MI, mi, true),
        )
    }

    /** The first series with a live sample at this second; sessions never overlap in time, so order only matters for ties. */
    private fun sampleAt(series: List<Series>, epochSec: Long): Sample? =
        series.firstNotNullOfOrNull { s -> s.at(epochSec)?.takeIf { it.ve != null } }
}
