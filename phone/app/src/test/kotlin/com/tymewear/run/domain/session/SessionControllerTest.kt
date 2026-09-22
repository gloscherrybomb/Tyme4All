package com.tymewear.run.domain.session

import com.tymewear.run.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun ctl() = SessionController(SessionStore(tmp.root), idleStopMs = 180_000, maxSessionMs = 3_600_000)
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
    fun `first breath opens a strap session and logs the breath`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        val id = c.activeSessionId!!
        assertEquals(SessionStore.idFor(1_000), id)
        val events = SessionStore(tmp.root).events(id)
        assertEquals("strap", (events[0] as SessionEvent.Start).source)
        assertEquals(listOf("start", "breath"), events.map { it::class.simpleName!!.lowercase() })
    }

    @Test
    fun `breath inside a watch session does not open another`() {
        val c = ctl()
        val id = c.start(1_000, "watch")
        c.onBreath(d, 1_500)
        c.onBattery(50, 1_600)
        assertEquals(id, c.activeSessionId)
        val events = SessionStore(tmp.root).events(id)
        assertEquals(listOf("start", "breath", "battery"), events.map { it::class.simpleName!!.lowercase() })
    }

    @Test
    fun `breath after a manual stop reopens a new session`() {
        val c = ctl()
        val first = c.start(1_000, "manual")
        c.stop(5_000, "manual")
        c.onBreath(d, 9_000)
        val second = c.activeSessionId!!
        assertNotEquals(first, second)
        assertEquals("strap", (SessionStore(tmp.root).events(second)[0] as SessionEvent.Start).source)
    }

    @Test
    fun `reopening within the same second gets a distinct id`() {
        val c = ctl()
        val first = c.start(1_000, "manual")
        c.stop(1_200, "manual")
        c.onBreath(d, 1_800)
        val second = c.activeSessionId!!
        assertNotEquals(first, second)
        val store = SessionStore(tmp.root)
        assertEquals(1_200L, store.meta(first)!!.endMs)
        assertNull(store.meta(second)!!.endMs)
    }

    @Test
    fun `idle timeout closes the session after the last breath`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        c.onBreath(d, 10_000)
        assertNull(c.tick(10_000 + 180_000))
        assertEquals("idle", c.tick(10_000 + 180_001))
        assertNull(c.activeSessionId)
        val id = SessionStore.idFor(1_000)
        assertEquals("idle", (SessionStore(tmp.root).events(id).last() as SessionEvent.Stop).reason)
    }

    @Test
    fun `idle timeout counts from start when no breath has arrived`() {
        val c = ctl()
        c.start(1_000, "watch")
        assertNull(c.tick(1_000 + 180_000))
        assertEquals("idle", c.tick(1_000 + 180_001))
    }

    @Test
    fun `strap events are logged but do not close the session`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        c.onStrap(false, 2_000)
        c.onStrap(true, 3_000)
        assertNull(c.tick(100_000))
        val events = SessionStore(tmp.root).events(c.activeSessionId!!)
        assertEquals(listOf("start", "breath", "strap", "strap"), events.map { it::class.simpleName!!.lowercase() })
    }

    @Test
    fun `strap events outside a session are not logged anywhere`() {
        val c = ctl()
        c.onStrap(true, 1_000)
        c.onStrap(false, 2_000)
        assertNull(c.activeSessionId)
        assertEquals(0, SessionStore(tmp.root).list().size)
    }

    @Test
    fun `max session length closes even while breathing`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        c.onBreath(d, 1_000 + 3_600_000)
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
    fun `recoverOnStartup does not close the in-memory active session`() {
        val c = ctl()
        val activeId = c.start(1_000, "watch")
        val store = SessionStore(tmp.root)
        store.create(500_000, "watch").close()
        val otherId = SessionStore.idFor(500_000)

        c.recoverOnStartup(9_000_000)

        assertEquals(activeId, c.activeSessionId)
        assertNull(store.meta(activeId)!!.endMs)
        assertEquals(500_000L, store.meta(otherId)!!.endMs)
        assertEquals("restart", (store.events(otherId).last() as SessionEvent.Stop).reason)
    }

    @Test
    fun `idleStopMs is live and applies to the next tick`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        c.idleStopMs = 60_000
        assertEquals("idle", c.tick(61_001))
    }

    @Test
    fun `listener is told about changes including strap-opened sessions`() {
        val c = ctl()
        val seen = mutableListOf<String?>()
        c.listener = { seen.add(it) }
        c.onBreath(d, 1_000)
        val id = c.activeSessionId
        c.stop(2_000, "manual")
        assertEquals(listOf(id, null), seen)
    }
}
