package com.tymewear.run.domain.sync

import com.tymewear.run.domain.session.SessionMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {
    private val m = SessionMeta(id = "a", startMs = 0, endMs = 1_000)

    @Test fun `ended pending sessions are due when never attempted`() {
        assertEquals(listOf(m), SyncScheduler.due(listOf(m), nowMs = 2_000))
    }
    @Test fun `open sessions and non pending are not due`() {
        assertEquals(emptyList<SessionMeta>(), SyncScheduler.due(listOf(m.copy(endMs = null), m.copy(syncState = "synced"), m.copy(syncState = "failed")), 2_000))
    }
    @Test fun `respects the interval since last attempt`() {
        val tried = m.copy(lastSyncAttemptMs = 10_000)
        assertEquals(emptyList<SessionMeta>(), SyncScheduler.due(listOf(tried), 10_000 + 119_999))
        assertEquals(listOf(tried), SyncScheduler.due(listOf(tried), 10_000 + 120_000))
    }
    @Test fun `gives up after the window`() {
        val old = m.copy(endMs = 1_000)
        val now = 1_000 + 6 * 3_600_000L + 1
        assertEquals(emptyList<SessionMeta>(), SyncScheduler.due(listOf(old), now))
        assertEquals(listOf(old), SyncScheduler.expired(listOf(old), now))
        assertEquals(emptyList<SessionMeta>(), SyncScheduler.expired(listOf(old.copy(syncState = "synced")), now))
    }
    @Test fun `anyPending is true for a finished pending session inside the window`() {
        assertEquals(true, SyncScheduler.anyPending(listOf(m), nowMs = 1_000 + 6 * 3_600_000L))
    }
    @Test fun `anyPending is false once the window has passed`() {
        assertEquals(false, SyncScheduler.anyPending(listOf(m), nowMs = 1_000 + 6 * 3_600_000L + 1))
    }
    @Test fun `anyPending ignores synced failed and skipped sessions`() {
        assertEquals(false, SyncScheduler.anyPending(listOf(m.copy(syncState = "synced"), m.copy(syncState = "failed"), m.copy(syncState = "skipped")), 2_000))
    }
    @Test fun `anyPending ignores an open session`() {
        assertEquals(false, SyncScheduler.anyPending(listOf(m.copy(endMs = null)), 2_000))
    }

    private val tw = m.copy(syncState = "synced", tymewearState = "pending")

    @Test fun `a synced session with Tymewear pending is due once 2 minutes have passed since its last Tymewear attempt`() {
        val tried = tw.copy(tymewearAttemptMs = 10_000)
        assertEquals(listOf(tried), SyncScheduler.dueTymewear(listOf(tried), 10_000 + 120_000))
        assertEquals(listOf(tw), SyncScheduler.dueTymewear(listOf(tw), 2_000))
    }
    @Test fun `a Tymewear pending session is not due within 2 minutes of its last attempt`() {
        val tried = tw.copy(tymewearAttemptMs = 10_000)
        assertEquals(emptyList<SessionMeta>(), SyncScheduler.dueTymewear(listOf(tried), 10_000 + 119_999))
    }
    @Test fun `only synced sessions with Tymewear pending are due for Tymewear`() {
        val others = listOf(tw.copy(syncState = "pending"), tw.copy(tymewearState = null), tw.copy(tymewearState = "failed"), tw.copy(tymewearState = "synced"), tw.copy(endMs = null))
        assertEquals(emptyList<SessionMeta>(), SyncScheduler.dueTymewear(others, 2_000))
    }
    @Test fun `Tymewear gives up 6 hours after the session ended`() {
        val now = 1_000 + 6 * 3_600_000L + 1
        assertEquals(emptyList<SessionMeta>(), SyncScheduler.dueTymewear(listOf(tw), now))
        assertEquals(listOf(tw), SyncScheduler.expiredTymewear(listOf(tw), now))
        assertEquals(emptyList<SessionMeta>(), SyncScheduler.expiredTymewear(listOf(tw), 1_000 + 6 * 3_600_000L))
        assertEquals(emptyList<SessionMeta>(), SyncScheduler.expiredTymewear(listOf(tw.copy(tymewearState = "synced")), now))
    }
    @Test fun `anyPending counts a Tymewear pending session inside the window when Tymewear is on`() {
        assertEquals(true, SyncScheduler.anyPending(listOf(tw), nowMs = 1_000 + 6 * 3_600_000L, tymewear = true))
        assertEquals(false, SyncScheduler.anyPending(listOf(tw), nowMs = 1_000 + 6 * 3_600_000L + 1, tymewear = true))
    }
    @Test fun `anyPending ignores Tymewear pending sessions when Tymewear is off`() {
        assertEquals(false, SyncScheduler.anyPending(listOf(tw), nowMs = 2_000))
        assertEquals(false, SyncScheduler.anyPending(listOf(tw), nowMs = 2_000, tymewear = false))
    }
    @Test fun `anyPending ignores Tymewear states other than pending`() {
        val done = listOf(tw.copy(tymewearState = "synced"), tw.copy(tymewearState = "failed"), tw.copy(tymewearState = "skipped"), tw.copy(tymewearState = null))
        assertEquals(false, SyncScheduler.anyPending(done, nowMs = 2_000, tymewear = true))
    }
}
