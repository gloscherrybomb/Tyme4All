package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.session.SeriesBuilder
import com.tymewear.run.domain.session.SessionMeta
import com.tymewear.run.domain.session.SessionStore
import java.time.Instant

sealed class SyncOutcome {
    data class Synced(val activityId: String, val activityLabel: String, val updated: List<String>) : SyncOutcome()
    data object NotYet : SyncOutcome()
    data class Skipped(val reason: String) : SyncOutcome()
    data class Failed(val message: String) : SyncOutcome()
}

/** The activity to push to, and the next-best overlapping activity if there was one. */
private data class Choice(val activity: ActivitySummary, val runnerUp: ActivitySummary?)

class SyncEngine(private val api: IntervalsApi, private val store: SessionStore) {

    fun sync(sessionId: String, settings: Settings, nowMs: Long): SyncOutcome = run(sessionId, settings, nowMs) { meta ->
        val start = Instant.ofEpochMilli(meta.startMs)
        val end = Instant.ofEpochMilli(meta.endMs!!)
        // Intervals.icu interprets oldest/newest in the athlete's local zone, not UTC, so a
        // narrow UTC-based window can clip an activity near a zone boundary. Widen it to ±24 h;
        // the real selection happens in ActivityMatcher's overlap ranking.
        val candidates = api.listActivities(start.minusSeconds(86_400), end.plusSeconds(86_400))
        val ranked = ActivityMatcher.rank(candidates, meta.startMs, meta.endMs)
        ranked.firstOrNull()?.let { Choice(it.activity, ranked.getOrNull(1)?.activity) }
    }

    fun syncTo(sessionId: String, activityId: String, settings: Settings, nowMs: Long): SyncOutcome = run(sessionId, settings, nowMs) { meta ->
        val start = Instant.ofEpochMilli(meta.startMs)
        val found = api.listActivities(start.minusSeconds(86_400), start.plusSeconds(86_400)).firstOrNull { it.id == activityId }
            ?: throw IntervalsException(404, "activity $activityId not found near the session")
        Choice(found, null)
    }

    private fun run(sessionId: String, settings: Settings, nowMs: Long, choose: (SessionMeta) -> Choice?): SyncOutcome {
        val meta = store.meta(sessionId) ?: return SyncOutcome.Failed("unknown session")
        fun record(state: String, msg: String, activityId: String? = meta.activityId) =
            store.updateMeta(sessionId) { it.copy(syncState = state, syncMessage = msg, lastSyncAttemptMs = nowMs, activityId = activityId) }

        if (settings.intervalsApiKey.isNullOrBlank()) { record("skipped", "no api key"); return SyncOutcome.Skipped("no api key") }
        val end = meta.endMs ?: run { record("pending", "session still open"); return SyncOutcome.NotYet }
        if (end - meta.startMs < 60_000) { record("skipped", "session shorter than 60 s"); return SyncOutcome.Skipped("session shorter than 60 s") }

        return try {
            val choice = choose(meta) ?: run { record("pending", "no overlapping activity yet"); return SyncOutcome.NotYet }
            val activity = choice.activity
            val streams = api.getStreams(activity.id, listOf("time", "heartrate"))
            val time = streams.firstOrNull { it.type == "time" }?.data
            if (time.isNullOrEmpty()) { record("failed", "activity has no time stream", activity.id); return SyncOutcome.Failed("activity has no time stream") }
            val hr = streams.firstOrNull { it.type == "heartrate" }?.data
            // Other sessions already pushed to this activity must ride along, or this push's
            // nulls outside its own span would blank them.
            val others = store.list().filter { it.id != sessionId && it.activityId == activity.id && it.syncState == "synced" && it.endMs != null }
            val allSeries = (listOf(meta) + others).map { SeriesBuilder.build(store.events(it.id), settings.thresholds) }
            val aligned = StreamAligner.align(time, hr, activity.startDate, allSeries, settings.reserve)
            val result = api.putStreams(activity.id, aligned)
            val missing = StreamCodes.ALL - result.updated.toSet()
            if (missing.isNotEmpty()) {
                val msg = "streams not accepted: ${missing.joinToString(",")}"
                record("failed", msg, activity.id); SyncOutcome.Failed(msg)
            } else {
                val msg = buildString {
                    append("pushed ${result.updated.size} streams to ${activity.id}")
                    choice.runnerUp?.let { append("; also overlapped ${it.id} (${it.deviceName ?: it.source ?: "unknown device"})") }
                }
                record("synced", msg, activity.id)
                SyncOutcome.Synced(activity.id, activity.name ?: activity.id, result.updated)
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
