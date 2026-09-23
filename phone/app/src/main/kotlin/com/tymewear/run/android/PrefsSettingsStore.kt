package com.tymewear.run.android

import android.content.Context
import android.content.SharedPreferences
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
            .putFloat("z_endurance", settings.thresholds.endurance.toFloat())
            .putFloat("z_vt1", settings.thresholds.vt1.toFloat())
            .putFloat("z_vt2", settings.thresholds.vt2.toFloat())
            .putFloat("z_topz4", settings.thresholds.topZ4.toFloat())
            .putFloat("z_vo2max", settings.thresholds.vo2max.toFloat())
            .remove("vt1").remove("vt2").remove("topz4").remove("vo2max")
            .putFloat("resting_br", settings.reserve.restingBr.toFloat())
            .putFloat("max_br", settings.reserve.maxBr.toFloat())
            .putFloat("resting_hr", settings.reserve.restingHr.toFloat())
            .putFloat("max_hr", settings.reserve.maxHr.toFloat())
            .putString("sensor_id", settings.sensorId ?: "")
            .putBoolean("service_enabled", settings.serviceEnabled)
            .putString("intervals_key", settings.intervalsApiKey ?: "")
            .putInt("idle_stop_min", settings.idleStopMinutes)
            .putInt("retention_days", settings.retentionDays)
            .putBoolean("lan_overlay_enabled", settings.lanOverlayEnabled)
            .putString("lan_token", settings.lanToken ?: "")
            .putBoolean("tw_upload", settings.tymewearUpload)
            .putBoolean("tw_use_thresholds", settings.useTymewearThresholds)
            .putBoolean("tw_signed_in", settings.tymewearSignedIn)
            .putBoolean("tw_sign_in_refused", settings.tymewearSignInRefused)
            .putThresholds("tw_bike", settings.bikeThresholds)
            .putThresholds("tw_run", settings.runThresholds)
            .putReserve("tw_res", settings.tymewearReserve)
            .apply()
        state.value = settings
    }

    private fun SharedPreferences.Editor.putThresholds(prefix: String, t: ZoneThresholds?): SharedPreferences.Editor {
        putBoolean("${prefix}_set", t != null)
        if (t != null) {
            putFloat("${prefix}_endurance", t.endurance.toFloat())
            putFloat("${prefix}_vt1", t.vt1.toFloat())
            putFloat("${prefix}_vt2", t.vt2.toFloat())
            putFloat("${prefix}_topz4", t.topZ4.toFloat())
            putFloat("${prefix}_vo2max", t.vo2max.toFloat())
        }
        return this
    }

    private fun SharedPreferences.Editor.putReserve(prefix: String, r: ReserveSettings?): SharedPreferences.Editor {
        putBoolean("${prefix}_set", r != null)
        if (r != null) {
            putFloat("${prefix}_resting_br", r.restingBr.toFloat())
            putFloat("${prefix}_max_br", r.maxBr.toFloat())
            putFloat("${prefix}_resting_hr", r.restingHr.toFloat())
            putFloat("${prefix}_max_hr", r.maxHr.toFloat())
        }
        return this
    }

    private fun readTymewearThresholds(prefix: String): ZoneThresholds? {
        if (!prefs.getBoolean("${prefix}_set", false)) return null
        fun f(k: String) = prefs.getFloat("${prefix}_$k", 0f).toDouble()
        return ZoneThresholds(f("endurance"), f("vt1"), f("vt2"), f("topz4"), f("vo2max"))
    }

    private fun readTymewearReserve(prefix: String): ReserveSettings? {
        if (!prefs.getBoolean("${prefix}_set", false)) return null
        fun f(k: String) = prefs.getFloat("${prefix}_$k", 0f).toDouble()
        return ReserveSettings(f("resting_br"), f("max_br"), f("resting_hr"), f("max_hr"))
    }

    // v0.1.0 kept the four zone edges under Karoo names ("vt1".."vo2max", one name up);
    // v0.2.0 keeps Tymewear's names under "z_" keys. Old keys are read once, then removed on save.
    private fun readThresholds(d: ZoneThresholds, f: (String, Double) -> Double): ZoneThresholds = when {
        !prefs.contains("z_vt1") && prefs.contains("vt1") -> ZoneThresholds.migrateLegacy(
            f("vt1", d.endurance), f("vt2", d.vt1), f("topz4", d.vt2), f("vo2max", d.topZ4),
        )
        else -> ZoneThresholds(f("z_endurance", d.endurance), f("z_vt1", d.vt1), f("z_vt2", d.vt2), f("z_topz4", d.topZ4), f("z_vo2max", d.vo2max))
    }

    private fun read(): Settings {
        val d = Settings.DEFAULT
        fun f(k: String, def: Double) = prefs.getFloat(k, def.toFloat()).toDouble()
        return Settings(
            thresholds = readThresholds(d.thresholds, ::f),
            reserve = ReserveSettings(f("resting_br", d.reserve.restingBr), f("max_br", d.reserve.maxBr), f("resting_hr", d.reserve.restingHr), f("max_hr", d.reserve.maxHr)),
            sensorId = prefs.getString("sensor_id", "")!!.ifBlank { null },
            serviceEnabled = prefs.getBoolean("service_enabled", d.serviceEnabled),
            intervalsApiKey = prefs.getString("intervals_key", "")!!.ifBlank { null },
            idleStopMinutes = prefs.getInt("idle_stop_min", d.idleStopMinutes),
            retentionDays = prefs.getInt("retention_days", d.retentionDays),
            lanOverlayEnabled = prefs.getBoolean("lan_overlay_enabled", d.lanOverlayEnabled),
            lanToken = prefs.getString("lan_token", "")!!.ifBlank { null },
            tymewearUpload = prefs.getBoolean("tw_upload", d.tymewearUpload),
            useTymewearThresholds = prefs.getBoolean("tw_use_thresholds", d.useTymewearThresholds),
            tymewearSignedIn = prefs.getBoolean("tw_signed_in", d.tymewearSignedIn),
            bikeThresholds = readTymewearThresholds("tw_bike"),
            runThresholds = readTymewearThresholds("tw_run"),
            tymewearReserve = readTymewearReserve("tw_res"),
            tymewearSignInRefused = prefs.getBoolean("tw_sign_in_refused", d.tymewearSignInRefused),
        )
    }
}
