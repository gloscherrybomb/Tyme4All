package com.tymewear.run.domain

data class ReserveSettings(val restingBr: Double, val maxBr: Double, val restingHr: Double, val maxHr: Double)

/** The Karoo app's mobilization index formula (TymewearData.recomputeMi), made pure. */
object Reserve {
    fun percentHrr(hr: Double?, r: ReserveSettings): Double? {
        val range = r.maxHr - r.restingHr
        if (hr == null || hr <= 0.0 || range <= 0.0) return null
        return ((hr - r.restingHr) / range * 100.0).coerceAtLeast(0.0)
    }

    fun percentBrr(br: Double?, r: ReserveSettings): Double? {
        val range = r.maxBr - r.restingBr
        if (br == null || br <= 0.0 || range <= 0.0) return null
        return ((br - r.restingBr) / range * 100.0).coerceAtLeast(0.0)
    }

    fun mobilizationIndex(br: Double?, hr: Double?, r: ReserveSettings): Double? {
        val brr = percentBrr(br, r) ?: return null
        val hrr = percentHrr(hr, r) ?: return null
        return if (hrr >= 1.0) brr / hrr * 100.0 else 0.0
    }
}
