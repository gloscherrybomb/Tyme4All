package com.tymewear.run.domain.relay

import com.tymewear.run.domain.LivePayload
import com.tymewear.run.domain.LiveState
import com.tymewear.run.domain.SettingsStore
import com.tymewear.run.domain.session.SessionController
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Loopback-only HTTP relay the Zepp side service polls. No auth: nothing off-device can reach 127.0.0.1. */
class RelayServer(
    port: Int,
    private val live: LiveState,
    private val settings: SettingsStore,
    private val sessions: SessionController,
    private val clock: () -> Long = System::currentTimeMillis,
    private val version: String = "dev",
) : NanoHTTPD("127.0.0.1", port) {

    private val json = Json { encodeDefaults = true }

    override fun serve(session: IHTTPSession): Response {
        val m = session.method
        return when {
            m == Method.GET && session.uri == "/health" ->
                ok(buildJsonObject { put("ok", JsonPrimitive(true)); put("version", JsonPrimitive(version)) }.toString())
            m == Method.GET && session.uri == "/live" ->
                ok(json.encodeToString(LivePayload.serializer(), live.payload(settings.load(), clock())))
            m == Method.POST && session.uri == "/session/start" ->
                ok(sessionJson(sessions.start(clock(), "watch")))
            m == Method.POST && session.uri == "/session/stop" ->
                ok(sessionJson(sessions.stop(clock(), "watch")))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
        }
    }

    private fun sessionJson(id: String?) =
        buildJsonObject { put("sessionId", id?.let { JsonPrimitive(it) } ?: JsonNull) }.toString()

    private fun ok(body: String) = newFixedLengthResponse(Response.Status.OK, "application/json", body)
}
