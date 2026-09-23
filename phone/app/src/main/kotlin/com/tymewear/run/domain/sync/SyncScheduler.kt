package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Constants
import com.tymewear.run.domain.session.SessionMeta

object SyncScheduler {
    fun due(metas: List<SessionMeta>, nowMs: Long, intervalMs: Long = Constants.SYNC_POLL_INTERVAL_MS, giveUpMs: Long = Constants.SYNC_GIVE_UP_MS): List<SessionMeta> =
        metas.filter { m ->
            val end = m.endMs ?: return@filter false
            m.syncState == "pending" &&
                nowMs - end <= giveUpMs &&
                (m.lastSyncAttemptMs == null || nowMs - m.lastSyncAttemptMs >= intervalMs)
        }

    fun expired(metas: List<SessionMeta>, nowMs: Long, giveUpMs: Long = Constants.SYNC_GIVE_UP_MS): List<SessionMeta> =
        metas.filter { m -> val end = m.endMs; end != null && m.syncState == "pending" && nowMs - end > giveUpMs }

    /**
     * True while any finished session is still `pending` inside the give-up window, i.e. a sync may yet happen.
     * With [tymewear], a synced session whose Tymewear step is still pending counts too.
     */
    fun anyPending(metas: List<SessionMeta>, nowMs: Long, giveUpMs: Long = Constants.SYNC_GIVE_UP_MS, tymewear: Boolean = false): Boolean =
        metas.any { m ->
            val end = m.endMs
            end != null && nowMs - end <= giveUpMs && (m.syncState == "pending" || (tymewear && tymewearPending(m)))
        }

    /** Synced sessions whose Tymewear step is still pending and due another try. */
    fun dueTymewear(metas: List<SessionMeta>, nowMs: Long, intervalMs: Long = Constants.SYNC_POLL_INTERVAL_MS, giveUpMs: Long = Constants.SYNC_GIVE_UP_MS): List<SessionMeta> =
        metas.filter { m ->
            val end = m.endMs ?: return@filter false
            tymewearPending(m) &&
                nowMs - end <= giveUpMs &&
                (m.tymewearAttemptMs == null || nowMs - m.tymewearAttemptMs >= intervalMs)
        }

    fun expiredTymewear(metas: List<SessionMeta>, nowMs: Long, giveUpMs: Long = Constants.SYNC_GIVE_UP_MS): List<SessionMeta> =
        metas.filter { m -> val end = m.endMs; end != null && tymewearPending(m) && nowMs - end > giveUpMs }

    private fun tymewearPending(m: SessionMeta) = m.syncState == "synced" && m.tymewearState == "pending"
}
