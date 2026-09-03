package com.tymewear.run.domain.session

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlinx.serialization.encodeToString

/** Append-only JSON Lines writer for one session. Each append is flushed so a crash loses at most one line. */
class SessionLog(private val file: File) {
    private val writer = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)

    @Synchronized
    fun append(e: SessionEvent) {
        writer.write(sessionJson.encodeToString<SessionEvent>(e))
        writer.write("\n")
        writer.flush()
    }

    @Synchronized
    fun close() = writer.close()

    companion object {
        fun read(file: File): List<SessionEvent> {
            if (!file.exists()) return emptyList()
            return file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                try { sessionJson.decodeFromString(SessionEvent.serializer(), line) } catch (_: Exception) { null }
            }
        }
    }
}
