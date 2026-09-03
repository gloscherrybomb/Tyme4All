package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.session.SeriesBuilder
import com.tymewear.run.domain.session.SessionMeta
import com.tymewear.run.domain.session.SessionStore
import java.time.Instant

sealed class SyncOutcome {
    data class Synced(val activityId: String, val updated: List<String>) : SyncOutcome()
    data object NotYet : SyncOutcome()
    data class Skipped(val reason: String) : SyncOutcome()
    data class Failed(val message: String) : SyncOutcome()
}

class SyncEngine(private val api: IntervalsApi, private val store: SessionStore) {

    fun sync(sessionId: String, settings: Settings, nowMs: Long): SyncOutcome = run(sessionId, settings, nowMs) { meta ->
        val start = Instant.ofEpochMilli(meta.startMs)
        val end = Instant.ofEpochMilli(meta.endMs!!)
        // Intervals.icu interprets oldest/newest in the athlete's local zone, not UTC, so a
        // narrow UTC-based window can clip an activity near a zone boundary. Widen it to match
        // syncTo's ±24 h window; the real selection happens in ActivityMatcher's ±5 minute check.
        val candidates = api.listActivities(start.minusSeconds(86_400), end.plusSeconds(86_400))
        ActivityMatcher.pick(candidates, meta.startMs)
    }

    fun syncTo(sessionId: String, activityId: String, settings: Settings, nowMs: Long): SyncOutcome = run(sessionId, settings, nowMs) { meta ->
        val start = Instant.ofEpochMilli(meta.startMs)
        api.listActivities(start.minusSeconds(86_400), start.plusSeconds(86_400)).firstOrNull { it.id == activityId }
            ?: throw IntervalsException(404, "activity $activityId not found near the session")
    }

    private fun run(sessionId: String, settings: Settings, nowMs: Long, choose: (SessionMeta) -> ActivitySummary?): SyncOutcome {
        val meta = store.meta(sessionId) ?: return SyncOutcome.Failed("unknown session")
        fun record(state: String, msg: String, activityId: String? = meta.activityId) =
            store.updateMeta(sessionId) { it.copy(syncState = state, syncMessage = msg, lastSyncAttemptMs = nowMs, activityId = activityId) }

        if (settings.intervalsApiKey.isNullOrBlank()) { record("skipped", "no api key"); return SyncOutcome.Skipped("no api key") }
        val end = meta.endMs ?: run { record("pending", "session still open"); return SyncOutcome.NotYet }
        if (end - meta.startMs < 60_000) { record("skipped", "session shorter than 60 s"); return SyncOutcome.Skipped("session shorter than 60 s") }

        return try {
            val activity = choose(meta) ?: run { record("pending", "no matching activity yet"); return SyncOutcome.NotYet }
            val streams = api.getStreams(activity.id, listOf("time", "heartrate"))
            val time = streams.firstOrNull { it.type == "time" }?.data
            if (time.isNullOrEmpty()) { record("failed", "activity has no time stream", activity.id); return SyncOutcome.Failed("activity has no time stream") }
            val hr = streams.firstOrNull { it.type == "heartrate" }?.data
            val series = SeriesBuilder.build(store.events(sessionId), settings.thresholds)
            val aligned = StreamAligner.align(time, hr, activity.startDate, series, settings.reserve)
            val result = api.putStreams(activity.id, aligned)
            val missing = StreamCodes.ALL - result.updated.toSet()
            if (missing.isNotEmpty()) {
                val msg = "streams not accepted: ${missing.joinToString(",")}"
                record("failed", msg, activity.id); SyncOutcome.Failed(msg)
            } else {
                record("synced", "pushed ${result.updated.size} streams", activity.id)
                SyncOutcome.Synced(activity.id, result.updated)
            }
        } catch (e: IntervalsException) {
            record("failed", e.message ?: "intervals error"); SyncOutcome.Failed(e.message ?: "intervals error")
        } catch (e: java.io.IOException) {
            record("pending", "network: ${e.message}"); SyncOutcome.NotYet
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "unknown error"
            record("failed", msg); SyncOutcome.Failed(msg)
        }
    }
}
