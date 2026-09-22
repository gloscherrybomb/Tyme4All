package com.tymewear.run.domain.relay

import com.tymewear.run.domain.InMemorySettingsStore
import com.tymewear.run.domain.LiveState
import com.tymewear.run.domain.session.SessionController
import com.tymewear.run.domain.session.SessionStore
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelayServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: RelayServer
    private lateinit var sessions: SessionController
    private val live = LiveState()

    @Before fun up() {
        sessions = SessionController(SessionStore(tmp.root))
        server = RelayServer("127.0.0.1", 0, live, InMemorySettingsStore(), sessions, clock = { 1_000_000 }, version = "t")
        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }
    @After fun down() = server.stop()

    private fun call(method: String, path: String, port: Int = server.listeningPort, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to body
    }

    @Test fun `health`() {
        val (code, body) = call("GET", "/health")
        assertEquals(200, code)
        assertEquals("""{"ok":true,"version":"t"}""", body)
    }

    @Test fun `live returns payload json`() {
        val (code, body) = call("GET", "/live")
        assertEquals(200, code)
        assertTrue(body.contains("\"status\":\"disconnected\""))
        assertTrue(body.contains("\"vt1\":73.0"))
    }

    @Test fun `session start and stop`() {
        val (c1, b1) = call("POST", "/session/start")
        assertEquals(200, c1)
        val id = sessions.activeSessionId!!
        assertEquals("""{"sessionId":"$id"}""", b1)
        val (_, b2) = call("POST", "/session/start")
        assertEquals(b1, b2)
        val (_, b3) = call("POST", "/session/stop")
        assertEquals("""{"sessionId":"$id"}""", b3)
        val (_, b4) = call("POST", "/session/stop")
        assertEquals("""{"sessionId":null}""", b4)
    }

    @Test fun `unknown route is 404`() {
        assertEquals(404, call("GET", "/nope").first)
        assertEquals(404, call("GET", "/session/start").first)   // wrong method
    }

    @Test fun `overlay page is served on loopback`() {
        val (code, body) = call("GET", "/overlay")
        assertEquals(200, code)
        assertTrue(body.startsWith("<!doctype html>"))
        assertTrue(body.contains("/live"))
    }

    @Test fun `lan mode requires the token and hides session control`() {
        val lan = RelayServer("127.0.0.1", 0, live, InMemorySettingsStore(), sessions, clock = { 1_000_000 }, version = "t", token = "s3cret")
        lan.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val p = lan.listeningPort
            assertEquals(401, call("GET", "/live", p).first)
            assertEquals(401, call("GET", "/live?token=wrong", p).first)
            assertEquals(401, call("GET", "/live", p, mapOf("Authorization" to "Bearer wrong")).first)
            assertEquals(200, call("GET", "/live?token=s3cret", p).first)
            assertEquals(200, call("GET", "/health", p, mapOf("Authorization" to "Bearer s3cret")).first)
            assertEquals(200, call("GET", "/overlay?token=s3cret", p).first)
            assertEquals(404, call("POST", "/session/start?token=s3cret", p).first)
            assertEquals(404, call("POST", "/session/stop?token=s3cret", p).first)
            assertNull(sessions.activeSessionId)
        } finally { lan.stop() }
    }
}
