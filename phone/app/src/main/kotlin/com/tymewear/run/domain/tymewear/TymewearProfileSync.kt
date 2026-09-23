package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.Settings

/** Copies Tymewear's per-sport thresholds and resting/max values into the settings. Exceptions reach the caller. */
object TymewearProfileSync {
    fun apply(settings: Settings, api: TymewearApi): Settings {
        val id = api.profile().id
        val bike = api.activeThresholds(id, "bike")
        val run = api.activeThresholds(id, "running")
        val reserve = api.restingMax()
        return settings.copy(
            bikeThresholds = bike ?: settings.bikeThresholds,
            runThresholds = run ?: settings.runThresholds,
            tymewearReserve = reserve ?: settings.tymewearReserve,
        )
    }
}
