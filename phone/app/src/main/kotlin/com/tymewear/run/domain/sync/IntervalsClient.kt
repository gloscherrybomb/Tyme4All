package com.tymewear.run.domain.sync

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class IntervalsClient(
    apiKey: String,
    private val baseUrl: String = "https://intervals.icu",
    private val client: OkHttpClient = OkHttpClient(),
) : IntervalsApi {

    private val auth = Credentials.basic("API_KEY", apiKey)
    private val json = Json { ignoreUnknownKeys = true }
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    override fun listActivities(oldest: Instant, newest: Instant): List<ActivitySummary> {
        val body = get("/api/v1/athlete/0/activities?oldest=${fmt.format(oldest)}&newest=${fmt.format(newest)}")
        return json.parseToJsonElement(body).jsonArray.map { e ->
            val o = e.jsonObject
            ActivitySummary(
                id = o.str("id")!!,
                startDate = parseInstant(o.str("start_date")!!),
                name = o.str("name"),
                type = o.str("type"),
                source = o.str("source"),
                deviceName = o.str("device_name"),
            )
        }
    }

    override fun getStreams(activityId: String, types: List<String>): List<Stream> {
        val body = get("/api/v1/activity/$activityId/streams.json?types=${types.joinToString(",")}")
        return json.parseToJsonElement(body).jsonArray.map { e ->
            val o = e.jsonObject
            Stream(
                type = o.str("type")!!,
                data = (o["data"] as? JsonArray)?.map { v -> (v as? JsonPrimitive)?.doubleOrNull } ?: emptyList(),
                custom = (o["custom"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    override fun putStreams(activityId: String, streams: List<Stream>): UpdateStreamsResult {
        val payload = buildJsonArray {
            for (s in streams) {
                add(buildJsonObject {
                    put("type", JsonPrimitive(s.type))
                    put("custom", JsonPrimitive(s.custom))
                    put("data", buildJsonArray { for (v in s.data) add(v?.let { JsonPrimitive(it) } ?: JsonNull) })
                })
            }
        }.toString()
        val req = Request.Builder()
            .url(baseUrl + "/api/v1/activity/$activityId/streams")
            .header("Authorization", auth)
            .put(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = execute(req)
        val o = json.parseToJsonElement(body).jsonObject
        return UpdateStreamsResult(
            updated = o["updated"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            deleted = o["deleted"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        )
    }

    override fun verifyKey(): Boolean = try {
        get("/api/v1/athlete/0")
        true
    } catch (e: IntervalsException) {
        false
    }

    private fun get(path: String): String =
        execute(Request.Builder().url(baseUrl + path).header("Authorization", auth).get().build())

    private fun execute(req: Request): String {
        client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IntervalsException(r.code, "HTTP ${r.code} ${req.method} ${req.url.encodedPath}: ${text.take(200)}")
            return text
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun parseInstant(s: String): Instant =
        if (s.endsWith("Z") || s.contains("+")) Instant.parse(s)
        else LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)
}
