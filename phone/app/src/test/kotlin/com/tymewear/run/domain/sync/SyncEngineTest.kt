package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.session.SessionEvent
import com.tymewear.run.domain.session.SessionStore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeApi : IntervalsApi {
        var activities = listOf<ActivitySummary>()
        var streams = listOf<Stream>()
        var putResult = UpdateStreamsResult(StreamCodes.ALL, emptyList())
        var lastPut: List<Stream>? = null
        var lastPutId: String? = null
        var fail: IntervalsException? = null
        var failListActivities: Exception? = null
        var failGetStreams: Exception? = null
        override fun listActivities(oldest: Instant, newest: Instant) =
            fail?.let { throw it } ?: failListActivities?.let { throw it } ?: activities
        override fun getStreams(activityId: String, types: List<String>) = failGetStreams?.let { throw it } ?: streams
        override fun putStreams(activityId: String, streams: List<Stream>): UpdateStreamsResult { lastPutId = activityId; lastPut = streams; return putResult }
        override fun verifyKey() = true
    }

    private val startMs = 1_788_419_400_000L   // 2026-09-03T07:10:00Z
    private val settings = Settings.DEFAULT.copy(intervalsApiKey = "k")

    private fun session(store: SessionStore, lengthMs: Long = 600_000): String {
        val log = store.create(startMs, "watch")
        log.append(SessionEvent.Breath(startMs + 5_000, 20.0, 2.0, 1.0, 200, 100, 100, 1))
        log.close()
        val id = SessionStore.idFor(startMs)
        store.finish(id, startMs + lengthMs, "watch")
        return id
    }

    @Test fun `skips without api key`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val out = SyncEngine(FakeApi(), store).sync(id, Settings.DEFAULT, startMs + 700_000)
        assertEquals(SyncOutcome.Skipped("no api key"), out)
        assertEquals("skipped", store.meta(id)!!.syncState)
    }

    @Test fun `not yet when no activity matches`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val out = SyncEngine(FakeApi(), store).sync(id, settings, startMs + 700_000)
        assertEquals(SyncOutcome.NotYet, out)
        assertEquals("pending", store.meta(id)!!.syncState)
        assertEquals(startMs + 700_000, store.meta(id)!!.lastSyncAttemptMs)
    }

    @Test fun `syncs and records activity id`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply {
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs + 3_000), "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra"))
            streams = listOf(Stream("time", listOf(0.0, 1.0, 2.0, 3.0)), Stream("heartrate", listOf(120.0, 121.0, 122.0, 123.0)))
        }
        val out = SyncEngine(api, store).sync(id, settings, startMs + 700_000)
        assertTrue(out is SyncOutcome.Synced)
        assertEquals("i1", api.lastPutId)
        assertEquals(7, api.lastPut!!.size)
        // activity started at +3s; series has a breath at +5s, so activity second 2 (=+5s) carries VE 40
        val ve = api.lastPut!!.first { it.type == StreamCodes.VE }.data
        assertEquals(listOf(null, null, 40.0, 40.0), ve)
        assertEquals("synced", store.meta(id)!!.syncState)
        assertEquals("i1", store.meta(id)!!.activityId)
    }

    @Test fun `failed when intervals rejects a stream`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply {
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs), null, "Run", "ZEPP", null))
            streams = listOf(Stream("time", listOf(0.0, 1.0)))
            putResult = UpdateStreamsResult(StreamCodes.ALL - StreamCodes.MI, emptyList())
        }
        val out = SyncEngine(api, store).sync(id, settings, startMs + 700_000)
        assertEquals(SyncOutcome.Failed("streams not accepted: tyme_mobilization_index"), out)
        assertEquals("failed", store.meta(id)!!.syncState)
    }

    @Test fun `api error becomes failed`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { fail = IntervalsException(401, "HTTP 401") }
        assertTrue(SyncEngine(api, store).sync(id, settings, startMs + 700_000) is SyncOutcome.Failed)
        assertEquals("failed", store.meta(id)!!.syncState)
    }

    @Test fun `short sessions are skipped`() {
        val store = SessionStore(tmp.root); val id = session(store, lengthMs = 30_000)
        assertEquals(SyncOutcome.Skipped("session shorter than 60 s"), SyncEngine(FakeApi(), store).sync(id, settings, startMs + 700_000))
    }

    @Test fun `unexpected exception becomes failed and does not propagate`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply {
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs + 3_000), "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra"))
            failGetStreams = IllegalStateException("bad json")
        }
        val out = SyncEngine(api, store).sync(id, settings, startMs + 700_000)
        assertEquals(SyncOutcome.Failed("bad json"), out)
        assertEquals("failed", store.meta(id)!!.syncState)
    }

    @Test fun `offline io exception leaves session pending for the next attempt`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { failListActivities = java.io.IOException("offline") }
        val out = SyncEngine(api, store).sync(id, settings, startMs + 700_000)
        assertEquals(SyncOutcome.NotYet, out)
        assertEquals("pending", store.meta(id)!!.syncState)
    }

    @Test fun `manual match skips the matcher`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { streams = listOf(Stream("time", listOf(0.0))) }
        // activity start is needed for alignment: syncTo fetches it from listActivities by id
        api.activities = listOf(ActivitySummary("i9", Instant.ofEpochMilli(startMs + 7_200_000 + 1), null, "Run", "ZEPP", null))
        val out = SyncEngine(api, store).syncTo(id, "i9", settings, startMs + 700_000)
        assertTrue(out is SyncOutcome.Synced)
        assertEquals("i9", api.lastPutId)
    }
}
