package com.tymewear.run.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtocolTest {

    @Before fun reset() = Protocol.resetState()

    /** Build a 17-byte type-0x01 breath packet. */
    private fun breathPacket(ts: Long, inhale: Int, exhale: Int, tvRaw: Int, e: Int = 0): ByteArray {
        val b = ByteArray(17)
        b[0] = 0x01
        b[1] = (ts and 0xFF).toByte(); b[2] = ((ts shr 8) and 0xFF).toByte()
        b[3] = ((ts shr 16) and 0xFF).toByte(); b[4] = ((ts shr 24) and 0xFF).toByte()
        fun u16(off: Int, v: Int) { b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte() }
        u16(5, inhale); u16(7, exhale); u16(9, tvRaw); u16(11, tvRaw); u16(13, e); u16(15, e)
        return b
    }

    @Test
    fun `matches strap names and rejects the HR sensor`() {
        assertTrue(Protocol.isVitalProDevice("TYME-1a2b"))
        assertTrue(Protocol.isVitalProDevice("VitalPro R"))
        assertFalse(Protocol.isVitalProDevice("TymeHR 1234567"))
        assertFalse(Protocol.isVitalProDevice(null))
    }

    @Test
    fun `first packet uses inhale plus exhale for breath rate`() {
        // 150 + 150 raw units = 300 -> 6000/300 = 20 brpm; tvRaw 150 -> 1.5 L; VE 30
        val d = Protocol.parseNotification(breathPacket(ts = 1000, inhale = 150, exhale = 150, tvRaw = 150))
        assertNotNull(d)
        assertEquals(20.0, d!!.breathRate, 1e-9)
        assertEquals(1.5, d.tidalVolume, 1e-9)
        assertEquals(30.0, d.minuteVolume, 1e-9)
        assertEquals(1.0, d.ieRatio, 1e-9)
    }

    @Test
    fun `later packets use the timestamp delta`() {
        Protocol.parseNotification(breathPacket(ts = 1000, inhale = 150, exhale = 150, tvRaw = 150))
        // 75 ticks * 40ms = 3s -> 20 brpm
        val d = Protocol.parseNotification(breathPacket(ts = 1075, inhale = 100, exhale = 100, tvRaw = 100))
        assertEquals(20.0, d!!.breathRate, 1e-9)
    }

    @Test
    fun `ignores non breath packets and short packets`() {
        assertNull(Protocol.parseNotification(byteArrayOf(0x02, 0, 0)))
        assertNull(Protocol.parseNotification(byteArrayOf(0x01)))
    }

    @Test
    fun `rejects out of range ventilation`() {
        // tvRaw 60000 -> 600 L per breath at 20 brpm -> VE 12000, far over the cap
        assertNull(Protocol.parseNotification(breathPacket(ts = 1000, inhale = 150, exhale = 150, tvRaw = 60000)))
    }

    @Test
    fun `zone boundaries`() {
        assertEquals(0, Protocol.veZone(0.0, 73.0, 96.0, 112.0, 130.0))
        assertEquals(1, Protocol.veZone(72.9, 73.0, 96.0, 112.0, 130.0))
        assertEquals(2, Protocol.veZone(73.0, 73.0, 96.0, 112.0, 130.0))
        assertEquals(5, Protocol.veZone(130.0, 73.0, 96.0, 112.0, 130.0))
    }
}
