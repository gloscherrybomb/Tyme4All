package com.tymewear.run.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStateTest {
    private val s = Settings.DEFAULT
    private fun breath(br: Double, tv: Double, ts: Long) =
        Protocol.BreathingData(br, tv, br * tv, 1.0, 100, 100, (tv * 100).toInt(), 0, ts)

    @Test
    fun `off until enabled, disconnected until connected`() {
        val ls = LiveState()
        ls.setServiceEnabled(false)
        assertEquals("off", ls.payload(s, 0).status)
        ls.setServiceEnabled(true)
        assertEquals("disconnected", ls.payload(s, 0).status)
        ls.onConnected()
        // connected but no breath yet counts as stale: nothing to show
        assertEquals("stale", ls.payload(s, 0).status)
    }

    @Test
    fun `smooths and classifies`() {
        val ls = LiveState()
        ls.onConnected()
        ls.onBreath(breath(20.0, 2.0, 1), nowMs = 1_000)   // VE 40
        ls.onBreath(breath(30.0, 3.0, 2), nowMs = 2_000)   // VE 90
        val p = ls.payload(s, nowMs = 3_000)
        assertEquals("connected", p.status)
        assertEquals(25.0, p.br!!, 1e-9)
        assertEquals(2.5, p.tv!!, 1e-9)
        assertEquals(62.5, p.ve!!, 1e-9)   // smoothed BR * smoothed TV, not mean VE
        assertEquals(1, p.zone)
        assertEquals(3_000L - 2_000L, 3_000L - p.updatedAtMs!!)
    }

    @Test
    fun `goes stale then clears on disconnect`() {
        val ls = LiveState(stalenessMs = 10_000)
        ls.onConnected()
        ls.onBreath(breath(20.0, 2.0, 1), nowMs = 0)
        assertEquals("connected", ls.payload(s, 10_000).status)
        val stale = ls.payload(s, 10_001)
        assertEquals("stale", stale.status)
        assertNull(stale.ve)
        ls.onDisconnected()
        val off = ls.payload(s, 10_002)
        assertEquals("disconnected", off.status)
        assertNull(off.batteryPct)
        assertEquals(0, off.zone)
    }

    @Test
    fun `payload carries thresholds reserve session and battery and serialises`() {
        val ls = LiveState()
        ls.onConnected(); ls.onBattery(77); ls.setSessionId("20260903-071000")
        val p = ls.payload(s.copy(thresholds = ZoneThresholds(60.0, 80.0, 100.0, 120.0)), 0)
        assertEquals(60.0, p.thresholds.vt1, 1e-9)
        assertEquals(190.0, p.reserve.maxHr, 1e-9)
        assertEquals(77, p.batteryPct)
        assertEquals("20260903-071000", p.sessionId)
        val json = Json.encodeToString(LivePayload.serializer(), p)
        assertTrue(json.contains("\"status\":\"stale\""))
    }
}
