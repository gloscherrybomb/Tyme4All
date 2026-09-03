package com.tymewear.run.domain.sync

import java.time.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class IntervalsClientTest {
    private val server = MockWebServer()
    private lateinit var api: IntervalsClient

    @Before fun up() { server.start(); api = IntervalsClient("k3y", server.url("/").toString().trimEnd('/')) }
    @After fun down() { try { server.shutdown() } catch (e: Exception) { /* already shut down by a test */ } }

    @Test
    fun `lists activities with basic auth and utc window`() {
        server.enqueue(MockResponse().setBody("""[
          {"id":"i100","start_date":"2026-09-03T07:10:05Z","name":"Morning Run","type":"Run","source":"ZEPP","device_name":"Amazfit Cheetah 2 Ultra"},
          {"id":"i99","start_date":"2026-09-02T18:00:00","name":null,"type":"Ride","source":"GARMIN","device_name":null}
        ]"""))
        val list = api.listActivities(Instant.parse("2026-09-03T05:00:00Z"), Instant.parse("2026-09-03T11:00:00Z"))
        val req = server.takeRequest()
        assertEquals("/api/v1/athlete/0/activities?oldest=2026-09-03T05:00:00&newest=2026-09-03T11:00:00", req.path)
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("API_KEY:k3y".toByteArray()), req.getHeader("Authorization"))
        assertEquals(2, list.size)
        assertEquals(Instant.parse("2026-09-03T07:10:05Z"), list[0].startDate)
        assertEquals("Amazfit Cheetah 2 Ultra", list[0].deviceName)
        assertEquals(Instant.parse("2026-09-02T18:00:00Z"), list[1].startDate)
        assertNull(list[1].name)
    }

    @Test
    fun `parses start_date with negative and positive utc offsets`() {
        server.enqueue(MockResponse().setBody("""[
          {"id":"i1","start_date":"2026-09-03T07:10:05-05:00","name":null,"type":"Run","source":"ZEPP","device_name":null},
          {"id":"i2","start_date":"2026-09-03T07:10:05+02:00","name":null,"type":"Run","source":"ZEPP","device_name":null}
        ]"""))
        val list = api.listActivities(Instant.parse("2026-09-03T05:00:00Z"), Instant.parse("2026-09-03T11:00:00Z"))
        server.takeRequest()
        assertEquals(Instant.parse("2026-09-03T12:10:05Z"), list[0].startDate)
        assertEquals(Instant.parse("2026-09-03T05:10:05Z"), list[1].startDate)
    }

    @Test
    fun `gets streams with nulls`() {
        server.enqueue(MockResponse().setBody("""[{"type":"time","data":[0,1,2]},{"type":"heartrate","data":[120,null,122]}]"""))
        val s = api.getStreams("i100", listOf("time", "heartrate"))
        assertEquals("/api/v1/activity/i100/streams.json?types=time,heartrate", server.takeRequest().path)
        assertEquals(listOf(0.0, 1.0, 2.0), s[0].data)
        assertEquals(listOf(120.0, null, 122.0), s[1].data)
    }

    @Test
    fun `puts custom streams`() {
        server.enqueue(MockResponse().setBody("""{"updated":["TymeVentilation"],"deleted":[]}"""))
        val r = api.putStreams("i100", listOf(Stream("TymeVentilation", listOf(40.0, null), custom = true)))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/activity/i100/streams", req.path)
        assertEquals("""[{"type":"TymeVentilation","custom":true,"data":[40.0,null]}]""", req.body.readUtf8())
        assertEquals(listOf("TymeVentilation"), r.updated)
    }

    @Test
    fun `verify key`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(api.verifyKey())
        assertEquals("/api/v1/athlete/0", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(false, api.verifyKey())
    }

    @Test
    fun `verify key returns false when the server is unreachable`() {
        val url = server.url("/").toString().trimEnd('/')
        server.shutdown()
        val offlineApi = IntervalsClient("k3y", url)
        assertEquals(false, offlineApi.verifyKey())
    }

    @Test
    fun `non 2xx throws with code`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("nope"))
        try { api.getStreams("i1", listOf("time")); fail() } catch (e: IntervalsException) { assertEquals(403, e.httpCode) }
    }
}
