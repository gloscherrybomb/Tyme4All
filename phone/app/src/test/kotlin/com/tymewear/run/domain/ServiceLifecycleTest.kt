package com.tymewear.run.domain

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
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.NEARBY, sessionOpen = false, serviceEnabled = false, syncPending = false))
    }

    @Test
    fun `service disabled stops even when the strap is away and no session is open`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = false, serviceEnabled = false, syncPending = false))
    }

    @Test
    fun `service disabled stops even when presence is unknown and no session is open`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = false, syncPending = false))
    }

    @Test
    fun `service disabled stops even mid-session with the strap nearby`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.NEARBY, sessionOpen = true, serviceEnabled = false, syncPending = false))
    }

    @Test
    fun `service disabled stops even mid-session with the strap away`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = true, serviceEnabled = false, syncPending = false))
    }

    @Test
    fun `service disabled stops even mid-session with presence unknown`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = true, serviceEnabled = false, syncPending = false))
    }

    @Test
    fun `a session in progress is never interrupted by the strap being away`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = true, serviceEnabled = true, syncPending = false))
    }

    @Test
    fun `a session in progress is never interrupted by the strap being nearby`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.NEARBY, sessionOpen = true, serviceEnabled = true, syncPending = false))
    }

    @Test
    fun `a session in progress is never interrupted when presence is unknown`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = true, serviceEnabled = true, syncPending = false))
    }

    @Test
    fun `strap away with no session open stops the service`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = false, serviceEnabled = true, syncPending = false))
    }

    @Test
    fun `strap nearby with no session open keeps the service running`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.NEARBY, sessionOpen = false, serviceEnabled = true, syncPending = false))
    }

    @Test
    fun `unknown presence with no session open keeps the service running`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.UNKNOWN, sessionOpen = false, serviceEnabled = true, syncPending = false))
    }

    @Test
    fun `strap away with a finished session still pending sync keeps the service running`() {
        assertFalse(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = false, serviceEnabled = true, syncPending = true))
    }

    @Test
    fun `service disabled stops even while a sync is pending and the strap is away`() {
        assertTrue(ServiceLifecycle.shouldStop(StrapPresence.AWAY, sessionOpen = false, serviceEnabled = false, syncPending = true))
    }
}
