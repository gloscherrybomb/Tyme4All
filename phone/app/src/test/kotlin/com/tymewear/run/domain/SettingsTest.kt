package com.tymewear.run.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test
    fun `defaults match the spec`() {
        val d = Settings.DEFAULT
        assertEquals(ReserveSettings(12.0, 55.0, 60.0, 190.0), d.reserve)
        assertEquals(3, d.idleStopMinutes)
        assertEquals(90, d.retentionDays)
        assertEquals(null, d.intervalsApiKey)
        assertEquals(true, d.serviceEnabled)
        assertEquals(false, d.lanOverlayEnabled)
        assertEquals(null, d.lanToken)
    }

    @Test fun `v0_1_0 thresholds move one name down`() {
        assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 130.0, 180.0), ZoneThresholds.fromLegacy(73.0, 96.0, 112.0, 130.0))
    }

    @Test fun `migrated VO2max stays above the migrated Top Z4`() {
        assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 130.0, 180.0), ZoneThresholds.migrateLegacy(73.0, 96.0, 112.0, 130.0))
        assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 200.0, 210.0), ZoneThresholds.migrateLegacy(73.0, 96.0, 112.0, 200.0))
    }

    @Test fun `defaults keep the v0_1_0 edges under Tymewear's names`() {
        assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 130.0, 180.0), Settings.DEFAULT.thresholds)
    }

    @Test
    fun `in memory store round trips and emits`() = runTest {
        val store = InMemorySettingsStore()
        val s = Settings.DEFAULT.copy(intervalsApiKey = "abc", sensorId = "1a2b")
        store.save(s)
        assertEquals(s, store.load())
        assertEquals(s, store.changes.first())
    }

    private val bike = ZoneThresholds(73.2, 96.0, 112.0, 129.6, 182.3)
    private val run = ZoneThresholds(74.9, 94.5, 123.1, 149.8, 230.1)
    private val tw = Settings.DEFAULT.copy(tymewearSignedIn = true, bikeThresholds = bike, runThresholds = run,
        tymewearReserve = ReserveSettings(13.4, 67.4, 50.0, 188.0))

    @Test fun `thresholds follow the activity's sport when Tymewear is in use`() {
        assertEquals(run, tw.thresholdsFor("Run")); assertEquals(run, tw.thresholdsFor("trailrun"))
        assertEquals(run, tw.thresholdsFor("Hike")); assertEquals(bike, tw.thresholdsFor("VirtualRide"))
        assertEquals(bike, tw.thresholdsFor(null)); assertEquals(bike, tw.liveThresholds())
        assertEquals(ReserveSettings(13.4, 67.4, 50.0, 188.0), tw.effectiveReserve())
    }

    @Test fun `manual values apply when switched off or signed out`() {
        val off = tw.copy(useTymewearThresholds = false)
        assertEquals(Settings.DEFAULT.thresholds, off.thresholdsFor("Run")); assertEquals(Settings.DEFAULT.reserve, off.effectiveReserve())
        val out = tw.copy(tymewearSignedIn = false)
        assertEquals(Settings.DEFAULT.thresholds, out.liveThresholds())
    }

    @Test fun `a missing sport falls back to manual`() {
        assertEquals(Settings.DEFAULT.thresholds, tw.copy(runThresholds = null).thresholdsFor("Run"))
    }

    @Test fun `virtual runs and walks use run thresholds`() {
        assertEquals(run, tw.thresholdsFor("VirtualRun")); assertEquals(run, tw.thresholdsFor("walk"))
    }

    @Test fun `manual reserve applies when signed out or Tymewear has none`() {
        assertEquals(Settings.DEFAULT.reserve, tw.copy(tymewearSignedIn = false).effectiveReserve())
        assertEquals(Settings.DEFAULT.reserve, tw.copy(tymewearReserve = null).effectiveReserve())
    }
    @Test fun `Tymewear uploads run only when signed in, switched on and the sign-in is not refused`() {
        assertEquals(true, tw.tymewearActive)
        assertEquals(false, tw.copy(tymewearUpload = false).tymewearActive)
        assertEquals(false, tw.copy(tymewearSignedIn = false).tymewearActive)
        assertEquals(false, tw.copy(tymewearSignInRefused = true).tymewearActive)
        assertEquals(false, Settings.DEFAULT.tymewearSignInRefused)
    }

    @Test fun `the Tymewear profile is read when signed in and not refused, whatever the upload switch`() {
        assertEquals(true, tw.tymewearProfileReadable)
        assertEquals(true, tw.copy(tymewearUpload = false).tymewearProfileReadable)
        assertEquals(false, tw.copy(tymewearSignedIn = false).tymewearProfileReadable)
        assertEquals(false, tw.copy(tymewearSignInRefused = true).tymewearProfileReadable)
    }

    @Test fun `manual thresholds are in use unless Tymewear has both sports`() {
        assertEquals(false, tw.manualThresholdsInUse())
        assertEquals(true, tw.copy(runThresholds = null).manualThresholdsInUse())
        assertEquals(true, tw.copy(bikeThresholds = null).manualThresholdsInUse())
        assertEquals(true, tw.copy(useTymewearThresholds = false).manualThresholdsInUse())
        assertEquals(true, tw.copy(tymewearSignedIn = false).manualThresholdsInUse())
    }

    @Test fun `manual reserve is in use unless Tymewear has resting and max values`() {
        assertEquals(false, tw.manualReserveInUse())
        assertEquals(true, tw.copy(tymewearReserve = null).manualReserveInUse())
        assertEquals(true, tw.copy(useTymewearThresholds = false).manualReserveInUse())
    }
    @Test fun `the reason Tymewear is not running, or null when it is`() {
        assertEquals(null, tw.tymewearOffReason())
        assertEquals("Tymewear stopped accepting your sign-in. Sign in again in Settings.", tw.copy(tymewearSignInRefused = true).tymewearOffReason())
        assertEquals("Sign in to Tymewear in Settings first.", tw.copy(tymewearSignedIn = false).tymewearOffReason())
        assertEquals("Sending breathing to Tymewear is switched off in Settings.", tw.copy(tymewearUpload = false).tymewearOffReason())
    }
}
