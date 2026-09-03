package com.tymewear.run.domain.sync

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.session.Sample
import com.tymewear.run.domain.session.Series
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAlignerTest {
    private val r = ReserveSettings(12.0, 55.0, 60.0, 190.0)
    private val start = Instant.ofEpochSecond(1_000)
    // series covers epoch seconds 1001..1003
    private val series = Series(1_001, listOf(
        Sample(1_001, ve = 40.0, br = 33.5, tv = 1.19, ie = 1.0, zone = 1),
        Sample(1_002, null, null, null, null, null),
        Sample(1_003, ve = 100.0, br = 40.0, tv = 2.5, ie = 0.9, zone = 3),
    ))

    @Test fun `aligns to activity time axis and derives hr streams`() {
        val time = listOf(0.0, 1.0, 2.0, 3.0, 4.0)
        val hr = listOf(100.0, 125.0, 130.0, null, 140.0)
        val out = StreamAligner.align(time, hr, start, series, r).associateBy { it.type }
        assertEquals(StreamCodes.ALL.toSet(), out.keys)
        assertEquals(listOf(null, 40.0, null, 100.0, null), out[StreamCodes.VE]!!.data)
        assertEquals(listOf(null, 1.0, null, 3.0, null), out[StreamCodes.ZONE]!!.data)
        assertEquals(50.0, out[StreamCodes.BRR]!!.data[1]!!, 1e-9)
        assertEquals(100.0, out[StreamCodes.MI]!!.data[1]!!, 1e-9)     // 50/50*100
        assertNull(out[StreamCodes.MI]!!.data[3])                       // hr missing
        assertEquals(true, out.values.all { it.custom })
        assertEquals(true, out.values.all { it.data.size == 5 })
    }

    @Test fun `no hr stream gives null hr derived values but breathing still aligns`() {
        val out = StreamAligner.align(listOf(1.0), null, start, series, r).associateBy { it.type }
        assertEquals(40.0, out[StreamCodes.VE]!!.data[0]!!, 1e-9)
        assertNull(out[StreamCodes.MI]!!.data[0])
        assertEquals(50.0, out[StreamCodes.BRR]!!.data[0]!!, 1e-9)
    }

    @Test fun `fractional time rounds down`() {
        val out = StreamAligner.align(listOf(1.9), null, start, series, r).associateBy { it.type }
        assertEquals(40.0, out[StreamCodes.VE]!!.data[0]!!, 1e-9)
    }
}
