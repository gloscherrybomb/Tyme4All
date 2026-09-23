package com.tymewear.run.domain.fit

import com.garmin.fit.*
import com.tymewear.run.domain.sync.Stream
import com.tymewear.run.domain.sync.StreamCodes
import java.io.ByteArrayInputStream
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BreathingFitMergerTest {
    private val fitEpochOffset = 631_065_600L
    private val start = Instant.parse("2026-09-20T09:23:15Z")
    private val startFit = start.epochSecond - fitEpochOffset

    /** A small activity shaped like a watch file: file id, an existing developer field at index 0, 5 records, a lap, a session. */
    private fun sampleFit(): ByteArray {
        val e = BufferEncoder(Fit.ProtocolVersion.V2_0)
        e.write(FileIdMesg().apply { type = File.ACTIVITY; manufacturer = Manufacturer.DEVELOPMENT; timeCreated = DateTime(startFit) })
        val otherId = DeveloperDataIdMesg().apply { developerDataIndex = 0; for (i in 0 until 16) setApplicationId(i, 9.toByte()) }
        val otherField = FieldDescriptionMesg().apply { developerDataIndex = 0; fieldDefinitionNumber = 0; fitBaseTypeId = FitBaseType.UINT8; setFieldName(0, "watch_app_field") }
        e.write(otherId); e.write(otherField)
        for (i in 0 until 5) e.write(RecordMesg().apply {
            timestamp = DateTime(startFit + i); heartRate = (100 + i).toShort(); power = 150 + i
            addDeveloperField(DeveloperField(otherField, otherId).apply { value = 7 })
        })
        e.write(LapMesg().apply { timestamp = DateTime(startFit + 5); startTime = DateTime(startFit) })
        e.write(SessionMesg().apply { timestamp = DateTime(startFit + 5); startTime = DateTime(startFit); sport = Sport.RUNNING })
        return e.close()
    }

    private fun decode(bytes: ByteArray): List<Mesg> = mutableListOf<Mesg>().also { out -> Decode().read(ByteArrayInputStream(bytes)) { out.add(it) } }

    private fun streams(ve: List<Double?>) = listOf(
        Stream(StreamCodes.VE, ve, true),
        Stream(StreamCodes.BR, ve.map { it?.let { v -> v / 2 } }, true),
        Stream(StreamCodes.TV, ve.map { it?.let { 2.0 } }, true),
        Stream(StreamCodes.IE, ve.map { it?.let { 0.8 } }, true),
        Stream(StreamCodes.ZONE, ve.map { it?.let { 1.0 } }, true),
    )

    private fun devValue(m: Mesg, name: String): Double? =
        m.developerFields.firstOrNull { it.name == name }?.doubleValue

    @Test fun `adds the four breathing fields to records by timestamp`() {
        val time = listOf(0.0, 1.0, 2.0, 3.0, 4.0)
        val merged = BreathingFitMerger.merge(sampleFit(), start, time, streams(listOf(40.0, 41.0, null, 43.0, 44.0)))
        assertTrue(Decode().checkFileIntegrity(ByteArrayInputStream(merged)))
        val records = decode(merged).filter { it.num == MesgNum.RECORD }
        assertEquals(5, records.size)
        assertEquals(40.0, devValue(records[0], "tyme_minute_volume")!!, 1e-4)
        assertEquals(20.0, devValue(records[0], "tyme_breath_rate")!!, 1e-4)
        assertEquals(2.0, devValue(records[0], "tyme_tidal_volume")!!, 1e-4)
        assertEquals(0.8, devValue(records[0], "tyme_inhale_exhale_ratio")!!, 1e-4)
        assertNull(devValue(records[2], "tyme_minute_volume"))
        assertEquals(44.0, devValue(records[4], "tyme_minute_volume")!!, 1e-4)
    }

    @Test fun `keeps every original message and field`() {
        val original = decode(sampleFit())
        val merged = decode(BreathingFitMerger.merge(sampleFit(), start, listOf(0.0, 1.0, 2.0, 3.0, 4.0), streams(List(5) { 40.0 })))
        val names = { l: List<Mesg> -> l.groupingBy { it.name }.eachCount() }
        val before = names(original); val after = names(merged)
        assertEquals(before["record"], after["record"])
        assertEquals(before["lap"], after["lap"]); assertEquals(before["session"], after["session"])
        assertEquals(before["developer_data_id"]!! + 1, after["developer_data_id"])
        assertEquals(before["field_description"]!! + 4, after["field_description"])
        val r = RecordMesg(merged.first { it.num == MesgNum.RECORD })
        assertEquals(100.toShort(), r.heartRate); assertEquals(150, r.power)
        assertEquals(7.0, devValue(r, "watch_app_field")!!, 1e-9)
    }

    @Test fun `uses a developer data index not already in the file`() {
        val merged = decode(BreathingFitMerger.merge(sampleFit(), start, listOf(0.0), streams(listOf(40.0))))
        val indexes = merged.filter { it.num == MesgNum.DEVELOPER_DATA_ID }.map { DeveloperDataIdMesg(it).developerDataIndex }
        assertEquals(listOf(0.toShort(), 1.toShort()), indexes)
    }

    @Test fun `aligns by the activity time axis, not record order`() {
        // Intervals' time stream starts 2 s into the file: stream index 0 is record 2.
        val shifted = start.plusSeconds(2)
        val merged = decode(BreathingFitMerger.merge(sampleFit(), shifted, listOf(0.0, 1.0), streams(listOf(50.0, 51.0))))
        val records = merged.filter { it.num == MesgNum.RECORD }
        assertNull(devValue(records[1], "tyme_minute_volume"))
        assertEquals(50.0, devValue(records[2], "tyme_minute_volume")!!, 1e-4)
        assertEquals(51.0, devValue(records[3], "tyme_minute_volume")!!, 1e-4)
    }

    @Test(expected = NotFitException::class) fun `refuses a file that is not FIT`() {
        BreathingFitMerger.merge("<gpx/>".toByteArray(), start, listOf(0.0), streams(listOf(40.0)))
    }
}
