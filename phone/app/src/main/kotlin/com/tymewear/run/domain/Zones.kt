package com.tymewear.run.domain

/** Tymewear's thresholds. Zone edges: Endurance, VT1, VT2, Top Z4. VO2max is the top of Z5, not an edge. */
data class ZoneThresholds(val endurance: Double, val vt1: Double, val vt2: Double, val topZ4: Double, val vo2max: Double) {
    companion object {
        /** v0.1.0 stored the same four edges one name up (Karoo naming): its vt1 is Endurance, and so on. */
        fun fromLegacy(vt1: Double, vt2: Double, topZ4: Double, vo2max: Double, vo2maxTop: Double = Constants.DEFAULT_VO2MAX): ZoneThresholds =
            ZoneThresholds(endurance = vt1, vt1 = vt2, vt2 = topZ4, topZ4 = vo2max, vo2max = vo2maxTop)

        /** The v0.1.0 settings migration: VO2max is set at least 10 above the migrated Top Z4. */
        fun migrateLegacy(vt1: Double, vt2: Double, topZ4: Double, vo2max: Double): ZoneThresholds =
            fromLegacy(vt1, vt2, topZ4, vo2max, vo2maxTop = maxOf(Constants.DEFAULT_VO2MAX, vo2max + 10.0))

        /**
         * Endurance < VT1 < VT2 < Top Z4 < VO2max. For values in that order (null = unreadable),
         * returns per value whether it is in error: missing, or not above the value before it.
         */
        fun outOfOrder(values: List<Double?>): List<Boolean> =
            values.mapIndexed { i, v -> v == null || (i > 0 && values[i - 1]?.let { v <= it } == true) }
    }
}

fun ZoneThresholds.inOrder(): Boolean = ZoneThresholds.outOfOrder(listOf(endurance, vt1, vt2, topZ4, vo2max)).none { it }

object ZoneClassifier {
    fun zoneFor(ve: Double, thresholds: ZoneThresholds): Int =
        Protocol.veZone(ve, thresholds.endurance, thresholds.vt1, thresholds.vt2, thresholds.topZ4)
}
