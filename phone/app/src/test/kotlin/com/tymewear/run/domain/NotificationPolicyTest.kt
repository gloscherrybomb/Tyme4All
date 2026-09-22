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

    private val start = 1_790_000_000_000L   // 2026-09-21T14:13:20Z

    @Test fun `service text while recording shows start time and rounded VE`() {
        assertEquals("Recording since 14:13 · VE 34 L/min", NotificationPolicy.serviceText(StrapStatus.CONNECTED, start, 34.4, false, ZoneOffset.UTC))
        assertEquals("Recording since 14:13 · VE -- L/min", NotificationPolicy.serviceText(StrapStatus.STALE, start, null, false, ZoneOffset.UTC))
    }

    @Test fun `service text without a session reports the strap`() {
        assertEquals("Strap connected", NotificationPolicy.serviceText(StrapStatus.CONNECTED, null, 34.4, false, ZoneOffset.UTC))
        assertEquals("Strap data stale", NotificationPolicy.serviceText(StrapStatus.STALE, null, null, false, ZoneOffset.UTC))
        assertEquals("Waiting for strap", NotificationPolicy.serviceText(StrapStatus.DISCONNECTED, null, null, false, ZoneOffset.UTC))
        assertEquals("Service off", NotificationPolicy.serviceText(StrapStatus.OFF, null, null, false, ZoneOffset.UTC))
    }

    @Test fun `relay down is appended in either state`() {
        assertEquals("Waiting for strap · relay down", NotificationPolicy.serviceText(StrapStatus.DISCONNECTED, null, null, true, ZoneOffset.UTC))
        assertEquals("Recording since 14:13 · VE 34 L/min · relay down", NotificationPolicy.serviceText(StrapStatus.CONNECTED, start, 34.0, true, ZoneOffset.UTC))
    }
}
