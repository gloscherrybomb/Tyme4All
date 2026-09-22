package com.tymewear.run.domain

import com.tymewear.run.domain.session.SessionMeta
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    private val m = SessionMeta(id = "s", startMs = 0)

    @Test fun `unmatched notifies only for sessions of fifteen minutes or more`() {
        assertFalse(NotificationPolicy.notifyUnmatched(m))                               // still open
        assertFalse(NotificationPolicy.notifyUnmatched(m.copy(endMs = 15 * 60_000L - 1)))
        assertTrue(NotificationPolicy.notifyUnmatched(m.copy(endMs = 15 * 60_000L)))
    }

    @Test fun `recording body shows start time and rounded VE`() {
        val start = 1_790_000_000_000L   // 2026-09-21T14:13:20Z
        assertEquals("Since 14:13 · VE 34 L/min", NotificationPolicy.recordingBody(start, 34.4, ZoneOffset.UTC))
        assertEquals("Since 14:13 · VE -- L/min", NotificationPolicy.recordingBody(start, null, ZoneOffset.UTC))
    }
}
