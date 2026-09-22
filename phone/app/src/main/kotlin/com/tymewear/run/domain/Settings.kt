package com.tymewear.run.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

data class Settings(
    val thresholds: ZoneThresholds,
    val reserve: ReserveSettings,
    val sensorId: String?,
    val serviceEnabled: Boolean,
    val intervalsApiKey: String?,
    val idleStopMinutes: Int,
    val retentionDays: Int,
) {
    companion object {
        val DEFAULT = Settings(
            thresholds = ZoneThresholds(Constants.DEFAULT_VT1, Constants.DEFAULT_VT2, Constants.DEFAULT_TOP_Z4, Constants.DEFAULT_VO2MAX),
            reserve = ReserveSettings(Constants.DEFAULT_RESTING_BR, Constants.DEFAULT_MAX_BR, Constants.DEFAULT_RESTING_HR, Constants.DEFAULT_MAX_HR),
            sensorId = null,
            serviceEnabled = true,
            intervalsApiKey = null,
            idleStopMinutes = 3,
            retentionDays = Constants.RETENTION_DAYS,
        )
    }
}

interface SettingsStore {
    fun load(): Settings
    fun save(settings: Settings)
    val changes: Flow<Settings>
}

class InMemorySettingsStore(initial: Settings = Settings.DEFAULT) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override fun load(): Settings = state.value
    override fun save(settings: Settings) { state.value = settings }
    override val changes: Flow<Settings> get() = state
}
