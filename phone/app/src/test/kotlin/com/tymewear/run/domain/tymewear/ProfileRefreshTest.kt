package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.ZoneThresholds
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The thresholds read from Tymewear: its outcome, what is stored, and what the user is told. */
class ProfileRefreshTest {
    private val bike = ZoneThresholds(73.2, 96.0, 112.0, 129.6, 182.3)
    private val reserve = ReserveSettings(10.0, 60.0, 50.0, 185.0)
    private val signedIn = Settings.DEFAULT.copy(tymewearSignedIn = true)
    private val now = 1_790_000_000_000L   // 2026-09-21T14:13:20Z

    private class FakeTymewear(val fail: Exception? = null, val bike: ZoneThresholds? = null, val reserve: ReserveSettings? = null) : TymewearApi {
        var calls = 0
        override fun signIn(email: String, password: String) {}
        override fun profile(): TymewearProfile { calls++; fail?.let { throw it }; return TymewearProfile(7) }
        override fun activeThresholds(userId: Long, sport: String) = if (sport == "bike") bike else null
        override fun restingMax() = reserve
        override fun recentActivities(userId: Long, limit: Int) = emptyList<TymewearActivity>()
        override fun activity(id: String) = throw UnsupportedOperationException()
        override fun replaceFile(tpId: Long, fit: ByteArray) = throw UnsupportedOperationException()
    }

    @Test fun `a read returns what Tymewear holds`() {
        val out = TymewearProfileSync.refresh(signedIn, FakeTymewear(bike = bike, reserve = reserve))
        assertEquals(ProfileRefresh.Refreshed(bike, null, reserve), out)
    }

    @Test fun `not signed in reads nothing`() {
        val api = FakeTymewear(bike = bike)
        assertEquals(ProfileRefresh.NotSignedIn, TymewearProfileSync.refresh(Settings.DEFAULT, api))
        assertEquals(ProfileRefresh.NotSignedIn, TymewearProfileSync.refresh(signedIn.copy(tymewearSignInRefused = true), api))
        assertEquals(0, api.calls)
    }

    @Test fun `a refused sign-in is reported as refused`() {
        assertEquals(ProfileRefresh.AuthRefused, TymewearProfileSync.refresh(signedIn, FakeTymewear(fail = TymewearAuthException("no"))))
    }

    @Test fun `an HTTP error gives the code only, never the message`() {
        val out = TymewearProfileSync.refresh(signedIn, FakeTymewear(fail = TymewearException(503, "reply: secret@example.com")))
        assertEquals(ProfileRefresh.Failed("HTTP 503", "TymewearException"), out)
    }

    @Test fun `a reply Tymewear sent without error but could not be read gives the class name`() {
        val out = TymewearProfileSync.refresh(signedIn, FakeTymewear(fail = TymewearException(200, "Tymewear profile reply had no id")))
        assertEquals(ProfileRefresh.Failed("TymewearException", "TymewearException"), out)
    }

    @Test fun `any other failure gives the class name only`() {
        val out = TymewearProfileSync.refresh(signedIn, FakeTymewear(fail = java.net.UnknownHostException("api.tymewear.com secret")))
        assertEquals(ProfileRefresh.Failed("UnknownHostException", "UnknownHostException"), out)
    }

    @Test fun `a read is stored with its time and clears an earlier failure`() {
        val before = signedIn.copy(tymewearRefreshError = "HTTP 503", runThresholds = bike)
        val saved = TymewearProfileSync.record(before, ProfileRefresh.Refreshed(bike, null, reserve), now)
        assertEquals(bike, saved.bikeThresholds)
        assertEquals(bike, saved.runThresholds)   // Tymewear sent none: the stored value stays
        assertEquals(reserve, saved.tymewearReserve)
        assertEquals(now, saved.tymewearRefreshMs)
        assertNull(saved.tymewearRefreshError)
    }

    @Test fun `a failure is stored with its time and reason and keeps the thresholds`() {
        val before = signedIn.copy(bikeThresholds = bike)
        val saved = TymewearProfileSync.record(before, ProfileRefresh.Failed("HTTP 503", "TymewearException"), now)
        assertEquals(bike, saved.bikeThresholds)
        assertEquals(now, saved.tymewearRefreshMs)
        assertEquals("HTTP 503", saved.tymewearRefreshError)
    }

    @Test fun `a refusal is stored as a failure`() {
        val saved = TymewearProfileSync.record(signedIn, ProfileRefresh.AuthRefused, now)
        assertEquals("Tymewear refused the sign-in", saved.tymewearRefreshError)
    }

    @Test fun `nothing is stored after a sign-out during the read`() {
        val signedOut = Settings.DEFAULT
        assertEquals(signedOut, TymewearProfileSync.record(signedOut, ProfileRefresh.Refreshed(bike, null, reserve), now))
        assertEquals(signedOut, TymewearProfileSync.record(signedOut, ProfileRefresh.Failed("HTTP 503", "TymewearException"), now))
        assertEquals(signedOut, TymewearProfileSync.record(signedOut, ProfileRefresh.AuthRefused, now))
        assertEquals(signedIn, TymewearProfileSync.record(signedIn, ProfileRefresh.NotSignedIn, now))
    }

    @Test fun `a read is not stored over a refusal that came in meanwhile`() {
        val refused = signedIn.copy(tymewearSignInRefused = true)
        assertEquals(refused, TymewearProfileSync.record(refused, ProfileRefresh.Refreshed(bike, null, reserve), now))
    }

    @Test fun `sign-in message says whether the thresholds were read`() {
        assertEquals("Signed in to Tymewear", ProfileRefresh.signInMessage(ProfileRefresh.Refreshed(bike, null, null)))
        assertEquals("Signed in to Tymewear, but couldn't read your thresholds: HTTP 503", ProfileRefresh.signInMessage(ProfileRefresh.Failed("HTTP 503", "TymewearException")))
        assertEquals("Tymewear stopped accepting your sign-in. Sign in again.", ProfileRefresh.signInMessage(ProfileRefresh.AuthRefused))
    }

    @Test fun `refresh message for the Refresh button`() {
        assertEquals("Thresholds read from Tymewear", ProfileRefresh.refreshMessage(ProfileRefresh.Refreshed(bike, null, null)))
        assertEquals("Couldn't read thresholds: UnknownHostException", ProfileRefresh.refreshMessage(ProfileRefresh.Failed("UnknownHostException", "UnknownHostException")))
        assertEquals("Tymewear stopped accepting your sign-in. Sign in again.", ProfileRefresh.refreshMessage(ProfileRefresh.AuthRefused))
        assertEquals("Sign in to Tymewear first", ProfileRefresh.refreshMessage(ProfileRefresh.NotSignedIn))
    }

    @Test fun `card line shows the last read or the last failure`() {
        assertNull(ProfileRefresh.statusLine(signedIn, ZoneOffset.UTC))
        assertEquals("Thresholds read 14:13", ProfileRefresh.statusLine(signedIn.copy(tymewearRefreshMs = now), ZoneOffset.UTC))
        assertEquals("Couldn't read thresholds: HTTP 503", ProfileRefresh.statusLine(signedIn.copy(tymewearRefreshMs = now, tymewearRefreshError = "HTTP 503"), ZoneOffset.UTC))
    }
}
