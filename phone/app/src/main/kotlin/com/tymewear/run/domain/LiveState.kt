package com.tymewear.run.domain

import kotlinx.serialization.Serializable

enum class StrapStatus(val wire: String) { CONNECTED("connected"), STALE("stale"), DISCONNECTED("disconnected"), OFF("off") }

@Serializable data class ThresholdsDto(val endurance: Double, val vt1: Double, val vt2: Double, val topZ4: Double, val vo2max: Double)
@Serializable data class ReserveDto(val restingBr: Double, val maxBr: Double, val restingHr: Double, val maxHr: Double)

@Serializable
data class LivePayload(
    val ve: Double?, val br: Double?, val tv: Double?, val ie: Double?, val zone: Int,
    val batteryPct: Int?, val status: String, val sessionId: String?,
    val thresholds: ThresholdsDto, val reserve: ReserveDto, val updatedAtMs: Long?,
)

/** Latest strap values, smoothed the way the Karoo app smooths them. Thread-safe. */
class LiveState(stalenessMs: Long = Constants.BLE_DATA_STALENESS_TIMEOUT_MS) {
    private val lock = Any()
    private val freshness = DataFreshness(stalenessMs)
    private val brBuf = RollingBuffer(Constants.SMOOTHING_BREATHS)
    private val tvBuf = RollingBuffer(Constants.SMOOTHING_BREATHS)
    private var ie: Double? = null
    private var connected = false
    private var enabled = true
    private var battery: Int? = null
    private var sessionId: String? = null
    private var lastMs: Long? = null

    val smoothedBr: Double? get() = synchronized(lock) { if (lastMs == null) null else brBuf.average() }

    fun onBreath(d: Protocol.BreathingData, nowMs: Long) = synchronized(lock) {
        brBuf.add(d.breathRate); tvBuf.add(d.tidalVolume); ie = d.ieRatio
        freshness.recordUpdate(nowMs); lastMs = nowMs
    }
    fun onBattery(pct: Int) = synchronized(lock) { battery = pct }
    fun onConnected() = synchronized(lock) { connected = true }
    fun onDisconnected() = synchronized(lock) {
        connected = false; battery = null; brBuf.clear(); tvBuf.clear(); ie = null; freshness.reset(); lastMs = null
    }
    fun setServiceEnabled(b: Boolean) = synchronized(lock) { enabled = b }
    fun setSessionId(id: String?) = synchronized(lock) { sessionId = id }

    fun status(nowMs: Long): StrapStatus = synchronized(lock) {
        when {
            !enabled -> StrapStatus.OFF
            !connected -> StrapStatus.DISCONNECTED
            !freshness.isFresh(nowMs) -> StrapStatus.STALE
            else -> StrapStatus.CONNECTED
        }
    }

    fun payload(settings: Settings, nowMs: Long): LivePayload = synchronized(lock) {
        val st = status(nowMs)
        val live = st == StrapStatus.CONNECTED
        val br = if (live) brBuf.average() else null
        val tv = if (live) tvBuf.average() else null
        val ve = if (br != null && tv != null) br * tv else null
        LivePayload(
            ve = ve, br = br, tv = tv, ie = if (live) ie else null,
            zone = if (ve != null) ZoneClassifier.zoneFor(ve, settings.liveThresholds()) else 0,
            // Battery reflects the BLE link, not the breath stream: kept while the
            // link is connected (including when status is "stale"), cleared on disconnect.
            batteryPct = if (connected) battery else null,
            status = st.wire, sessionId = sessionId,
            thresholds = settings.liveThresholds().let { ThresholdsDto(it.endurance, it.vt1, it.vt2, it.topZ4, it.vo2max) },
            reserve = settings.effectiveReserve().let { ReserveDto(it.restingBr, it.maxBr, it.restingHr, it.maxHr) },
            updatedAtMs = lastMs,
        )
    }
}
