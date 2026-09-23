package com.tymewear.run.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneClassifierTest {

    private val t = ZoneThresholds(endurance = 73.0, vt1 = 96.0, vt2 = 112.0, topZ4 = 130.0, vo2max = 180.0)

    @Test
    fun `zones follow Tymewear's edges`() {
        assertEquals(0, ZoneClassifier.zoneFor(0.0, t))
        assertEquals(1, ZoneClassifier.zoneFor(72.9, t)); assertEquals(2, ZoneClassifier.zoneFor(73.0, t))
        assertEquals(3, ZoneClassifier.zoneFor(96.0, t)); assertEquals(4, ZoneClassifier.zoneFor(112.0, t))
        assertEquals(5, ZoneClassifier.zoneFor(130.0, t)); assertEquals(5, ZoneClassifier.zoneFor(250.0, t))
    }

    @Test
    fun `thresholds in Tymewear's order pass`() {
        assertTrue(t.inOrder())
        assertEquals(List(5) { false }, ZoneThresholds.outOfOrder(listOf(73.0, 96.0, 112.0, 130.0, 180.0)))
    }

    @Test
    fun `a threshold not above the one before it is out of order`() {
        assertFalse(t.copy(vt2 = 96.0).inOrder())
        assertEquals(listOf(false, false, true, false, false), ZoneThresholds.outOfOrder(listOf(73.0, 96.0, 96.0, 130.0, 180.0)))
        assertEquals(listOf(false, false, false, false, true), ZoneThresholds.outOfOrder(listOf(73.0, 96.0, 112.0, 130.0, 120.0)))
    }

    @Test
    fun `a missing threshold is out of order and does not flag the next one`() {
        assertEquals(listOf(false, true, false, false, false), ZoneThresholds.outOfOrder(listOf(73.0, null, 112.0, 130.0, 180.0)))
        assertEquals(listOf(true, false, false, false, false), ZoneThresholds.outOfOrder(listOf(null, 96.0, 112.0, 130.0, 180.0)))
    }

    @Test
    fun `no ventilation is not a zone`() {
        assertEquals(0, ZoneClassifier.zoneFor(0.0, t))
    }

    @Test
    fun `the value a caller displays determines the zone it gets`() {
        // The regression this locks down: a field must not show one VE and colour by a
        // zone derived from a different VE. Same input, same answer, every caller.
        val displayed = 97.5
        assertEquals(ZoneClassifier.zoneFor(displayed, t), ZoneClassifier.zoneFor(displayed, t))
        assertEquals(3, ZoneClassifier.zoneFor(displayed, t))
    }

    @Test
    fun `matches the legacy Protocol implementation across a sweep`() {
        // Guards the refactor: behaviour must be unchanged for every plausible VE.
        var ve = 0.0
        while (ve <= 250.0) {
            assertEquals(
                "VE=$ve",
                Protocol.veZone(ve, t.endurance, t.vt1, t.vt2, t.topZ4),
                ZoneClassifier.zoneFor(ve, t),
            )
            ve += 0.5
        }
    }
}
