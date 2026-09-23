package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.sync.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TymewearUploaderTest {
    private val start = Instant.parse("2026-09-20T09:23:15Z")
    private val run = ActivitySummary("i1", start, "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 600)
    private val time = listOf(0.0, 1.0)
    private val streams = listOf(Stream(StreamCodes.VE, listOf(40.0, 41.0), true))

    private class FakeIntervals(var file: ByteArray, var error: Exception? = null) : IntervalsApi {
        override fun listActivities(oldest: Instant, newest: Instant) = emptyList<ActivitySummary>()
        override fun getStreams(activityId: String, types: List<String>) = emptyList<Stream>()
        override fun putStreams(activityId: String, streams: List<Stream>) = UpdateStreamsResult(emptyList(), emptyList())
        override fun verifyKey() = true
        override fun originalFile(activityId: String) = error?.let { throw it } ?: file
    }

    private class FakeTymewear : TymewearApi {
        var listed = listOf<TymewearActivity>()
        var details = mapOf<String, TymewearActivity>()
        var uploaded: Pair<Long, ByteArray>? = null
        var fail: Exception? = null
        override fun signIn(email: String, password: String) {}
        override fun profile() = fail?.let { throw it } ?: TymewearProfile(7)
        override fun activeThresholds(userId: Long, sport: String): com.tymewear.run.domain.ZoneThresholds? = null
        override fun restingMax(): ReserveSettings? = null
        override fun recentActivities(userId: Long, limit: Int) = listed
        override fun activity(id: String) = details.getValue(id)
        override fun replaceFile(tpId: Long, fit: ByteArray) { fail?.let { throw it }; uploaded = tpId to fit }
    }

    /** A real (tiny) FIT: records at the activity's first two seconds. */
    private fun fit(): ByteArray {
        val fitStart = start.epochSecond - 631_065_600L
        val e = com.garmin.fit.BufferEncoder(com.garmin.fit.Fit.ProtocolVersion.V2_0)
        e.write(com.garmin.fit.FileIdMesg().apply { type = com.garmin.fit.File.ACTIVITY })
        for (i in 0..1) e.write(com.garmin.fit.RecordMesg().apply { timestamp = com.garmin.fit.DateTime(fitStart + i) })
        return e.close()
    }

    private fun copyOf(activityStart: Instant, tpId: Long = 972545) =
        TymewearActivity("a1", activityStart.plusSeconds(700), listOf(PartnerCopy(tpId, "INTERVALS_ICU", activityStart)))

    @Test fun `finds Tymewear's copy by start time and uploads the merged file`() {
        val tw = FakeTymewear().apply { listed = listOf(copyOf(start).copy(partnerCopies = emptyList())); details = mapOf("a1" to copyOf(start)) }
        val out = TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams)
        assertEquals(TymewearOutcome.Uploaded, out)
        assertEquals(972545L, tw.uploaded!!.first)
        assertTrue(tw.uploaded!!.second.size > fit().size)
    }

    @Test fun `not yet when Tymewear has no copy`() {
        val tw = FakeTymewear()
        assertEquals(TymewearOutcome.NotYet, TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams))
        assertNull(tw.uploaded)
    }

    @Test fun `ignores a copy from another partner or another start`() {
        val other = TymewearActivity("a2", start.plusSeconds(700), listOf(PartnerCopy(1, "GARMIN", start), PartnerCopy(2, "INTERVALS_ICU", start.plusSeconds(3600))))
        val tw = FakeTymewear().apply { listed = listOf(other); details = mapOf("a2" to other) }
        assertEquals(TymewearOutcome.NotYet, TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams))
    }

    @Test fun `skips Karoo rides and non FIT originals`() {
        val karoo = run.copy(deviceName = "HAMMERHEAD Karoo")
        assertTrue(TymewearUploader(FakeIntervals(fit()), FakeTymewear()).upload(karoo, time, streams) is TymewearOutcome.Skipped)
        val tw = FakeTymewear().apply { listed = listOf(copyOf(start)); details = mapOf("a1" to copyOf(start)) }
        assertTrue(TymewearUploader(FakeIntervals("<gpx/>".toByteArray()), tw).upload(run, time, streams) is TymewearOutcome.Skipped)
        assertNull(tw.uploaded)
    }

    @Test fun `auth failure is a failed outcome marked auth`() {
        val tw = FakeTymewear().apply { fail = TymewearAuthException("Tymewear stopped accepting the saved sign-in") }
        assertEquals(TymewearOutcome.Failed("Tymewear stopped accepting the saved sign-in", auth = true),
            TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams))
    }

    @Test fun `a refused upload is failed with the reason`() {
        val tw = FakeTymewear().apply { listed = listOf(copyOf(start)); details = mapOf("a1" to copyOf(start)) }
        tw.fail = null
        val refusing = object : TymewearApi by tw { override fun replaceFile(tpId: Long, fit: ByteArray) { throw TymewearException(400, "Tymewear refused the file (HTTP 400)") } }
        assertEquals(TymewearOutcome.Failed("Tymewear refused the file (HTTP 400)"), TymewearUploader(FakeIntervals(fit()), refusing).upload(run, time, streams))
    }

    @Test fun `network errors mean try again`() {
        val tw = FakeTymewear().apply { fail = java.io.IOException("timeout") }
        assertEquals(TymewearOutcome.NotYet, TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams))
    }

    private fun found() = FakeTymewear().apply { listed = listOf(copyOf(start)); details = mapOf("a1" to copyOf(start)) }

    @Test fun `a download the server could not serve means try again`() {
        val tw = found()
        assertEquals(TymewearOutcome.NotYet, TymewearUploader(FakeIntervals(fit(), IntervalsException(503, "HTTP 503")), tw).upload(run, time, streams))
        assertEquals(TymewearOutcome.NotYet, TymewearUploader(FakeIntervals(fit(), IntervalsException(429, "HTTP 429")), tw).upload(run, time, streams))
        assertNull(tw.uploaded)
    }

    @Test fun `a download refused by Intervals is failed`() {
        val out = TymewearUploader(FakeIntervals(fit(), IntervalsException(404, "HTTP 404")), found()).upload(run, time, streams)
        assertTrue(out is TymewearOutcome.Failed && !out.auth)
    }

    @Test fun `a corrupt gzip original is skipped, not retried`() {
        val out = TymewearUploader(FakeIntervals(fit(), java.util.zip.ZipException("Not in GZIP format")), found()).upload(run, time, streams)
        assertEquals(TymewearOutcome.Skipped("the activity's original file could not be read"), out)
    }

    @Test fun `an unexpected error is failed with the class name only`() {
        val tw = FakeTymewear().apply { fail = IllegalStateException("reply said fake-token") }
        assertEquals(TymewearOutcome.Failed("unexpected error: IllegalStateException"), TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams))
    }
}
