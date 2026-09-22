package com.tymewear.run.domain.session

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class SessionMeta(
    val id: String,
    val startMs: Long,
    val endMs: Long? = null,
    val syncState: String = "pending",   // pending | synced | unmatched | failed | skipped | discarded
    val activityId: String? = null,
    val syncMessage: String? = null,
    val lastSyncAttemptMs: Long? = null,
)

class SessionStore(private val root: File) {
    init { root.mkdirs() }

    private fun logFile(id: String) = File(root, "$id.jsonl")
    private fun metaFile(id: String) = File(root, "$id.meta.json")

    fun create(startMs: Long, source: String): SessionLog {
        val id = idFor(startMs)
        writeMeta(SessionMeta(id = id, startMs = startMs))
        val log = SessionLog(logFile(id))
        log.append(SessionEvent.Start(startMs, source))
        return log
    }

    fun openLog(id: String): SessionLog = SessionLog(logFile(id))

    fun finish(id: String, endMs: Long, reason: String) {
        openLog(id).apply { append(SessionEvent.Stop(endMs, reason)); close() }
        updateMeta(id) { it.copy(endMs = endMs) }
    }

    fun list(): List<SessionMeta> =
        root.listFiles { f -> f.name.endsWith(".meta.json") }
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.startMs }
            ?: emptyList()

    fun meta(id: String): SessionMeta? = metaFile(id).takeIf { it.exists() }?.let { readMeta(it) }

    @Synchronized
    fun updateMeta(id: String, f: (SessionMeta) -> SessionMeta) {
        val current = meta(id) ?: return
        writeMeta(f(current))
    }

    fun events(id: String): List<SessionEvent> = SessionLog.read(logFile(id))

    /** Marks every finished, still-pending session as discarded so the sync stops waiting for it.
     *  The data is kept; "Retry sync" on the Sessions tab can still push it later. Returns the ids. */
    fun discardPending(nowMs: Long): List<String> =
        list().filter { it.endMs != null && it.syncState == "pending" }.map { m ->
            updateMeta(m.id) { it.copy(syncState = "discarded", syncMessage = "discarded by user", lastSyncAttemptMs = nowMs) }
            m.id
        }

    fun prune(nowMs: Long, retentionDays: Int): Int {
        val cutoff = nowMs - retentionDays * 86_400_000L
        var n = 0
        for (m in list()) {
            if (m.startMs < cutoff) {
                logFile(m.id).delete(); metaFile(m.id).delete(); n++
            }
        }
        return n
    }

    private fun writeMeta(m: SessionMeta) = metaFile(m.id).writeText(sessionJson.encodeToString(m))
    private fun readMeta(f: File): SessionMeta? =
        try { sessionJson.decodeFromString(SessionMeta.serializer(), f.readText()) } catch (_: Exception) { null }

    companion object {
        private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        fun idFor(startMs: Long): String = fmt.format(Instant.ofEpochMilli(startMs))
    }
}
