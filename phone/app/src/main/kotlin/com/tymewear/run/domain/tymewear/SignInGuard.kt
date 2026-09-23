package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.sync.ActivitySummary
import com.tymewear.run.domain.sync.Stream

/**
 * Stops calling Tymewear once it refuses the saved sign-in. The first auth failure is reported
 * through [onRefused]; while [refused] holds, attempts wait (NotYet) without calling [step], so
 * nothing retries the stored password until the user signs in again.
 */
class SignInGuard(
    private val step: TymewearStep,
    private val refused: () -> Boolean,
    private val onRefused: () -> Unit,
) : TymewearStep {
    override fun upload(activity: ActivitySummary, time: List<Double?>, streams: List<Stream>): TymewearOutcome {
        if (refused()) return TymewearOutcome.NotYet
        val out = step.upload(activity, time, streams)
        if (out is TymewearOutcome.Failed && out.auth) onRefused()
        return out
    }
}
