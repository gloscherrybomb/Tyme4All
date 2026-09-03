package com.tymewear.run.domain.session

import com.tymewear.run.domain.Constants
import com.tymewear.run.domain.Protocol

/**
 * Opens and closes recording sessions. The watch is the normal source of start and stop;
 * the fallback rules exist for the day the Zepp app dies mid-run.
 */
class SessionController(
    private val store: SessionStore,
    private val fallbackDisconnectedMs: Long = Constants.FALLBACK_STOP_DISCONNECTED_MS,
    private val maxSessionMs: Long = Constants.MAX_SESSION_MS,
) {
    private val lock = Any()
    private var log: SessionLog? = null
    private var id: String? = null
    private var startMs: Long = 0
    private var strapConnected = false
    private var disconnectedSinceMs: Long? = null

    var listener: ((String?) -> Unit)? = null

    val activeSessionId: String? get() = synchronized(lock) { id }

    fun start(nowMs: Long, source: String): String {
        var toNotify: String? = null
        val result: String
        synchronized(lock) {
            val existing = id
            if (existing != null) {
                result = existing
            } else {
                val newLog = store.create(nowMs, source)
                log = newLog
                val newId = SessionStore.idFor(nowMs)
                id = newId
                startMs = nowMs
                if (!strapConnected) disconnectedSinceMs = nowMs
                result = newId
                toNotify = newId
            }
        }
        toNotify?.let { notify(it) }
        return result
    }

    fun stop(nowMs: Long, reason: String): String? {
        var stopped = false
        val result: String?
        synchronized(lock) {
            result = stopLocked(nowMs, reason)
            stopped = result != null
        }
        if (stopped) notify(null)
        return result
    }

    /** Closes the currently open session. Must be called while holding [lock]. */
    private fun stopLocked(nowMs: Long, reason: String): String? {
        val current = id ?: return null
        log?.close()
        log = null
        id = null
        store.finish(current, nowMs, reason)
        return current
    }

    fun onBreath(d: Protocol.BreathingData, nowMs: Long) = synchronized(lock) {
        log?.append(SessionEvent.Breath(nowMs, d.breathRate, d.tidalVolume, d.ieRatio, d.tvRaw, d.inhaleDurationCs, d.exhaleDurationCs, d.timestamp40ms))
    }

    fun onBattery(pct: Int, nowMs: Long) = synchronized(lock) { log?.append(SessionEvent.Battery(nowMs, pct)) }

    fun onStrap(connected: Boolean, nowMs: Long): Unit = synchronized(lock) {
        if (connected == strapConnected) return
        strapConnected = connected
        disconnectedSinceMs = if (connected) null else nowMs
        log?.append(SessionEvent.Strap(nowMs, connected))
    }

    /** Apply the fallback rules. Returns the stop reason when it stopped the session. */
    fun tick(nowMs: Long): String? {
        var stopped = false
        val reason: String?
        synchronized(lock) {
            if (id == null) {
                reason = null
            } else {
                val since = disconnectedSinceMs
                val r = when {
                    since != null && nowMs - since > fallbackDisconnectedMs -> "strap-disconnected"
                    nowMs - startMs > maxSessionMs -> "max-length"
                    else -> null
                }
                if (r != null) {
                    stopLocked(nowMs, r)
                    stopped = true
                }
                reason = r
            }
        }
        if (stopped) notify(null)
        return reason
    }

    fun recoverOnStartup(nowMs: Long) = synchronized(lock) {
        val activeId = id
        for (m in store.list()) {
            if (m.endMs == null && m.id != activeId) store.finish(m.id, nowMs, "restart")
        }
    }

    private fun notify(v: String?) { listener?.invoke(v) }
}
