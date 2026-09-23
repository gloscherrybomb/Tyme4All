package com.tymewear.run.domain.fit

import com.garmin.fit.BufferEncoder
import com.garmin.fit.Decode
import com.garmin.fit.DeveloperDataIdMesg
import com.garmin.fit.DeveloperField
import com.garmin.fit.FieldDescriptionMesg
import com.garmin.fit.Fit
import com.garmin.fit.FitBaseType
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.tymewear.run.domain.sync.Stream
import com.tymewear.run.domain.sync.StreamCodes
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.math.floor

class NotFitException(message: String) : Exception(message)

/**
 * Adds the breathing streams to an activity's original FIT file as Tymewear's own developer fields
 * (the names K-Breathe writes on the Karoo), so Tymewear reads them like one of its own recordings.
 * Every other message and field is written back unchanged.
 */
object BreathingFitMerger {
    private const val FIT_EPOCH_OFFSET_S = 631_065_600L
    /** "tyme4all" then zeros and a 1: Tyme4All's own application id. */
    private val APP_ID = byteArrayOf(0x74, 0x79, 0x6d, 0x65, 0x34, 0x61, 0x6c, 0x6c, 0, 0, 0, 0, 0, 0, 0, 1)

    private data class Field(val number: Short, val name: String, val units: String, val stream: String)
    private val FIELDS = listOf(
        Field(0, "tyme_breath_rate", "brpm", StreamCodes.BR),
        Field(1, "tyme_tidal_volume", "vol/br", StreamCodes.TV),
        Field(2, "tyme_minute_volume", "vol/min", StreamCodes.VE),
        Field(3, "tyme_inhale_exhale_ratio", "sec/sec", StreamCodes.IE),
    )

    fun merge(original: ByteArray, activityStart: Instant, time: List<Double?>, streams: List<Stream>): ByteArray {
        if (!Decode().isFileFit(ByteArrayInputStream(original))) throw NotFitException("original file is not a FIT file")
        val mesgs = ArrayList<Mesg>()
        try {
            Decode().read(ByteArrayInputStream(original)) { mesgs.add(it) }
        } catch (e: FitRuntimeException) {
            throw NotFitException("original FIT file could not be read: ${e.message}")
        }

        // Unix second -> index into the streams, from Intervals.icu's own time axis.
        val indexBySecond = HashMap<Long, Int>(time.size * 2)
        time.forEachIndexed { i, t -> if (t != null) indexBySecond.putIfAbsent(activityStart.epochSecond + floor(t).toLong(), i) }
        val byCode = streams.associateBy { it.type }

        val index = ((mesgs.filter { it.num == MesgNum.DEVELOPER_DATA_ID }
            .mapNotNull { DeveloperDataIdMesg(it).developerDataIndex?.toInt() }.maxOrNull() ?: -1) + 1).toShort()
        val dataId = DeveloperDataIdMesg().apply {
            developerDataIndex = index
            APP_ID.forEachIndexed { i, b -> setApplicationId(i, b) }
            applicationVersion = 1L
        }
        val descriptions = FIELDS.map { f ->
            f to FieldDescriptionMesg().apply {
                developerDataIndex = index; fieldDefinitionNumber = f.number
                fitBaseTypeId = FitBaseType.FLOAT32; setFieldName(0, f.name); setUnits(0, f.units)
            }
        }

        val out = BufferEncoder(Fit.ProtocolVersion.V2_0)
        var declared = false
        for (m in mesgs) {
            if (m.num == MesgNum.RECORD) {
                if (!declared) { out.write(dataId); descriptions.forEach { out.write(it.second) }; declared = true }
                val ts = RecordMesg(m).timestamp?.timestamp
                val i = ts?.let { indexBySecond[it + FIT_EPOCH_OFFSET_S] }
                if (i != null) for ((f, desc) in descriptions) {
                    val v = byCode[f.stream]?.data?.getOrNull(i) ?: continue
                    m.addDeveloperField(DeveloperField(desc, dataId).apply { value = v.toFloat() })
                }
            }
            out.write(m)
        }
        return out.close()
    }
}
