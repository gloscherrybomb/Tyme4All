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
    val lanOverlayEnabled: Boolean = false,
    val lanToken: String? = null,
    val tymewearUpload: Boolean = true,
    val useTymewearThresholds: Boolean = true,
    val tymewearSignedIn: Boolean = false,
    val bikeThresholds: ZoneThresholds? = null,
    val runThresholds: ZoneThresholds? = null,
    val tymewearReserve: ReserveSettings? = null,
    /** Tymewear refused the saved email and password; nothing calls Tymewear until the user signs in again. */
    val tymewearSignInRefused: Boolean = false,
) {
    private val tymewearApplies: Boolean get() = tymewearSignedIn && useTymewearThresholds

    /** Whether breathing goes to Tymewear: the upload step and its retries run only while this holds. */
    val tymewearActive: Boolean get() = tymewearSignedIn && tymewearUpload && !tymewearSignInRefused

    /** Why breathing is not going to Tymewear, for the user; null while [tymewearActive]. */
    fun tymewearOffReason(): String? = when {
        tymewearSignedIn && tymewearSignInRefused -> "Tymewear stopped accepting your sign-in. Sign in again in Settings."
        !tymewearSignedIn -> "Sign in to Tymewear in Settings first."
        !tymewearUpload -> "Sending breathing to Tymewear is switched off in Settings."
        else -> null
    }

    /** Whether the thresholds may be read from Tymewear. */
    val tymewearProfileReadable: Boolean get() = tymewearSignedIn && !tymewearSignInRefused

    /** True when some sport falls back to the manual thresholds, so they should stay editable. */
    fun manualThresholdsInUse(): Boolean = !tymewearApplies || bikeThresholds == null || runThresholds == null

    /** True when the manual reserve is what applies. */
    fun manualReserveInUse(): Boolean = !tymewearApplies || tymewearReserve == null

    /** Thresholds for the live view: bike. */
    fun liveThresholds(): ZoneThresholds = thresholdsFor(null)

    /** Tymewear's run thresholds for runs, walks and hikes, bike for everything else; manual when Tymewear's are off or missing. */
    fun thresholdsFor(activityType: String?): ZoneThresholds {
        val isRun = activityType != null && RUN_TYPES.any { it.equals(activityType, ignoreCase = true) }
        val tymewear = if (isRun) runThresholds else bikeThresholds
        return if (tymewearApplies && tymewear != null) tymewear else thresholds
    }

    fun effectiveReserve(): ReserveSettings =
        if (tymewearApplies && tymewearReserve != null) tymewearReserve else reserve

    companion object {
        private val RUN_TYPES = listOf("Run", "TrailRun", "VirtualRun", "Walk", "Hike")
        val DEFAULT = Settings(
            thresholds = ZoneThresholds(Constants.DEFAULT_ENDURANCE, Constants.DEFAULT_VT1, Constants.DEFAULT_VT2, Constants.DEFAULT_TOP_Z4, Constants.DEFAULT_VO2MAX),
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
