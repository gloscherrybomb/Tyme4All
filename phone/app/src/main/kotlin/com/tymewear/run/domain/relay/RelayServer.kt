package com.tymewear.run.domain.relay

import com.tymewear.run.domain.LivePayload
import com.tymewear.run.domain.LiveState
import com.tymewear.run.domain.SettingsStore
import com.tymewear.run.domain.session.SessionController
import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * HTTP relay. On loopback (token == null) it is open and also accepts session start/stop
 * from the watch. On the LAN (token != null) every request must carry the token and the
 * session endpoints do not exist: nothing on the network may start or stop recording.
 */
class RelayServer(
    host: String,
    port: Int,
    private val live: LiveState,
    private val settings: SettingsStore,
    private val sessions: SessionController,
    private val clock: () -> Long = System::currentTimeMillis,
    private val version: String = "dev",
    private val token: String? = null,
) : NanoHTTPD(host, port) {

    private val json = Json { encodeDefaults = true }

    override fun serve(session: IHTTPSession): Response {
        if (token != null && !authorized(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", """{"error":"unauthorized"}""")
        }
        val m = session.method
        return when {
            m == Method.GET && session.uri == "/health" ->
                ok(buildJsonObject { put("ok", JsonPrimitive(true)); put("version", JsonPrimitive(version)) }.toString())
            m == Method.GET && session.uri == "/live" ->
                ok(json.encodeToString(LivePayload.serializer(), live.payload(settings.load(), clock())))
            m == Method.GET && session.uri == "/overlay" ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", OverlayPage.HTML)
            token == null && m == Method.POST && session.uri == "/session/start" ->
                ok(sessionJson(sessions.start(clock(), "watch")))
            token == null && m == Method.POST && session.uri == "/session/stop" ->
                ok(sessionJson(sessions.stop(clock(), "watch")))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val expected = token ?: return true
        val fromQuery = session.parameters["token"]?.firstOrNull()
        val fromHeader = session.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        return listOfNotNull(fromQuery, fromHeader).any { MessageDigest.isEqual(it.toByteArray(), expected.toByteArray()) }
    }

    private fun sessionJson(id: String?) =
        buildJsonObject { put("sessionId", id?.let { JsonPrimitive(it) } ?: JsonNull) }.toString()

    private fun ok(body: String) = newFixedLengthResponse(Response.Status.OK, "application/json", body)
}
