package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.ZoneThresholds
import org.junit.Assert.*
import org.junit.Test

class TymewearProfileSyncTest {
    @Test fun `zones match Tymewear's own bands`() {
        val t = ZoneThresholds(74.9, 94.5, 123.1, 149.8, 230.1)
        assertEquals(1, com.tymewear.run.domain.ZoneClassifier.zoneFor(60.0, t))    // Z1, below Endurance
        assertEquals(2, com.tymewear.run.domain.ZoneClassifier.zoneFor(80.0, t))    // Z2, Endurance to VT1
        assertEquals(3, com.tymewear.run.domain.ZoneClassifier.zoneFor(100.0, t))   // Z3, VT1 to VT2
        assertEquals(4, com.tymewear.run.domain.ZoneClassifier.zoneFor(130.0, t))   // Z4, VT2 to Top Z4
        assertEquals(5, com.tymewear.run.domain.ZoneClassifier.zoneFor(160.0, t))   // Z5, Top Z4 and above
    }

    @Test fun `apply keeps stored values when Tymewear returns nothing new`() {
        val stored = ZoneThresholds(1.0, 2.0, 3.0, 4.0, 5.0)
        val sports = mutableListOf<String>()
        val api = object : TymewearApi {
            override fun signIn(email: String, password: String) {}
            override fun profile() = TymewearProfile(4242)
            override fun activeThresholds(userId: Long, sport: String): ZoneThresholds? {
                assertEquals(4242L, userId)
                sports += sport
                return if (sport == "bike") ZoneThresholds(73.2, 96.0, 112.0, 129.6, 182.3) else null
            }
            override fun restingMax(): ReserveSettings? = null
            override fun recentActivities(userId: Long, limit: Int) = emptyList<TymewearActivity>()
            override fun activity(id: String) = throw UnsupportedOperationException()
            override fun replaceFile(tpId: Long, fit: ByteArray) = throw UnsupportedOperationException()
        }
        val out = TymewearProfileSync.apply(Settings.DEFAULT.copy(runThresholds = stored), api)
        assertEquals(ZoneThresholds(73.2, 96.0, 112.0, 129.6, 182.3), out.bikeThresholds)
        assertEquals(stored, out.runThresholds)
        assertNull(out.tymewearReserve)
        assertEquals(listOf("bike", "running"), sports)
    }
}
