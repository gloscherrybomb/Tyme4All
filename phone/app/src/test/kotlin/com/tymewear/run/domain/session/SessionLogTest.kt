package com.tymewear.run.domain.session

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `events round trip through jsonl`() {
        val f = tmp.newFile("s.jsonl")
        val log = SessionLog(f)
        val events = listOf(
            SessionEvent.Start(1000, "watch"),
            SessionEvent.Breath(1500, br = 20.0, tv = 1.5, ie = 1.0, tvRaw = 150, inhale = 150, exhale = 150, ts40 = 99),
            SessionEvent.Battery(1600, 80),
            SessionEvent.Strap(2000, connected = false),
            SessionEvent.Stop(3000, "watch"),
        )
        events.forEach(log::append)
        log.close()
        assertEquals(events, SessionLog.read(f))
        assertEquals(5, f.readLines().size)
    }

    @Test
    fun `a truncated last line is skipped not fatal`() {
        val f = tmp.newFile("s.jsonl")
        SessionLog(f).apply { append(SessionEvent.Start(1, "manual")); close() }
        f.appendText("{\"type\":\"breath\",\"tMs\":5")
        assertEquals(listOf<SessionEvent>(SessionEvent.Start(1, "manual")), SessionLog.read(f))
    }
}
