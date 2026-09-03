package com.tymewear.run.domain.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityMatcherTest {
    private val start = Instant.parse("2026-09-03T07:10:00Z")
    private fun a(id: String, offsetSec: Long, device: String? = "Amazfit Cheetah 2 Ultra", source: String? = "ZEPP") =
        ActivitySummary(id, start.plusSeconds(offsetSec), "Run", "Run", source, device)

    @Test fun `closest within window wins`() {
        val pick = ActivityMatcher.pick(listOf(a("far", 200), a("near", 30)), start.toEpochMilli())
        assertEquals("near", pick!!.id)
    }
    @Test fun `outside window is ignored`() {
        assertNull(ActivityMatcher.pick(listOf(a("late", 301)), start.toEpochMilli()))
        assertEquals("edge", ActivityMatcher.pick(listOf(a("edge", -300)), start.toEpochMilli())!!.id)
    }
    @Test fun `amazfit beats another device even if further`() {
        val pick = ActivityMatcher.pick(listOf(a("karoo", 5, device = "Hammerhead Karoo", source = "UPLOAD"), a("amaz", 60)), start.toEpochMilli())
        assertEquals("amaz", pick!!.id)
    }
    @Test fun `strava sourced activities are never picked`() {
        assertNull(ActivityMatcher.pick(listOf(a("s", 0, device = null, source = "STRAVA")), start.toEpochMilli()))
    }
}
