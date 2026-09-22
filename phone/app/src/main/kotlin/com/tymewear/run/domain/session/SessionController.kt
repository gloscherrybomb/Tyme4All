package com.tymewear.run.domain.session

import com.tymewear.run.domain.Constants
import com.tymewear.run.domain.Protocol

/**
 * Opens and closes recording sessions. The strap is the normal trigger: the first breath
 * packet opens a session and an idle timeout with no packets closes it. Watch and manual
 * start/stop still work but are no longer required.
 */
class SessionController(
    private val store: SessionStore,
    idleStopMs: Long = Constants.IDLE_STOP_MS,
    private val maxSessionMs: Long = Constants.MAX_SESSION_MS,
) {
    @Volatile var idleStopMs: Long = idleStopMs
    private val lock = Any()
    private var log: SessionLog? = null
    private var id: String? = null
    private var startMs: Long = 0
    private var lastBreathMs: Long? = null

    var listener: ((String?) -> Unit)? = null

    val activeSessionId: String? get() = synchronized(lock) { id }

    fun start(nowMs: Long, source: String): String {
        var opened: String? = null
        val result = synchronized(lock) { id ?: openLocked(nowMs, source).also { opened = it } }
        opened?.let { notify(it) }
        return result
    }

    fun stop(nowMs: Long, reason: String): String? {
        val result = synchronized(lock) { stopLocked(nowMs, reason) }
        if (result != null) notify(null)
        return result
    }

    fun onBreath(d: Protocol.BreathingData, nowMs: Long) {
        var opened: String? = null
        synchronized(lock) {
            if (id == null) opened = openLocked(nowMs, "strap")
            lastBreathMs = nowMs
            log?.append(SessionEvent.Breath(nowMs, d.breathRate, d.tidalVolume, d.ieRatio, d.tvRaw, d.inhaleDurationCs, d.exhaleDurationCs, d.timestamp40ms))
        }
        opened?.let { notify(it) }
    }

    fun onBattery(pct: Int, nowMs: Long) = synchronized(lock) { log?.append(SessionEvent.Battery(nowMs, pct)) }

    fun onStrap(connected: Boolean, nowMs: Long): Unit = synchronized(lock) {
        log?.append(SessionEvent.Strap(nowMs, connected))
    }

    /** Apply the idle and maximum-length rules. Returns the stop reason when it stopped the session. */
    fun tick(nowMs: Long): String? {
        val reason = synchronized(lock) {
            if (id == null) return@synchronized null
            val since = lastBreathMs ?: startMs
            val r = when {
                nowMs - since > idleStopMs -> "idle"
                nowMs - startMs > maxSessionMs -> "max-length"
                else -> null
            }
            if (r != null) stopLocked(nowMs, r)
            r
        }
        if (reason != null) notify(null)
        return reason
    }

    fun recoverOnStartup(nowMs: Long) = synchronized(lock) {
        val activeId = id
        for (m in store.list()) {
            if (m.endMs == null && m.id != activeId) {
                val lastEventMs = store.events(m.id).lastOrNull()?.tMs ?: m.startMs
                val endMs = lastEventMs.coerceAtMost(nowMs)
                store.finish(m.id, endMs, "restart")
            }
        }
    }

    /** Must be called while holding [lock]. Session ids have one-second resolution, so a
     *  session reopened in the same second as one that just closed is pushed forward until
     *  its id is free rather than overwriting the closed session's files. */
    private fun openLocked(nowMs: Long, source: String): String {
        var t = nowMs
        while (store.meta(SessionStore.idFor(t)) != null) t += 1_000
        val newLog = store.create(t, source)
        val newId = SessionStore.idFor(t)
        log = newLog
        id = newId
        startMs = t
        lastBreathMs = null
        return newId
    }

    /** Must be called while holding [lock]. */
    private fun stopLocked(nowMs: Long, reason: String): String? {
        val current = id ?: return null
        log?.close()
        log = null
        id = null
        lastBreathMs = null
        store.finish(current, nowMs, reason)
        return current
    }

    private fun notify(v: String?) { listener?.invoke(v) }
}
