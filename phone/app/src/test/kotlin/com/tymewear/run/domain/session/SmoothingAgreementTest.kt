package com.tymewear.run.domain.session

import com.tymewear.run.domain.LiveState
import com.tymewear.run.domain.Protocol
import com.tymewear.run.domain.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LiveState (live smoothing) and SeriesBuilder (replay smoothing) must agree: both use the
 * same rolling-average window over the same raw breaths, so a value read live and the same
 * value read back from the synced log should match.
 */
class SmoothingAgreementTest {
    private val settings = Settings.DEFAULT
    private val breaths = listOf(
        Triple(1_000L, 20.0, 2.0),
        Triple(4_000L, 25.0, 2.5),
        Triple(7_000L, 30.0, 3.0),
    )

    @Test
    fun `live and replayed VE agree for the same breaths`() {
        val live = LiveState()
        live.onConnected()
        for ((tMs, br, tv) in breaths) {
            live.onBreath(Protocol.BreathingData(br, tv, br * tv, 1.0, 100, 100, (tv * 100).toInt(), 0, tMs), nowMs = tMs)
        }
        val livePayload = live.payload(settings, nowMs = 7_500)

        val events = listOf(SessionEvent.Start(0, "watch")) +
            breaths.map { (tMs, br, tv) -> SessionEvent.Breath(tMs, br, tv, 1.0, (tv * 100).toInt(), 100, 100, tMs / 40) } +
            listOf(SessionEvent.Stop(8_000, "watch"))
        val series = SeriesBuilder.build(events, settings.thresholds)
        val replayedVe = series.at(7)!!.ve!!

        assertEquals(replayedVe, livePayload.ve!!, 1e-9)
    }
}
