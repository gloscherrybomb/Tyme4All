package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.fit.BreathingFitMerger
import com.tymewear.run.domain.fit.NotFitException
import com.tymewear.run.domain.sync.ActivitySummary
import com.tymewear.run.domain.sync.IntervalsApi
import com.tymewear.run.domain.sync.IntervalsException
import com.tymewear.run.domain.sync.Stream
import java.io.IOException
import java.time.Duration
import java.util.zip.ZipException
import kotlin.math.abs

sealed class TymewearOutcome {
    data object Uploaded : TymewearOutcome()
    data object NotYet : TymewearOutcome()
    data class Skipped(val reason: String) : TymewearOutcome()
    data class Failed(val reason: String, val auth: Boolean = false) : TymewearOutcome()
}

/** The Tymewear step of a sync: gets one activity's breathing into Tymewear. */
fun interface TymewearStep {
    fun upload(activity: ActivitySummary, time: List<Double?>, streams: List<Stream>): TymewearOutcome
}

/** Gets one activity's breathing into Tymewear by replacing the file behind Tymewear's copy of it. */
class TymewearUploader(private val intervals: IntervalsApi, private val tymewear: TymewearApi) : TymewearStep {

    override fun upload(activity: ActivitySummary, time: List<Double?>, streams: List<Stream>): TymewearOutcome {
        if (activity.deviceName?.contains("karoo", ignoreCase = true) == true) return TymewearOutcome.Skipped("Karoo rides carry breathing already")
        return try {
            val tpId = findCopy(activity) ?: return TymewearOutcome.NotYet
            val merged = BreathingFitMerger.merge(intervals.originalFile(activity.id), activity.startDate, time, streams)
            tymewear.replaceFile(tpId, merged)
            TymewearOutcome.Uploaded
        } catch (e: NotFitException) {
            TymewearOutcome.Skipped("the activity's original file is not a FIT file")
        } catch (e: TymewearAuthException) {
            TymewearOutcome.Failed(e.message ?: "Tymewear refused the sign-in", auth = true)
        } catch (e: TymewearException) {
            if (refused(e.httpCode)) TymewearOutcome.Failed(e.message ?: "Tymewear refused the file") else TymewearOutcome.NotYet
        } catch (e: IntervalsException) {
            if (refused(e.httpCode)) TymewearOutcome.Failed("could not download the original file: ${e.message}") else TymewearOutcome.NotYet
        } catch (e: ZipException) {
            TymewearOutcome.Skipped("the activity's original file could not be read")
        } catch (e: IOException) {
            TymewearOutcome.NotYet
        } catch (e: RuntimeException) {
            // Class name only: a message could quote a reply.
            TymewearOutcome.Failed("unexpected error: ${e.javaClass.simpleName}")
        }
    }

    /** A 4xx other than 429 will not change on a retry; anything else might. */
    private fun refused(httpCode: Int) = httpCode in 400..499 && httpCode != 429

    /** Tymewear's id for its Intervals.icu copy of this activity, or null if it has none yet. */
    private fun findCopy(activity: ActivitySummary): Long? {
        val profile = tymewear.profile()
        val listed = tymewear.recentActivities(profile.id, LIST_LIMIT)
        // Tymewear lists an activity when it imports it, some time after the activity started.
        val earliest = activity.startDate.minus(Duration.ofHours(1))
        val candidates = listed.filter { it.listedAt == null || !it.listedAt.isBefore(earliest) }.take(DETAIL_LIMIT)
        for (c in candidates) {
            val copies = c.partnerCopies.ifEmpty { tymewear.activity(c.id).partnerCopies }
            copies.firstOrNull { it.partner == "INTERVALS_ICU" && abs(Duration.between(it.startDate, activity.startDate).seconds) <= 60 }
                ?.let { return it.tpId }
        }
        return null
    }

    private companion object {
        const val LIST_LIMIT = 30
        /** Details are large (per-second arrays), so only this many are opened per attempt. */
        const val DETAIL_LIMIT = 8
    }
}
