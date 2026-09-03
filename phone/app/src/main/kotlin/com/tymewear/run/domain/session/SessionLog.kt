package com.tymewear.run.domain.session

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString

/** Append-only JSON Lines writer for one session. Each append is flushed so a crash loses at most one line. */
class SessionLog(private val file: File) {
    private val lock = fileLocks.getOrPut(file.canonicalPath) { Any() }

    init {
        // If a previous writer died mid-line (wrote the JSON but not the trailing '\n'),
        // terminate that partial line now so it is dropped (not merged) on the next read,
        // and so this instance's first append starts on its own line.
        if (file.exists() && file.length() > 0L) {
            val lastByte = java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(raf.length() - 1)
                raf.readByte()
            }
            if (lastByte != '\n'.code.toByte()) {
                FileOutputStream(file, true).use { it.write('\n'.code) }
            }
        }
    }

    private val writer = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)

    fun append(e: SessionEvent) {
        synchronized(lock) {
            writer.write(sessionJson.encodeToString<SessionEvent>(e) + "\n")
            writer.flush()
        }
    }

    fun close() {
        synchronized(lock) { writer.close() }
    }

    companion object {
        private val fileLocks = ConcurrentHashMap<String, Any>()

        fun read(file: File): List<SessionEvent> {
            if (!file.exists()) return emptyList()
            return file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                try { sessionJson.decodeFromString(SessionEvent.serializer(), line) } catch (_: Exception) { null }
            }
        }
    }
}
