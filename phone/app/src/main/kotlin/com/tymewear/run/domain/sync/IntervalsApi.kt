package com.tymewear.run.domain.sync

import java.time.Instant

data class ActivitySummary(val id: String, val startDate: Instant, val name: String?, val type: String?, val source: String?, val deviceName: String?, val elapsedTimeS: Int? = null)
data class Stream(val type: String, val data: List<Double?>, val custom: Boolean = false)
data class UpdateStreamsResult(val updated: List<String>, val deleted: List<String>)
class IntervalsException(val httpCode: Int, message: String) : Exception(message)

interface IntervalsApi {
    fun listActivities(oldest: Instant, newest: Instant): List<ActivitySummary>
    fun getStreams(activityId: String, types: List<String>): List<Stream>
    fun putStreams(activityId: String, streams: List<Stream>): UpdateStreamsResult
    fun verifyKey(): Boolean
    /** The activity's original uploaded file (FIT, GPX or TCX), uncompressed. */
    fun originalFile(activityId: String): ByteArray
}
