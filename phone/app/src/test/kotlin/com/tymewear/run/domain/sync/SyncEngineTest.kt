package com.tymewear.run.domain.sync

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.ZoneThresholds
import com.tymewear.run.domain.session.SessionEvent
import com.tymewear.run.domain.session.SessionStore
import com.tymewear.run.domain.tymewear.TymewearOutcome
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        var original = ByteArray(0)
        override fun originalFile(activityId: String) = original
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
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs + 3_000), "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 600))
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
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs), null, "Run", "ZEPP", null, 600))
            streams = listOf(Stream("time", listOf(0.0, 1.0)))
            putResult = UpdateStreamsResult(StreamCodes.ALL - StreamCodes.MI, emptyList())
        }
        val out = SyncEngine(api, store).sync(id, settings, startMs + 700_000)
        assertEquals(SyncOutcome.Failed("streams not accepted: ${StreamCodes.MI}"), out)
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
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs + 3_000), "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 600))
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
        api.activities = listOf(ActivitySummary("i9", Instant.ofEpochMilli(startMs + 7_200_000 + 1), null, "Run", "ZEPP", null, 600))
        val out = SyncEngine(api, store).syncTo(id, "i9", settings, startMs + 700_000)
        assertTrue(out is SyncOutcome.Synced)
        assertEquals("i9", api.lastPutId)
    }

    @Test fun `manual match to a Karoo activity fails without pushing`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply {
            streams = listOf(Stream("time", listOf(0.0)))
            activities = listOf(ActivitySummary("k1", Instant.ofEpochMilli(startMs), "Ride", "Ride", "HAMMERHEAD", "Hammerhead Karoo 3", 600))
        }
        val out = SyncEngine(api, store).syncTo(id, "k1", settings, startMs + 700_000)
        assertEquals(SyncOutcome.Failed("activity k1 was recorded by a Karoo, which records breathing itself"), out)
        assertEquals("failed", store.meta(id)!!.syncState)
        assertNull(api.lastPutId)
    }

    @Test fun `unnamed activity is labelled by type and local start time`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val start = Instant.ofEpochMilli(startMs + 3_000)
        val api = FakeApi().apply {
            activities = listOf(ActivitySummary("i1", start, null, "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 600))
            streams = listOf(Stream("time", listOf(0.0, 1.0)))
        }
        val out = SyncEngine(api, store).sync(id, settings, startMs + 700_000) as SyncOutcome.Synced
        val hhmm = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(start.atZone(java.time.ZoneId.systemDefault()))
        assertEquals("Run at $hhmm", out.activityLabel)
    }

    @Test fun `runner up activity is named in the sync message`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply {
            activities = listOf(
                ActivitySummary("tpv", Instant.ofEpochMilli(startMs), "Ride", "Ride", "TPV", null, 600),
                ActivitySummary("amaz", Instant.ofEpochMilli(startMs + 60_000), "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 540),
            )
            streams = listOf(Stream("time", listOf(0.0, 1.0)))
        }
        val out = SyncEngine(api, store).sync(id, settings, startMs + 700_000)
        assertTrue(out is SyncOutcome.Synced)
        assertEquals("tpv", api.lastPutId)
        assertEquals("pushed 7 streams to tpv; also overlapped amaz (Amazfit Cheetah 2 Ultra)", store.meta(id)!!.syncMessage)
        assertEquals("Ride", (out as SyncOutcome.Synced).activityLabel)
    }

    @Test fun `a second session for an already synced activity carries the first session's data too`() {
        val store = SessionStore(tmp.root)
        val a = session(store, lengthMs = 320_000)                       // breath at +5 s, VE 40
        val bStart = startMs + 330_000
        store.create(bStart, "strap").apply {
            append(SessionEvent.Breath(startMs + 340_000, 20.0, 3.0, 1.0, 300, 100, 100, 1)); close()   // VE 60
        }
        val b = SessionStore.idFor(bStart)
        store.finish(b, startMs + 700_000, "idle")

        val api = FakeApi().apply {
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs), "Ride", "Ride", "TPV", null, 900))
            streams = listOf(Stream("time", listOf(0.0, 5.0, 340.0, 800.0)))
        }
        val engine = SyncEngine(api, store)
        assertTrue(engine.sync(a, settings, startMs + 1_000_000) is SyncOutcome.Synced)
        assertEquals(listOf(null, 40.0, null, null), api.lastPut!!.first { it.type == StreamCodes.VE }.data)

        assertTrue(engine.sync(b, settings, startMs + 1_000_000) is SyncOutcome.Synced)
        assertEquals(listOf(null, 40.0, 60.0, null), api.lastPut!!.first { it.type == StreamCodes.VE }.data)
        assertEquals("i1", store.meta(b)!!.activityId)
    }

    /** Two sessions, A then B, that both overlap activity i1. Returns (a, b). */
    private fun twoSessions(store: SessionStore): Pair<String, String> {
        val a = session(store, lengthMs = 320_000)                       // breath at +5 s, VE 40
        val bStart = startMs + 330_000
        store.create(bStart, "strap").apply {
            append(SessionEvent.Breath(startMs + 340_000, 20.0, 3.0, 1.0, 300, 100, 100, 1)); close()   // VE 60
        }
        val b = SessionStore.idFor(bStart)
        store.finish(b, startMs + 700_000, "idle")
        return a to b
    }

    @Test fun `merge keeps a sibling whose later re-sync failed`() {
        val store = SessionStore(tmp.root)
        val (a, b) = twoSessions(store)
        val api = FakeApi().apply {
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs), "Ride", "Ride", "TPV", null, 900))
            streams = listOf(Stream("time", listOf(0.0, 5.0, 340.0, 800.0)))
        }
        val engine = SyncEngine(api, store)
        assertTrue(engine.sync(a, settings, startMs + 1_000_000) is SyncOutcome.Synced)
        // A's data is on the activity; a later retry of A failed but it still targets i1.
        store.updateMeta(a) { it.copy(syncState = "failed", syncMessage = "HTTP 500") }

        assertTrue(engine.sync(b, settings, startMs + 1_000_000) is SyncOutcome.Synced)
        assertEquals(listOf(null, 40.0, 60.0, null), api.lastPut!!.first { it.type == StreamCodes.VE }.data)
    }

    @Test fun `merge ignores a sibling session synced to a different activity`() {
        val store = SessionStore(tmp.root)
        val (a, b) = twoSessions(store)
        store.updateMeta(a) { it.copy(syncState = "synced", activityId = "other") }
        val api = FakeApi().apply {
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs), "Ride", "Ride", "TPV", null, 900))
            streams = listOf(Stream("time", listOf(0.0, 5.0, 340.0, 800.0)))
        }
        assertTrue(SyncEngine(api, store).sync(b, settings, startMs + 1_000_000) is SyncOutcome.Synced)
        assertEquals(listOf(null, null, 60.0, null), api.lastPut!!.first { it.type == StreamCodes.VE }.data)
    }

    private val twSettings = settings.copy(tymewearSignedIn = true)
    private val zeppRun = ActivitySummary("i1", Instant.ofEpochMilli(startMs + 3_000), "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 600)
    private val twoRows = listOf(Stream("time", listOf(0.0, 1.0, 2.0, 3.0)), Stream("heartrate", listOf(120.0, 121.0, 122.0, 123.0)))

    @Test fun `uploads to Tymewear after a successful push`() {
        val store = SessionStore(tmp.root); val id = session(store)
        var seen: List<Stream>? = null
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        val out = SyncEngine(api, store) { _, _, s -> seen = s; TymewearOutcome.Uploaded }.sync(id, twSettings, startMs + 700_000)
        assertEquals(TymewearOutcome.Uploaded, (out as SyncOutcome.Synced).tymewear)
        assertEquals(api.lastPut, seen)
        assertEquals("synced", store.meta(id)!!.tymewearState)
    }

    @Test fun `a Tymewear failure leaves the intervals result synced`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        val out = SyncEngine(api, store) { _, _, _ -> throw RuntimeException("boom") }.sync(id, twSettings, startMs + 700_000)
        assertTrue(out is SyncOutcome.Synced)
        assertEquals("synced", store.meta(id)!!.syncState)
        assertEquals("failed", store.meta(id)!!.tymewearState)
    }

    @Test fun `a refused sign-in leaves the Tymewear step pending for after the next sign-in`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        val refused = TymewearOutcome.Failed("Tymewear stopped accepting the saved sign-in", auth = true)
        val out = SyncEngine(api, store) { _, _, _ -> refused }.sync(id, twSettings, startMs + 700_000)
        assertEquals(refused, (out as SyncOutcome.Synced).tymewear)
        assertEquals("synced", store.meta(id)!!.syncState)
        assertEquals("pending", store.meta(id)!!.tymewearState)
        assertEquals("Tymewear stopped accepting the sign-in", store.meta(id)!!.tymewearMessage)
        assertEquals(listOf(id), SyncScheduler.dueTymewear(store.list(), startMs + 700_000 + 120_000).map { it.id })
    }

    @Test fun `a Tymewear rejection that is not about the sign-in is failed`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        SyncEngine(api, store) { _, _, _ -> TymewearOutcome.Failed("Tymewear rejected the file (400)") }.sync(id, twSettings, startMs + 700_000)
        assertEquals("failed", store.meta(id)!!.tymewearState)
        assertEquals("Tymewear rejected the file (400)", store.meta(id)!!.tymewearMessage)
    }

    @Test fun `no Tymewear state when not signed in`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        SyncEngine(api, store) { _, _, _ -> throw AssertionError("must not upload") }.sync(id, settings, startMs + 700_000)
        assertNull(store.meta(id)!!.tymewearState)
    }

    @Test fun `no Tymewear step while the sign-in is refused`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        val refused = twSettings.copy(tymewearSignInRefused = true)
        val engine = SyncEngine(api, store) { _, _, _ -> throw AssertionError("must not upload") }
        assertTrue(engine.sync(id, refused, startMs + 700_000) is SyncOutcome.Synced)
        assertNull(store.meta(id)!!.tymewearState)
        assertNull(engine.syncTymewear(id, refused, startMs + 900_000))
    }

    @Test fun `retrying Tymewear never pushes to intervals again`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        SyncEngine(api, store) { _, _, _ -> TymewearOutcome.NotYet }.sync(id, twSettings, startMs + 700_000)
        assertEquals("pending", store.meta(id)!!.tymewearState)
        api.lastPut = null
        val again = SyncEngine(api, store) { _, _, _ -> TymewearOutcome.Uploaded }.syncTymewear(id, twSettings, startMs + 900_000)
        assertEquals(TymewearOutcome.Uploaded, again)
        assertNull(api.lastPut)
        assertEquals("synced", store.meta(id)!!.tymewearState)
    }

    @Test fun `zones use the activity's sport thresholds`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        // VE 40 is Z1 under the default thresholds and Z4 (VT2 30 to Top Z4 45) under these run thresholds.
        val s = twSettings.copy(runThresholds = ZoneThresholds(10.0, 20.0, 30.0, 45.0, 60.0))
        SyncEngine(api, store).sync(id, s, startMs + 700_000)
        assertEquals(4.0, api.lastPut!!.first { it.type == StreamCodes.ZONE }.data[2])
    }

    @Test fun `retrying Tymewear does nothing for a session not synced to intervals`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        val out = SyncEngine(api, store) { _, _, _ -> throw AssertionError("must not upload") }.syncTymewear(id, twSettings, startMs + 700_000)
        assertNull(out)
        assertNull(store.meta(id)!!.tymewearState)
    }

    @Test fun `a Tymewear failure records only the class name`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        SyncEngine(api, store) { _, _, _ -> throw IllegalStateException("reply: secret") }.sync(id, twSettings, startMs + 700_000)
        assertEquals("unexpected error: IllegalStateException", store.meta(id)!!.tymewearMessage)
    }

    @Test fun `a store error while recording the Tymewear step leaves the intervals result synced`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        val metaFile = java.io.File(tmp.root, "$id.meta.json")
        // The synced record is written before the step runs; locking the file then makes the step's write throw an IOException.
        val out = SyncEngine(api, store) { _, _, _ -> assertTrue(metaFile.setWritable(false)); TymewearOutcome.Uploaded }
            .sync(id, twSettings, startMs + 700_000)
        metaFile.setWritable(true)
        assertTrue(out is SyncOutcome.Synced)
        assertEquals(TymewearOutcome.Uploaded, (out as SyncOutcome.Synced).tymewear)
        assertEquals("synced", store.meta(id)!!.syncState)
        assertNull(store.meta(id)!!.tymewearState)
    }

    @Test fun `alignment uses the Tymewear reserve when signed in`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
        // BR 20 with resting 10 and max 30 is a 50 % breathing reserve.
        val tymewearReserve = ReserveSettings(restingBr = 10.0, maxBr = 30.0, restingHr = 50.0, maxHr = 190.0)
        assertNotEquals(tymewearReserve, twSettings.reserve)
        SyncEngine(api, store).sync(id, twSettings.copy(tymewearReserve = tymewearReserve), startMs + 700_000)
        assertEquals(50.0, api.lastPut!!.first { it.type == StreamCodes.BRR }.data[2]!!, 1e-9)
    }
}
