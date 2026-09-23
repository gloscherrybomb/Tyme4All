package com.tymewear.run.domain.session

import com.tymewear.run.domain.ZoneThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesBuilderTest {
    private val t = ZoneThresholds(73.0, 96.0, 112.0, 130.0, 180.0)
    private fun breath(tMs: Long, br: Double, tv: Double) = SessionEvent.Breath(tMs, br, tv, 1.0, (tv * 100).toInt(), 100, 100, tMs / 40)

    @Test
    fun `one sample per second from start to stop`() {
        val ev = listOf(SessionEvent.Start(10_000, "watch"), SessionEvent.Stop(14_500, "watch"))
        val s = SeriesBuilder.build(ev, t)
        assertEquals(10L, s.startEpochSec)
        assertEquals(5, s.samples.size)                // seconds 10..14
        assertEquals(14L, s.endEpochSec)
        assertNull(s.samples[0].ve)
    }

    @Test
    fun `values hold between breaths and smooth over breaths`() {
        val ev = listOf(
            SessionEvent.Start(0, "watch"),
            breath(1_000, 20.0, 2.0),   // VE 40
            breath(4_000, 30.0, 3.0),   // smoothed BR 25, TV 2.5 -> VE 62.5
            SessionEvent.Stop(6_000, "watch"),
        )
        val s = SeriesBuilder.build(ev, t)
        assertNull(s.at(0)!!.ve)
        assertEquals(40.0, s.at(1)!!.ve!!, 1e-9)
        assertEquals(40.0, s.at(3)!!.ve!!, 1e-9)
        assertEquals(62.5, s.at(4)!!.ve!!, 1e-9)
        assertEquals(1, s.at(4)!!.zone)
        assertEquals(62.5, s.at(6)!!.ve!!, 1e-9)
    }

    @Test
    fun `goes null when stale and after a disconnect`() {
        val ev = listOf(
            SessionEvent.Start(0, "watch"),
            breath(1_000, 20.0, 2.0),
            SessionEvent.Strap(20_000, connected = false),
            breath(30_000, 20.0, 2.0),
            SessionEvent.Stop(31_000, "watch"),
        )
        val s = SeriesBuilder.build(ev, t, stalenessMs = 10_000)
        assertEquals(40.0, s.at(11)!!.ve!!, 1e-9)   // 10s after breath, still fresh
        assertNull(s.at(12)!!.ve)                    // stale
        assertNull(s.at(25)!!.ve)                    // disconnected
        assertEquals(40.0, s.at(30)!!.ve!!, 1e-9)   // buffers restarted after reconnect
    }

    @Test
    fun `lookup outside range is null`() {
        val s = SeriesBuilder.build(listOf(SessionEvent.Start(5_000, "watch"), SessionEvent.Stop(6_000, "watch")), t)
        assertNull(s.at(4))
        assertNull(s.at(7))
    }
}
