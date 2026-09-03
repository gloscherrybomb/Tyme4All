package com.tymewear.run.android

import android.content.Context
import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.SettingsStore
import com.tymewear.run.domain.ZoneThresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("kbreathe_run", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    override fun load(): Settings = state.value
    override val changes: Flow<Settings> get() = state

    override fun save(settings: Settings) {
        prefs.edit()
            .putFloat("vt1", settings.thresholds.vt1.toFloat())
            .putFloat("vt2", settings.thresholds.vt2.toFloat())
            .putFloat("topz4", settings.thresholds.topZ4.toFloat())
            .putFloat("vo2max", settings.thresholds.vo2max.toFloat())
            .putFloat("resting_br", settings.reserve.restingBr.toFloat())
            .putFloat("max_br", settings.reserve.maxBr.toFloat())
            .putFloat("resting_hr", settings.reserve.restingHr.toFloat())
            .putFloat("max_hr", settings.reserve.maxHr.toFloat())
            .putString("sensor_id", settings.sensorId ?: "")
            .putBoolean("service_enabled", settings.serviceEnabled)
            .putString("intervals_key", settings.intervalsApiKey ?: "")
            .putInt("fallback_stop_min", settings.fallbackStopMinutes)
            .putInt("retention_days", settings.retentionDays)
            .apply()
        state.value = settings
    }

    private fun read(): Settings {
        val d = Settings.DEFAULT
        fun f(k: String, def: Double) = prefs.getFloat(k, def.toFloat()).toDouble()
        return Settings(
            thresholds = ZoneThresholds(f("vt1", d.thresholds.vt1), f("vt2", d.thresholds.vt2), f("topz4", d.thresholds.topZ4), f("vo2max", d.thresholds.vo2max)),
            reserve = ReserveSettings(f("resting_br", d.reserve.restingBr), f("max_br", d.reserve.maxBr), f("resting_hr", d.reserve.restingHr), f("max_hr", d.reserve.maxHr)),
            sensorId = prefs.getString("sensor_id", "")!!.ifBlank { null },
            serviceEnabled = prefs.getBoolean("service_enabled", d.serviceEnabled),
            intervalsApiKey = prefs.getString("intervals_key", "")!!.ifBlank { null },
            fallbackStopMinutes = prefs.getInt("fallback_stop_min", d.fallbackStopMinutes),
            retentionDays = prefs.getInt("retention_days", d.retentionDays),
        )
    }
}
