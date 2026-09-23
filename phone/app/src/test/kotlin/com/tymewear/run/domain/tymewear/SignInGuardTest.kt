package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.sync.ActivitySummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SignInGuardTest {
    private val activity = ActivitySummary("i1", Instant.ofEpochMilli(0), "Run", "Run", "ZEPP", "Amazfit", 600)

    @Test fun `passes the step's outcome through while the sign-in is accepted`() {
        var told = 0
        val guard = SignInGuard(TymewearStep { _, _, _ -> TymewearOutcome.Uploaded }, refused = { false }, onRefused = { told++ })
        assertEquals(TymewearOutcome.Uploaded, guard.upload(activity, emptyList(), emptyList()))
        assertEquals(0, told)
    }

    @Test fun `an auth failure reports the refusal once and later attempts wait without calling Tymewear`() {
        var refused = false
        var calls = 0
        val guard = SignInGuard(
            TymewearStep { _, _, _ -> calls++; TymewearOutcome.Failed("Tymewear stopped accepting the saved sign-in", auth = true) },
            refused = { refused }, onRefused = { refused = true },
        )
        assertEquals(TymewearOutcome.Failed("Tymewear stopped accepting the saved sign-in", auth = true), guard.upload(activity, emptyList(), emptyList()))
        assertEquals(true, refused)
        assertEquals(TymewearOutcome.NotYet, guard.upload(activity, emptyList(), emptyList()))
        assertEquals(1, calls)
    }

    @Test fun `a failure that is not about the sign-in does not report a refusal`() {
        var told = false
        val guard = SignInGuard(TymewearStep { _, _, _ -> TymewearOutcome.Failed("Tymewear refused the file") }, refused = { false }, onRefused = { told = true })
        guard.upload(activity, emptyList(), emptyList())
        assertEquals(false, told)
    }
}
