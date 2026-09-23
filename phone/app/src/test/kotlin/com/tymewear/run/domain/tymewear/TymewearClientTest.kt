package com.tymewear.run.domain.tymewear

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import com.tymewear.run.domain.ZoneThresholds

class TymewearClientTest {
    private val server = MockWebServer()
    private val creds = InMemoryCredentialStore()
    private lateinit var api: TymewearClient

    @Before fun up() { server.start(); api = TymewearClient(creds, server.url("/").toString().trimEnd('/')) }
    @After fun down() { server.shutdown() }

    private fun ok(body: String) = MockResponse().setBody(body)

    @Test fun `sign in posts the email as username and keeps the token`() {
        server.enqueue(ok("""{"token":"fake-token"}"""))
        api.signIn("test@example.com", "fake-pass")
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertEquals("/api/session/signin/", r.path)
        assertEquals("v2", r.getHeader("X-Source"))
        assertTrue(r.body.readUtf8().contains("\"username\":\"test@example.com\""))
        assertEquals("fake-token", creds.token); assertEquals("test@example.com", creds.email)
    }

    @Test fun `a refused sign in throws without the secrets`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"bad"}"""))
        val e = assertThrows(TymewearAuthException::class.java) { api.signIn("test@example.com", "fake-pass") }
        assertFalse(e.message!!.contains("fake-pass")); assertFalse(e.message!!.contains("test@example.com"))
        assertNull(creds.token)
    }

    @Test fun `reads the profile id`() {
        creds.token = "fake-token"
        server.enqueue(ok("""[{"id":2430,"email":"test@example.com","bike_ve_target_vt1":68.6}]"""))
        assertEquals(2430L, api.profile().id)
        assertEquals("Token fake-token", server.takeRequest().getHeader("Authorization"))
    }

    private fun marker(ve: Double) = """{"label":"x","metrics":{"ve":{"value":$ve},"hr":{"value":150}}}"""

    @Test fun `reads the five VE markers of the active thresholds`() {
        creds.token = "fake-token"
        server.enqueue(ok("""{"sport_type":"bike","thresholds":{"endurance":${marker(73.2)},"vt1":${marker(96.0)},"vt2":${marker(112.0)},"topz4":${marker(129.6)},"vo2max":${marker(182.3)}}}"""))
        assertEquals(ZoneThresholds(73.2, 96.0, 112.0, 129.6, 182.3), api.activeThresholds(2430, "bike"))
        assertEquals("/v2/api/users/2430/thresholds/active/?sport_type=bike", server.takeRequest().path)
    }

    @Test fun `active thresholds are null when absent or incomplete`() {
        creds.token = "fake-token"
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(api.activeThresholds(2430, "running"))
        server.enqueue(ok("""{"thresholds":{"endurance":${marker(74.9)},"vt1":${marker(94.5)}}}"""))
        assertNull(api.activeThresholds(2430, "running"))
    }

    @Test fun `reads resting and max values`() {
        creds.token = "fake-token"
        server.enqueue(ok("""{"br_rest":{"value":13.4},"hr_rest":{"value":50},"br_max":{"value":67.4},"hr_max":{"value":188},"ve_rest":{"value":9}}"""))
        val r = api.restingMax()!!
        assertEquals(13.4, r.restingBr, 1e-9); assertEquals(67.4, r.maxBr, 1e-9)
        assertEquals(50.0, r.restingHr, 1e-9); assertEquals(188.0, r.maxHr, 1e-9)
    }

    @Test fun `resting max is null when any value is missing`() {
        creds.token = "fake-token"
        server.enqueue(ok("""{"br_rest":{"value":13.4},"hr_rest":null}"""))
        assertNull(api.restingMax())
    }

    @Test fun `lists recent activities with their local listing time`() {
        creds.token = "fake-token"
        server.enqueue(ok("""{"next":null,"results":[{"id":"a1","time_stamp":"2026 Sep 20 11:18:21","tz_offset":"1.0"}]}"""))
        val l = api.recentActivities(2430, 30)
        assertEquals("/v2/api/activities-cursor/?user=2430&limit=30", server.takeRequest().path)
        assertEquals(Instant.parse("2026-09-20T10:18:21Z"), l.single().listedAt)
    }

    @Test fun `one activity carries its partner copies`() {
        creds.token = "fake-token"
        server.enqueue(ok("""{"id":"a1","x":[[1,2]],"third_party_activities":[{"id":962296,"partner":"INTERVALS_ICU","start_date":"2026-09-20T09:23:15Z"}]}"""))
        val a = api.activity("a1")
        assertEquals(listOf(PartnerCopy(962296, "INTERVALS_ICU", Instant.parse("2026-09-20T09:23:15Z"))), a.partnerCopies)
    }

    @Test fun `replace file is a multipart patch with fit_file`() {
        creds.token = "fake-token"
        server.enqueue(ok("""{"id":972545}"""))
        api.replaceFile(972545, byteArrayOf(14, 16))
        val r = server.takeRequest()
        assertEquals("PATCH", r.method); assertEquals("/api/activities/third-party/972545/", r.path)
        assertTrue(r.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(r.body.readUtf8().contains("name=\"fit_file\""))
    }

    @Test fun `a 401 refreshes once, then signs in again, then retries`() {
        creds.save("test@example.com", "fake-pass"); creds.token = "old"
        server.enqueue(MockResponse().setResponseCode(401))           // profile with old
        server.enqueue(MockResponse().setResponseCode(401))           // refresh refused
        server.enqueue(ok("""{"token":"new"}"""))                     // sign in
        server.enqueue(ok("""{"id":1}"""))                            // profile retried
        assertEquals(1L, api.profile().id)
        val paths = (1..4).map { server.takeRequest().path }
        assertEquals(listOf("/v2/api/profile/", "/api/session/refresh/", "/api/session/signin/", "/v2/api/profile/"), paths)
        assertEquals("new", creds.token)
    }

    @Test fun `gives up with an auth error when nothing works`() {
        creds.save("test@example.com", "fake-pass"); creds.token = "old"
        repeat(4) { server.enqueue(MockResponse().setResponseCode(401)) }
        val e = assertThrows(TymewearAuthException::class.java) { api.profile() }
        assertFalse(e.message!!.contains("fake"))
    }

    @Test fun `other errors carry the code but never the token`() {
        creds.token = "fake-token"
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops fake-token"))
        val e = assertThrows(TymewearException::class.java) { api.profile() }
        assertEquals(500, e.httpCode); assertFalse(e.message!!.contains("fake-token"))
    }

    @Test fun `a reply that is not JSON throws without quoting it`() {
        creds.token = "fake-token"
        server.enqueue(ok("""{"id":1,"email":"test@example.com""""))
        val e = assertThrows(TymewearException::class.java) { api.profile() }
        assertFalse(e.message!!.contains("test@example.com")); assertFalse(e.message!!.contains("{"))
    }

    @Test fun `a 401 with a working refresh retries with the new token`() {
        creds.token = "old"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(ok("""{"token":"new"}"""))
        server.enqueue(ok("""{"id":1}"""))
        assertEquals(1L, api.profile().id)
        server.takeRequest()
        val refresh = server.takeRequest()
        assertEquals("/api/session/refresh/", refresh.path); assertEquals("POST", refresh.method)
        assertEquals("Token old", refresh.getHeader("Authorization")); assertEquals(0L, refresh.bodySize)
        assertEquals("Token new", server.takeRequest().getHeader("Authorization"))
        assertEquals("new", creds.token)
    }

    @Test fun `authenticated requests carry X-Source v2`() {
        creds.token = "fake-token"
        server.enqueue(ok("""{"id":1}"""))
        api.profile()
        assertEquals("v2", server.takeRequest().getHeader("X-Source"))
    }

    @Test fun `listing times honour a fractional offset, a single-digit day, and give null when unreadable`() {
        creds.token = "fake-token"
        server.enqueue(ok("""{"results":[{"id":"a1","time_stamp":"2026 Sep 20 11:18:21","tz_offset":"5.5"},{"id":"a2","time_stamp":"2026 Sep 5 08:00:00","tz_offset":"1.0"},{"id":"a3","time_stamp":"yesterday","tz_offset":"1.0"}]}"""))
        val l = api.recentActivities(2430, 30)
        assertEquals(Instant.parse("2026-09-20T05:48:21Z"), l[0].listedAt)
        assertEquals(Instant.parse("2026-09-05T07:00:00Z"), l[1].listedAt)
        assertNull(l[2].listedAt)
    }

    @Test fun `a refused file names the code`() {
        creds.token = "fake-token"
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad fake-token"))
        val e = assertThrows(TymewearException::class.java) { api.replaceFile(972545, byteArrayOf(14, 16)) }
        assertEquals(400, e.httpCode); assertEquals("Tymewear refused the file (HTTP 400)", e.message)
    }
}
