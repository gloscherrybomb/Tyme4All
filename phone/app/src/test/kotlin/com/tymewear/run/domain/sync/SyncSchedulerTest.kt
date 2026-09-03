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
}
