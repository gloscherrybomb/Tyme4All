package com.tymewear.run.domain

import com.tymewear.run.domain.session.SessionMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service must never abandon an in-progress recording just because the strap's
 * presence looks bad, but an explicit "service off" always wins, mid-session or not.
 */
class ServiceLifecycleTest {

    @Test
    fun `service disabled stops even when the strap is nearby and no session is open`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.NEARBY, sessionOpen = false, serviceEnabled = false, syncPending = false, watch = unpaired))
    }

    @Test
    fun `service disabled stops even when the strap is away and no session is open`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = false, serviceEnabled = false, syncPending = false, watch = unpaired))
    }

    @Test
    fun `service disabled stops even when presence is unknown and no session is open`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = false, syncPending = false, watch = unpaired))
    }

    @Test
    fun `service disabled stops even mid-session with the strap nearby`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.NEARBY, sessionOpen = true, serviceEnabled = false, syncPending = false, watch = unpaired))
    }

    @Test
    fun `service disabled stops even mid-session with the strap away`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = true, serviceEnabled = false, syncPending = false, watch = unpaired))
    }

    @Test
    fun `service disabled stops even mid-session with presence unknown`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = true, serviceEnabled = false, syncPending = false, watch = unpaired))
    }

    @Test
    fun `a session in progress is never interrupted by the strap being away`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = true, serviceEnabled = true, syncPending = false, watch = unpaired))
    }

    @Test
    fun `a session in progress is never interrupted by the strap being nearby`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.NEARBY, sessionOpen = true, serviceEnabled = true, syncPending = false, watch = unpaired))
    }

    @Test
    fun `a session in progress is never interrupted when presence is unknown`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = true, serviceEnabled = true, syncPending = false, watch = unpaired))
    }

    @Test
    fun `strap away with no session open stops the service`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = false, serviceEnabled = true, syncPending = false, watch = unpaired))
    }

    @Test
    fun `strap nearby with no session open keeps the service running`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.NEARBY, sessionOpen = false, serviceEnabled = true, syncPending = false, watch = unpaired))
    }

    @Test
    fun `unknown presence with no session open keeps the service running`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = true, syncPending = false, watch = unpaired))
    }

    @Test
    fun `strap away with a finished session still pending sync keeps the service running`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = false, serviceEnabled = true, syncPending = true, watch = unpaired))
    }

    @Test
    fun `service disabled stops even while a sync is pending and the strap is away`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = false, serviceEnabled = false, syncPending = true, watch = unpaired))
    }

    // Presence watching: paired, CDM observing, but no presence answer and no strap connection.
    private val unpaired = PresenceWatch(paired = false, observingCount = 0, strapConnected = false, msSinceStrapSeen = 0L)
    private val grace = Constants.PRESENCE_GRACE_MS
    private fun watching(strapConnected: Boolean = false, msSinceStrapSeen: Long = grace) =
        PresenceWatch(paired = true, observingCount = 1, strapConnected = strapConnected, msSinceStrapSeen = msSinceStrapSeen)

    @Test
    fun `paired and observing with unknown presence and no strap for the grace period stops the service`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = true, syncPending = false, watch = watching()))
    }

    @Test
    fun `paired and observing with unknown presence keeps running inside the grace period`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = true, syncPending = false, watch = watching(msSinceStrapSeen = grace - 1)))
    }

    @Test
    fun `paired and observing with the strap connected keeps running whatever the presence`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = true, syncPending = false, watch = watching(strapConnected = true, msSinceStrapSeen = 0L)))
    }

    @Test
    fun `paired and observing with the strap nearby keeps running past the grace period`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.NEARBY, sessionOpen = false, serviceEnabled = true, syncPending = false, watch = watching()))
    }

    @Test
    fun `paired and observing with no strap for the grace period keeps running while a session is open`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = true, serviceEnabled = true, syncPending = false, watch = watching()))
    }

    @Test
    fun `paired and observing with no strap for the grace period keeps running while a sync is pending`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = true, syncPending = true, watch = watching()))
    }

    @Test
    fun `paired but not observing keeps the service always on`() {
        val notObserving = PresenceWatch(paired = true, observingCount = 0, strapConnected = false, msSinceStrapSeen = grace * 10)
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = true, syncPending = false, watch = notObserving))
    }

    @Test
    fun `unpaired keeps the service always on however long the strap has been gone`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = true, syncPending = false, watch = unpaired.copy(msSinceStrapSeen = grace * 10)))
    }

    @Test
    fun `the grace period is two minutes`() {
        assertEquals(2 * 60 * 1000L, Constants.PRESENCE_GRACE_MS)
    }

    // Opening the app (or signing in) with presence observed starts the service only when it has work;
    // otherwise the app looks for the strap itself with a short scan, while it is in the foreground.
    private fun open(
        paired: Boolean = true, observing: Boolean = true, presence: StrapPresence = StrapPresence.UNKNOWN,
        sessionOpen: Boolean = false, syncPending: Boolean = false, canScan: Boolean = true, scanRunning: Boolean = false,
    ) = ServiceLifecycle.onAppOpen(paired, observing, presence, sessionOpen, syncPending, canScan, scanRunning)

    @Test
    fun `app open with presence observed and the strap not nearby scans for it instead of starting the service`() {
        assertEquals(AppOpenAction.SCAN, open(presence = StrapPresence.UNKNOWN))
        assertEquals(AppOpenAction.SCAN, open(presence = StrapPresence.AWAY))
    }

    @Test
    fun `app open without the scan permission does nothing when the strap is not nearby`() {
        assertEquals(AppOpenAction.NOTHING, open(canScan = false))
    }

    @Test
    fun `app open while a scan is already running does not start a second one`() {
        assertEquals(AppOpenAction.NOTHING, open(scanRunning = true))
    }

    @Test
    fun `app open with presence observed and the strap nearby starts the service`() {
        assertEquals(AppOpenAction.START, open(presence = StrapPresence.NEARBY))
    }

    @Test
    fun `app open with presence observed and a session open starts the service`() {
        assertEquals(AppOpenAction.START, open(presence = StrapPresence.AWAY, sessionOpen = true))
    }

    @Test
    fun `app open with presence observed and a sync pending starts the service`() {
        assertEquals(AppOpenAction.START, open(presence = StrapPresence.AWAY, syncPending = true))
    }

    @Test
    fun `app open with work to do starts the service even while a scan runs`() {
        assertEquals(AppOpenAction.START, open(syncPending = true, scanRunning = true))
    }

    @Test
    fun `app open when paired but not observing starts the service as before`() {
        assertEquals(AppOpenAction.START, open(observing = false))
    }

    @Test
    fun `app open when unpaired starts the service as before`() {
        assertEquals(AppOpenAction.START, open(paired = false, observing = false))
        assertEquals(AppOpenAction.START, open(paired = false, observing = true, canScan = false))
    }

    @Test
    fun `the app-open scan lasts twenty seconds`() {
        assertEquals(20_000L, Constants.APP_OPEN_SCAN_MS)
    }

    // A run left open on disk (reboot, or the process killed mid-run) counts as open until the service closes it.
    @Test
    fun `a session on disk with no end counts as open and starts the service`() {
        val openOnDisk = listOf(SessionMeta(id = "a", startMs = 0, endMs = 60_000), SessionMeta(id = "b", startMs = 100_000))
        assertTrue(ServiceLifecycle.sessionOpen(activeSessionId = null, metas = openOnDisk))
        assertEquals(AppOpenAction.START, open(sessionOpen = ServiceLifecycle.sessionOpen(null, openOnDisk)))
    }

    @Test
    fun `the session in memory counts as open`() {
        assertTrue(ServiceLifecycle.sessionOpen(activeSessionId = "b", metas = emptyList()))
    }

    @Test
    fun `only finished sessions on disk and none in memory is not open`() {
        assertFalse(ServiceLifecycle.sessionOpen(activeSessionId = null, metas = listOf(SessionMeta(id = "a", startMs = 0, endMs = 60_000))))
    }
}
