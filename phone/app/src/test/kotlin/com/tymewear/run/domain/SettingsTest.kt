package com.tymewear.run.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test
    fun `defaults match the spec`() {
        val d = Settings.DEFAULT
        assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 130.0), d.thresholds)
        assertEquals(ReserveSettings(12.0, 55.0, 60.0, 190.0), d.reserve)
        assertEquals(3, d.idleStopMinutes)
        assertEquals(90, d.retentionDays)
        assertEquals(null, d.intervalsApiKey)
        assertEquals(true, d.serviceEnabled)
    }

    @Test
    fun `in memory store round trips and emits`() = runTest {
        val store = InMemorySettingsStore()
        val s = Settings.DEFAULT.copy(intervalsApiKey = "abc", sensorId = "1a2b")
        store.save(s)
        assertEquals(s, store.load())
        assertEquals(s, store.changes.first())
    }
}
