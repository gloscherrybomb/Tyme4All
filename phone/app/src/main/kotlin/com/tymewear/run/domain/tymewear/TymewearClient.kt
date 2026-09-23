package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.ZoneThresholds
import com.tymewear.run.domain.inOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Tymewear's undocumented API, limited to the calls Tyme4All needs. Error messages carry only the
 * method, path and HTTP code: reply bodies may echo the email, password or token.
 */
class TymewearClient(
    private val credentials: CredentialStore,
    baseUrl: String = "https://api.tymewear.com",
    private val client: OkHttpClient = OkHttpClient(),
) : TymewearApi {

    private val baseUrl = baseUrl.removeSuffix("/")
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()
    private val listedFormat = DateTimeFormatter.ofPattern("yyyy MMM d HH:mm:ss", Locale.ENGLISH)

    override fun signIn(email: String, password: String) {
        credentials.token = requestToken(email, password)
        credentials.save(email, password)
    }

    override fun profile(): TymewearProfile {
        val root = getJson("/v2/api/profile/")
        val o = (if (root is JsonArray) root.firstOrNull() else root) as? JsonObject
            ?: throw TymewearException(200, "Tymewear profile reply had no profile")
        val id = o.long("id") ?: throw TymewearException(200, "Tymewear profile reply had no id")
        return TymewearProfile(id)
    }

    override fun activeThresholds(userId: Long, sport: String): ZoneThresholds? {
        val path = "/v2/api/users/$userId/thresholds/active/?sport_type=$sport"
        val body = try {
            getJson(path)
        } catch (e: TymewearException) {
            if (e.httpCode == 404) return null else throw e
        }
        val markers = body.obj()?.get("thresholds")?.obj() ?: return null
        val ve = MARKERS.map { k ->
            markers[k]?.obj()?.get("metrics")?.obj()?.get("ve")?.obj()?.double("value") ?: return null
        }
        return ZoneThresholds(ve[0], ve[1], ve[2], ve[3], ve[4]).takeIf { it.inOrder() }
    }

    override fun restingMax(): ReserveSettings? {
        val o = getJson("/v2/api/resting-max-values/").obj() ?: return null
        fun v(k: String) = o[k]?.obj()?.double("value")
        return ReserveSettings(
            restingBr = v("br_rest") ?: return null,
            maxBr = v("br_max") ?: return null,
            restingHr = v("hr_rest") ?: return null,
            maxHr = v("hr_max") ?: return null,
        )
    }

    override fun recentActivities(userId: Long, limit: Int): List<TymewearActivity> {
        val o = getJson("/v2/api/activities-cursor/?user=$userId&limit=$limit").obj()
        val results = o?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { e ->
            val a = e.obj() ?: return@mapNotNull null
            val id = a.str("id") ?: return@mapNotNull null
            TymewearActivity(id, listedAt(a.str("time_stamp"), a.str("tz_offset")), emptyList())
        }
    }

    override fun activity(id: String): TymewearActivity {
        val o = getJson("/v2/api/activities/$id/").obj()
            ?: throw TymewearException(200, "Tymewear activity reply was not an object")
        val copies = (o["third_party_activities"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e.obj() ?: return@mapNotNull null
            val tpId = c.long("id") ?: return@mapNotNull null
            val partner = c.str("partner") ?: return@mapNotNull null
            val start = c.str("start_date")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            PartnerCopy(tpId, partner, start)
        }
        return TymewearActivity(o.str("id") ?: id, null, copies)
    }

    override fun replaceFile(tpId: Long, fit: ByteArray) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("fit_file", "tyme4all.fit", fit.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val path = "/api/activities/third-party/$tpId/"
        authed("PATCH", path, body).use { r ->
            if (r.code in 400..499) throw TymewearException(r.code, "Tymewear refused the file (HTTP ${r.code})")
            if (!r.isSuccessful) throw httpError("PATCH", path, r.code)
        }
    }

    /** POSTs the sign-in and returns the token, touching no stored state. */
    private fun requestToken(email: String, password: String): String {
        val payload = buildJsonObject { put("username", email); put("password", password) }.toString()
        val req = base("/api/session/signin/").post(payload.toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 400 || r.code == 401 || r.code == 403) throw TymewearAuthException("Tymewear did not accept the email and password")
            if (!r.isSuccessful) throw TymewearException(r.code, "Tymewear sign-in returned HTTP ${r.code}")
            return tokenOf(r) ?: throw TymewearException(r.code, "Tymewear sign-in reply had no token")
        }
    }

    /** POSTs the refresh, empty body, old token in the header; the new token, or null if Tymewear refused it. */
    private fun refresh(old: String): String? {
        val req = base("/api/session/refresh/").header("Authorization", "Token $old").post(ByteArray(0).toRequestBody(null)).build()
        client.newCall(req).execute().use { r -> return if (r.code == 200) tokenOf(r) else null }
    }

    private fun getJson(path: String): JsonElement =
        authed("GET", path, null).use { r ->
            if (!r.isSuccessful) throw httpError("GET", path, r.code)
            parse(r.body?.string() ?: "", "GET", path, r.code)
        }

    /** Parses a reply. kotlinx's own messages quote the input, which may hold the email, so they are dropped. */
    private fun parse(body: String, method: String, path: String, code: Int): JsonElement =
        try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw TymewearException(code, "Tymewear $method ${path.substringBefore('?')} reply was not JSON")
        } catch (e: IllegalArgumentException) {
            throw TymewearException(code, "Tymewear $method ${path.substringBefore('?')} reply was not JSON")
        }

    /**
     * Sends with the current token. On a 401: refresh once, else sign in again with the stored email
     * and password, then retry once. The returned response is never a 401; the caller closes it.
     */
    private fun authed(method: String, path: String, body: RequestBody?): Response {
        fun send(): Response {
            val b = base(path).method(method, body)
            credentials.token?.let { b.header("Authorization", "Token $it") }
            return client.newCall(b.build()).execute()
        }
        val first = send()
        if (first.code != 401) return first
        first.close()

        val refreshed = credentials.token?.let { refresh(it) }
        if (refreshed != null) {
            credentials.token = refreshed
        } else {
            val email = credentials.email
            val password = credentials.password
            if (email == null || password == null) throw stopped()
            credentials.token = try {
                requestToken(email, password)
            } catch (e: TymewearAuthException) {
                throw stopped()
            }
        }
        val retry = send()
        if (retry.code == 401) {
            retry.close()
            throw stopped()
        }
        return retry
    }

    private fun base(path: String): Request.Builder = Request.Builder().url(baseUrl + path).header("X-Source", "v2")

    private fun tokenOf(r: Response): String? =
        runCatching { json.parseToJsonElement(r.body?.string() ?: "").obj()?.str("token") }.getOrNull()

    private fun stopped() = TymewearAuthException("Tymewear stopped accepting the saved sign-in")

    private fun httpError(method: String, path: String, code: Int) =
        TymewearException(code, "Tymewear $method ${path.substringBefore('?')} returned HTTP $code")

    private fun listedAt(timeStamp: String?, tzOffset: String?): Instant? = runCatching {
        val offsetS = (tzOffset!!.toDouble() * 3600).roundToInt()
        LocalDateTime.parse(timeStamp!!, listedFormat).toInstant(ZoneOffset.ofTotalSeconds(offsetS))
    }.getOrNull()

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.double(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

    private companion object {
        val MARKERS = listOf("endurance", "vt1", "vt2", "topz4", "vo2max")
    }
}
