package com.tymewear.run.domain.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityMatcherTest {
    private val t0 = Instant.parse("2026-09-22T18:00:00Z")
    private val sessionStart = t0.toEpochMilli()
    private val sessionEnd = t0.plusSeconds(3_600).toEpochMilli()   // one hour session

    private fun a(id: String, startOffsetSec: Long, elapsedSec: Int?, source: String? = "TPV", device: String? = null) =
        ActivitySummary(id, t0.plusSeconds(startOffsetSec), null, "Ride", source, device, elapsedSec)

    @Test fun `activity fully inside the session matches with its own length`() {
        val r = ActivityMatcher.rank(listOf(a("in", 600, 1_800)), sessionStart, sessionEnd)
        assertEquals("in", r.single().activity.id)
        assertEquals(1_800_000L, r.single().overlapMs)
    }

    @Test fun `session fully inside the activity matches with the session length`() {
        val r = ActivityMatcher.rank(listOf(a("big", -600, 7_200)), sessionStart, sessionEnd)
        assertEquals(3_600_000L, r.single().overlapMs)
    }

    @Test fun `partial overlaps on either side are measured`() {
        val before = ActivityMatcher.rank(listOf(a("b", -1_800, 3_600)), sessionStart, sessionEnd).single()
        val after = ActivityMatcher.rank(listOf(a("a", 1_800, 3_600)), sessionStart, sessionEnd).single()
        assertEquals(1_800_000L, before.overlapMs)
        assertEquals(1_800_000L, after.overlapMs)
    }

    @Test fun `below the minimum overlap is dropped and at it is kept`() {
        assertNull(ActivityMatcher.pick(listOf(a("short", 3_600 - 299, 3_600)), sessionStart, sessionEnd))
        assertEquals("edge", ActivityMatcher.pick(listOf(a("edge", 3_600 - 300, 3_600)), sessionStart, sessionEnd)!!.id)
    }

    @Test fun `no elapsed time never matches automatically`() {
        assertNull(ActivityMatcher.pick(listOf(a("nolen", 0, null)), sessionStart, sessionEnd))
    }

    @Test fun `largest overlap wins regardless of device`() {
        val r = ActivityMatcher.rank(
            listOf(a("amaz", 0, 900, source = "ZEPP", device = "Amazfit Cheetah 2 Ultra"), a("tpv", 0, 3_600)),
            sessionStart, sessionEnd,
        )
        assertEquals(listOf("tpv", "amaz"), r.map { it.activity.id })
        assertEquals("tpv", ActivityMatcher.pick(r.map { it.activity }, sessionStart, sessionEnd)!!.id)
    }

    @Test fun `equal overlap resolves to the earliest start`() {
        val r = ActivityMatcher.rank(listOf(a("later", 60, 1_800), a("earlier", 0, 1_800)), sessionStart, sessionEnd)
        assertEquals(listOf("earlier", "later"), r.map { it.activity.id })
    }

    @Test fun `strava sourced activities are never picked`() {
        assertNull(ActivityMatcher.pick(listOf(a("s", 0, 3_600, source = "STRAVA")), sessionStart, sessionEnd))
        assertNull(ActivityMatcher.pick(listOf(a("s", 0, 3_600, source = "strava")), sessionStart, sessionEnd))
    }

    @Test fun `karoo recordings are never picked because the karoo records breathing itself`() {
        assertNull(ActivityMatcher.pick(listOf(a("k", 0, 3_600, source = "UPLOAD", device = "Hammerhead Karoo 3")), sessionStart, sessionEnd))
    }

    @Test fun `activity entirely outside the session has zero overlap`() {
        assertNull(ActivityMatcher.pick(listOf(a("gone", 7_200, 3_600)), sessionStart, sessionEnd))
    }
}
