package com.tymewear.run.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReserveTest {
    private val r = ReserveSettings(restingBr = 12.0, maxBr = 55.0, restingHr = 60.0, maxHr = 190.0)

    @Test
    fun `percent hrr from heart rate`() {
        assertEquals(50.0, Reserve.percentHrr(125.0, r)!!, 1e-9)
        assertEquals(0.0, Reserve.percentHrr(50.0, r)!!, 1e-9)   // below resting floors at 0
        assertNull(Reserve.percentHrr(null, r))
        assertNull(Reserve.percentHrr(0.0, r))
    }

    @Test
    fun `percent brr from breathing rate`() {
        assertEquals(50.0, Reserve.percentBrr(33.5, r)!!, 1e-9)
        assertEquals(0.0, Reserve.percentBrr(10.0, r)!!, 1e-9)
        assertNull(Reserve.percentBrr(null, r))
    }

    @Test
    fun `mobilization index matches the karoo formula`() {
        // %BRR 50, %HRR 50 -> MI 100
        assertEquals(100.0, Reserve.mobilizationIndex(33.5, 125.0, r)!!, 1e-9)
        // %HRR below 1 -> 0, not a wild number
        assertEquals(0.0, Reserve.mobilizationIndex(33.5, 60.5, r)!!, 1e-9)
        assertNull(Reserve.mobilizationIndex(null, 125.0, r))
        assertNull(Reserve.mobilizationIndex(33.5, null, r))
    }

    @Test
    fun `degenerate ranges give null`() {
        val bad = r.copy(maxBr = 12.0)
        assertNull(Reserve.percentBrr(30.0, bad))
        assertNull(Reserve.mobilizationIndex(30.0, 120.0, bad))
    }
}
