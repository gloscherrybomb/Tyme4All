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

    /** True while any finished session is still `pending` inside the give-up window, i.e. a sync may yet happen. */
    fun anyPending(metas: List<SessionMeta>, nowMs: Long, giveUpMs: Long = Constants.SYNC_GIVE_UP_MS): Boolean =
        metas.any { m -> val end = m.endMs; end != null && m.syncState == "pending" && nowMs - end <= giveUpMs }
}
