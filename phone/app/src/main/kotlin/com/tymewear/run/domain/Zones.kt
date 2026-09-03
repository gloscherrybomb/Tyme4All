package com.tymewear.run.domain

data class ZoneThresholds(val vt1: Double, val vt2: Double, val topZ4: Double, val vo2max: Double)

object ZoneClassifier {
    fun zoneFor(ve: Double, thresholds: ZoneThresholds): Int =
        Protocol.veZone(ve, thresholds.vt1, thresholds.vt2, thresholds.topZ4, thresholds.vo2max)
}
