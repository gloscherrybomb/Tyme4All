package com.tymewear.run.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `id is utc timestamp`() {
        assertEquals("20260903-071000", SessionStore.idFor(1788419400000L))
    }

    @Test
    fun `create finish list and read back`() {
        val store = SessionStore(tmp.root)
        val log = store.create(startMs = 1788419400000L, source = "watch")
        log.append(SessionEvent.Breath(1788419401000L, 20.0, 1.5, 1.0, 150, 150, 150, 10))
        log.close()
        store.finish("20260903-071000", endMs = 1788423000000L, reason = "watch")

        val metas = store.list()
        assertEquals(1, metas.size)
        assertEquals("20260903-071000", metas[0].id)
        assertEquals(1788423000000L, metas[0].endMs)
        assertEquals("pending", metas[0].syncState)

        val events = store.events("20260903-071000")
        assertEquals(3, events.size)
        assertEquals(SessionEvent.Stop(1788423000000L, "watch"), events.last())
    }

    @Test
    fun `list is newest first and meta updates persist`() {
        val store = SessionStore(tmp.root)
        store.create(1_000_000_000_000L, "manual").close()
        store.create(1_100_000_000_000L, "manual").close()
        assertEquals(listOf(SessionStore.idFor(1_100_000_000_000L), SessionStore.idFor(1_000_000_000_000L)), store.list().map { it.id })
        store.updateMeta(SessionStore.idFor(1_000_000_000_000L)) { it.copy(syncState = "synced", activityId = "i123") }
        assertEquals("i123", store.meta(SessionStore.idFor(1_000_000_000_000L))!!.activityId)
    }

    @Test
    fun `prune removes sessions older than retention`() {
        val store = SessionStore(tmp.root)
        val day = 86_400_000L
        val now = 2_000_000_000_000L
        store.create(now - 100 * day, "manual").close()
        store.create(now - 10 * day, "manual").close()
        assertEquals(1, store.prune(now, retentionDays = 90))
        assertEquals(1, store.list().size)
        assertNull(store.meta(SessionStore.idFor(now - 100 * day)))
    }

    @Test
    fun `discardPending marks only finished pending sessions`() {
        val store = SessionStore(tmp.root)
        store.create(1_000, "strap").close(); store.finish(SessionStore.idFor(1_000), 100_000, "idle")          // pending, finished
        store.create(200_000, "strap").close()                                                                  // still open
        store.create(300_000, "strap").close(); store.finish(SessionStore.idFor(300_000), 400_000, "idle")
        store.updateMeta(SessionStore.idFor(300_000)) { it.copy(syncState = "synced", activityId = "i1") }

        val discarded = store.discardPending(nowMs = 500_000)

        assertEquals(listOf(SessionStore.idFor(1_000)), discarded)
        val m = store.meta(SessionStore.idFor(1_000))!!
        assertEquals("discarded", m.syncState)
        assertEquals("discarded by user", m.syncMessage)
        assertEquals(500_000L, m.lastSyncAttemptMs)
        assertEquals("pending", store.meta(SessionStore.idFor(200_000))!!.syncState)
        assertEquals("synced", store.meta(SessionStore.idFor(300_000))!!.syncState)
    }
}
