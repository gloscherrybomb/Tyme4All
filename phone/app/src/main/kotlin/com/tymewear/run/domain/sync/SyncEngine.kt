package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.session.SeriesBuilder
import com.tymewear.run.domain.session.SessionMeta
import com.tymewear.run.domain.session.SessionStore
import com.tymewear.run.domain.tymewear.TymewearOutcome
import com.tymewear.run.domain.tymewear.TymewearStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed class SyncOutcome {
    data class Synced(
        val activityId: String,
        val activityLabel: String,
        val updated: List<String>,
        val tymewear: TymewearOutcome? = null,
    ) : SyncOutcome()
    data object NotYet : SyncOutcome()
    data class Skipped(val reason: String) : SyncOutcome()
    data class Failed(val message: String) : SyncOutcome()
}

/** The activity to push to, and the next-best overlapping activity if there was one. */
private data class Choice(val activity: ActivitySummary, val runnerUp: ActivitySummary?)

/** An activity's Intervals.icu time stream and the breathing streams aligned to it. */
private data class Prepared(val time: List<Double?>, val aligned: List<Stream>)

class SyncEngine(
    private val api: IntervalsApi,
    private val store: SessionStore,
    private val uploader: TymewearStep? = null,
) {

    /** Notification label: the activity's name, else its type and local start time ("Ride at 18:42"). */
    private fun activityLabel(a: ActivitySummary): String =
        a.name ?: "${a.type ?: "Activity"} at ${HH_MM.format(a.startDate.atZone(ZoneId.systemDefault()))}"

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
        // The matcher drops Karoo rides (the Karoo records the Tyme* fields itself); a manual id must not bypass that.
        if (found.deviceName?.contains("karoo", ignoreCase = true) == true) {
            throw IntervalsException(400, "activity $activityId was recorded by a Karoo, which records breathing itself")
        }
        Choice(found, null)
    }

    private fun run(sessionId: String, settings: Settings, nowMs: Long, choose: (SessionMeta) -> Choice?): SyncOutcome {
        val meta = store.meta(sessionId) ?: return SyncOutcome.Failed("unknown session")
        fun record(state: String, msg: String, activityId: String? = meta.activityId) =
            store.updateMeta(sessionId) { it.copy(syncState = state, syncMessage = msg, lastSyncAttemptMs = nowMs, activityId = activityId) }

        if (settings.intervalsApiKey.isNullOrBlank()) { record("skipped", "no api key"); return SyncOutcome.Skipped("no api key") }
        val end = meta.endMs ?: run { record("pending", "session still open"); return SyncOutcome.NotYet }
        if (end - meta.startMs < 60_000) { record("skipped", "session shorter than 60 s"); return SyncOutcome.Skipped("session shorter than 60 s") }

        // Set once the push succeeded; runs after the try below, so nothing in it can alter the Intervals.icu result.
        var tymewearAttempt: (() -> TymewearOutcome)? = null
        val outcome = try {
            val choice = choose(meta) ?: run { record("pending", "no overlapping activity yet"); return SyncOutcome.NotYet }
            val activity = choice.activity
            val (time, aligned) = prepare(meta, activity, settings)
                ?: run { record("failed", "activity has no time stream", activity.id); return SyncOutcome.Failed("activity has no time stream") }
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
                if (uploader != null && settings.tymewearActive) {
                    tymewearAttempt = { uploader.upload(activity, time, aligned) }
                }
                SyncOutcome.Synced(activity.id, activityLabel(activity), result.updated)
            }
        } catch (e: IntervalsException) {
            record("failed", e.message ?: "intervals error"); SyncOutcome.Failed(e.message ?: "intervals error")
        } catch (e: java.io.IOException) {
            record("pending", "network: ${e.message}"); SyncOutcome.NotYet
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "unknown error"
            record("failed", msg); SyncOutcome.Failed(msg)
        }
        val attempt = tymewearAttempt
        return if (outcome is SyncOutcome.Synced && attempt != null) outcome.copy(tymewear = tymewearStep(sessionId, nowMs, attempt)) else outcome
    }

    /**
     * Retries only the Tymewear step of a session already synced to Intervals.icu: rebuilds the
     * same streams the push sent and hands them to the uploader. Never pushes to Intervals.icu.
     * Null when there is nothing to do (no uploader, Tymewear off, signed out or refused, session not synced).
     */
    fun syncTymewear(sessionId: String, settings: Settings, nowMs: Long): TymewearOutcome? {
        val step = uploader ?: return null
        if (!settings.tymewearActive) return null
        val meta = store.meta(sessionId) ?: return null
        val activityId = meta.activityId
        if (meta.syncState != "synced" || activityId == null) return null
        return tymewearStep(sessionId, nowMs) {
            val start = Instant.ofEpochMilli(meta.startMs)
            val activity = api.listActivities(start.minusSeconds(86_400), start.plusSeconds(86_400)).firstOrNull { it.id == activityId }
                ?: return@tymewearStep TymewearOutcome.Failed("activity $activityId not found near the session")
            val (time, aligned) = prepare(meta, activity, settings)
                ?: return@tymewearStep TymewearOutcome.Failed("activity has no time stream")
            step.upload(activity, time, aligned)
        }
    }

    /**
     * Runs one Tymewear attempt and records its outcome. Never throws: an exception in the attempt
     * is a failure of this step only, and a store error only loses the record of it.
     */
    private fun tymewearStep(sessionId: String, nowMs: Long, attempt: () -> TymewearOutcome): TymewearOutcome {
        val outcome = try {
            attempt()
        } catch (e: java.io.IOException) {
            TymewearOutcome.NotYet
        } catch (e: Exception) {
            // Class name only: a message could quote a reply.
            TymewearOutcome.Failed("unexpected error: ${e.javaClass.simpleName}")
        }
        val (state, msg) = when (outcome) {
            TymewearOutcome.Uploaded -> "synced" to "breathing added in Tymewear"
            TymewearOutcome.NotYet -> "pending" to "not in Tymewear yet"
            is TymewearOutcome.Skipped -> "skipped" to outcome.reason
            // A refused sign-in waits for the next one: the retry loop picks it up again, within the give-up window.
            is TymewearOutcome.Failed -> if (outcome.auth) "pending" to "Tymewear stopped accepting the sign-in" else "failed" to outcome.reason
        }
        try {
            store.updateMeta(sessionId) { it.copy(tymewearState = state, tymewearMessage = msg, tymewearAttemptMs = nowMs) }
        } catch (_: Exception) {
            // The state stays as it was; the Intervals.icu result is unaffected.
        }
        return outcome
    }

    /**
     * Fetches the activity's time and heart-rate streams and aligns the breathing to them, using
     * the activity's sport thresholds. Null when the activity has no time stream.
     */
    private fun prepare(meta: SessionMeta, activity: ActivitySummary, settings: Settings): Prepared? {
        val streams = api.getStreams(activity.id, listOf("time", "heartrate"))
        val time = streams.firstOrNull { it.type == "time" }?.data
        if (time.isNullOrEmpty()) return null
        val hr = streams.firstOrNull { it.type == "heartrate" }?.data
        // Other sessions already pushed to this activity must ride along, or this push's
        // nulls outside its own span would blank them. Any finished sibling that targets
        // this activity counts, whatever its current state: a later failed retry does not
        // remove the data its earlier push put on the activity.
        val others = store.list().filter { it.id != meta.id && it.activityId == activity.id && it.endMs != null }
        val thresholds = settings.thresholdsFor(activity.type)
        val allSeries = (listOf(meta) + others).map { SeriesBuilder.build(store.events(it.id), thresholds) }
        return Prepared(time, StreamAligner.align(time, hr, activity.startDate, allSeries, settings.effectiveReserve()))
    }
}

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
