package com.tymewear.run.domain.session

import com.tymewear.run.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun ctl() = SessionController(SessionStore(tmp.root), fallbackDisconnectedMs = 600_000, maxSessionMs = 3_600_000)
    private val d = Protocol.BreathingData(20.0, 1.5, 30.0, 1.0, 100, 100, 150, 0, 1)

    @Test
    fun `start is idempotent and stop closes`() {
        val c = ctl()
        val id = c.start(1_000, "watch")
        assertEquals(id, c.start(2_000, "watch"))
        assertEquals(id, c.activeSessionId)
        assertEquals(id, c.stop(5_000, "watch"))
        assertNull(c.activeSessionId)
        assertNull(c.stop(6_000, "watch"))
        val store = SessionStore(tmp.root)
        assertEquals(5_000L, store.meta(id)!!.endMs)
        assertEquals("watch", (store.events(id).last() as SessionEvent.Stop).reason)
    }

    @Test
    fun `breaths are logged only inside a session`() {
        val c = ctl()
        c.onBreath(d, 500)
        val id = c.start(1_000, "watch")
        c.onBreath(d, 1_500)
        c.onBattery(50, 1_600)
        c.stop(2_000, "watch")
        c.onBreath(d, 2_500)
        val events = SessionStore(tmp.root).events(id)
        assertEquals(listOf("start", "breath", "battery", "stop"), events.map { it::class.simpleName!!.lowercase() })
    }

    @Test
    fun `fallback stop after strap disconnected long enough`() {
        val c = ctl()
        c.onStrap(true, 0)
        c.start(1_000, "watch")
        c.onStrap(false, 10_000)
        assertNull(c.tick(10_000 + 599_999))
        assertEquals("strap-disconnected", c.tick(10_000 + 600_001))
        assertNull(c.activeSessionId)
    }

    @Test
    fun `reconnect cancels the disconnected fallback`() {
        val c = ctl()
        c.start(1_000, "watch")
        c.onStrap(false, 10_000)
        c.onStrap(true, 300_000)
        assertNull(c.tick(10_000 + 600_001))
    }

    @Test
    fun `fallback stop at max session length`() {
        val c = ctl()
        c.start(1_000, "watch")
        c.onStrap(true, 1_000)
        assertEquals("max-length", c.tick(1_000 + 3_600_001))
    }

    @Test
    fun `startup recovery closes an unfinished session at its last event`() {
        val store = SessionStore(tmp.root)
        val log = store.create(1_000, "watch")
        log.append(SessionEvent.Breath(5_000, d.breathRate, d.tidalVolume, d.ieRatio, d.tvRaw, d.inhaleDurationCs, d.exhaleDurationCs, d.timestamp40ms))
        log.close()
        val c = SessionController(store)
        c.recoverOnStartup(nowMs = 9_000)
        assertEquals(5_000L, store.meta(SessionStore.idFor(1_000))!!.endMs)
        assertEquals("restart", (store.events(SessionStore.idFor(1_000)).last() as SessionEvent.Stop).reason)
    }

    @Test
    fun `startup recovery of a log with only a start ends at the start time`() {
        val store = SessionStore(tmp.root)
        store.create(1_000, "watch").close()
        val c = SessionController(store)
        c.recoverOnStartup(nowMs = 9_000)
        assertEquals(1_000L, store.meta(SessionStore.idFor(1_000))!!.endMs)
    }

    @Test
    fun `session started after disconnect timer already expired is not stopped by tick`() {
        val c = ctl()
        c.onStrap(false, 0)
        c.start(1_000_000, "watch")
        assertNull(c.tick(1_000_000 + 599_999))
        assertEquals("strap-disconnected", c.tick(1_000_000 + 600_001))
    }

    @Test
    fun `recoverOnStartup does not close the in-memory active session`() {
        val c = ctl()
        val activeId = c.start(1_000, "watch")
        val store = SessionStore(tmp.root)
        store.create(500_000, "watch").close()
        val otherId = SessionStore.idFor(500_000)

        c.recoverOnStartup(9_000_000)

        assertEquals(activeId, c.activeSessionId)
        assertNull(store.meta(activeId)!!.endMs)
        // otherId's log has only a Start, so recovery ends it at its start time, not now.
        assertEquals(500_000L, store.meta(otherId)!!.endMs)
        assertEquals("restart", (store.events(otherId).last() as SessionEvent.Stop).reason)
    }

    @Test
    fun `fallbackDisconnectedMs is live and applies to the next tick`() {
        val c = ctl()
        c.onStrap(true, 0)
        c.start(1_000, "watch")
        c.onStrap(false, 0)
        c.fallbackDisconnectedMs = 60_000
        assertEquals("strap-disconnected", c.tick(60_001))
    }

    @Test
    fun `listener is told about changes`() {
        val c = ctl()
        val seen = mutableListOf<String?>()
        c.listener = { seen.add(it) }
        val id = c.start(1_000, "manual")
        c.stop(2_000, "manual")
        assertEquals(listOf(id, null), seen)
    }
}
