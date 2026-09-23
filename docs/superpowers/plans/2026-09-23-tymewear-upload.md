# Breathing to Tymewear, thresholds from Tymewear: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename Tyme4All's zones and thresholds to match Tymewear exactly. Then, after Tyme4All pushes breathing to an Intervals.icu activity, merge the same breathing into that activity's original FIT file and upload it to Tymewear in place of Tymewear's copy. Also read the athlete's per-sport VE thresholds and resting/max values from Tymewear. Ship as v0.2.0.

**Architecture:** First a rename, so Tyme4All's thresholds and zones carry Tymewear's names. Then there are three new pure-JVM units under `domain/`, each testable without Android:
- a FIT merger built on the Garmin FIT SDK;
- an OkHttp Tymewear client;
- a `TymewearUploader` that finds Tymewear's copy, downloads the original file from Intervals.icu, merges the breathing and uploads it.

`SyncEngine` runs the uploader as a second step after a successful Intervals.icu push, with its own per-session state. `Settings` gains Tymewear thresholds and chooses the thresholds per use. The Android layer adds an encrypted credential store, a Settings card, a Sessions column and notification text.

**Tech Stack:** Kotlin 2.0, Android minSdk 26 / target 34, OkHttp 4.12, kotlinx.serialization, `com.garmin:fit:21.176.0`, `androidx.security:security-crypto:1.0.0`, JUnit 4, MockWebServer.

**Spec:** `docs/superpowers/specs/2026-09-23-tymewear-upload-design.md`

## Global Constraints

- **Allowed Tymewear calls.** These are the only calls to Tymewear:
  - `POST /api/session/signin/`
  - `POST /api/session/refresh/`
  - `GET /v2/api/profile/`
  - `GET /v2/api/users/{profileId}/thresholds/active/?sport_type=running|bike`
  - `GET /v2/api/resting-max-values/`
  - `GET /v2/api/activities-cursor/`
  - `GET /v2/api/activities/{id}/`
  - `PATCH /api/activities/third-party/{tpId}/`

  No delete, tag, pin, run-algo or respond call may exist anywhere in the code.
- **Headers.** Every Tymewear request carries `X-Source: v2`. Authenticated ones also carry `Authorization: Token <token>`. The base URL is `https://api.tymewear.com`.
- **Secrets.** The email, password and token never appear in a log line (Timber included), an exception message, `SessionMeta` or a notification.
- **The Intervals.icu file is never modified.** A Tymewear failure never changes the Intervals.icu outcome of a session.
- **Breathing field names** are exactly `tyme_breath_rate` (0, brpm), `tyme_tidal_volume` (1, vol/br), `tyme_minute_volume` (2, vol/min) and `tyme_inhale_exhale_ratio` (3, sec/sec). Each is float32, under a developer data index not already used in the file.
- **Tymewear's names everywhere.** Thresholds are Endurance, VT1, VT2, Top Z4 and VO2max, in that order. Zones are Z1 to Z5: Z1 is below Endurance, Z2 is Endurance to VT1, Z3 is VT1 to VT2, Z4 is VT2 to Top Z4, and Z5 is Top Z4 and above. VO2max is the top of Z5 and not a zone edge. This applies to code, settings labels, the live JSON, the overlay, the watch page and the docs.
- **Karoo rides** (device name contains "karoo", case-insensitive) are never uploaded.
- **Test data.** Tests use `test@example.com`, `fake-pass` and `fake-token`, and only generated FIT files. No real activity file is committed; the repo is public.
- **Build and test** from `phone/`: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## File map

| File | Role |
|---|---|
| `domain/Zones.kt`, `domain/Protocol.kt`, `domain/Constants.kt`, `domain/Settings.kt`, `domain/LiveState.kt`, `domain/relay/OverlayPage.kt`, `android/PrefsSettingsStore.kt`, `android/ui/SettingsScreen.kt`, `watch/`, `pc/` | Tymewear's names for zones and thresholds (Task 1) |
| `phone/gradle/libs.versions.toml`, `phone/app/build.gradle.kts` | New dependencies; version bump in Task 7 |
| `domain/fit/BreathingFitMerger.kt` (new) | The FIT merge (Task 2) |
| `domain/tymewear/TymewearApi.kt` (new) | Interface, models, `TymewearAuthException`, `TymewearException`, `CredentialStore` (Task 3) |
| `domain/tymewear/TymewearClient.kt` (new) | OkHttp client (Task 3) |
| `domain/sync/IntervalsApi.kt`, `IntervalsClient.kt` | `originalFile(id)` (Task 4) |
| `domain/tymewear/TymewearUploader.kt` (new) | Find, download, merge, upload (Task 4) |
| `domain/Settings.kt`, `domain/tymewear/TymewearProfileSync.kt` (new), `domain/LiveState.kt`, `android/PrefsSettingsStore.kt` | Thresholds from Tymewear (Task 5) |
| `domain/session/SessionStore.kt`, `domain/sync/SyncScheduler.kt`, `domain/sync/SyncEngine.kt` | Second sync step (Task 6) |
| `android/EncryptedCredentialStore.kt` (new), `android/Graph.kt`, `android/RecorderService.kt`, `android/Notifications.kt`, `android/ui/SettingsScreen.kt`, `android/ui/SessionsScreen.kt` | Android wiring (Task 7) |
| `README.md`, `phone/README.md` | Docs and release (Task 8) |

Paths under `domain/` and `android/` are relative to `phone/app/src/main/kotlin/com/tymewear/run/`. Tests mirror them under `phone/app/src/test/kotlin/com/tymewear/run/`.

---

### Task 1: Tymewear's names for zones and thresholds

Tyme4All inherited the Karoo app's names. There, four fields called `vt1`, `vt2`, `topZ4` and `vo2max` are the edges between five zones, and the zones are labelled Endurance, VT1, VT2, Top Z4 and VO2Max. Tymewear's Fitness Profile uses different names for the same edges: Endurance, VT1, VT2 and Top Z4, with VO2max above them. It calls the zones Z1 to Z5. The athlete's bike values prove the old fields map one place down: Tymewear's markers are Endurance 73.2, VT1 96, VT2 112 and Top Z4 129.6, and the Karoo fields hold 73, 96, 112 and 130. The athlete wants Tymewear's names throughout.

**Files:**
- Modify:
  - `domain/Zones.kt`, `domain/Protocol.kt` (`veZone` and its comment block), `domain/Constants.kt`, `domain/Settings.kt`, `domain/LiveState.kt`
  - `domain/relay/OverlayPage.kt` (`ZONE_NAMES`)
  - `android/PrefsSettingsStore.kt`, `android/ui/SettingsScreen.kt`
  - `watch/shared/constants.js`, `watch/tools/mock-relay.js`, `pc/overlay.ps1`
  - `README.md`, `phone/README.md`, `watch/README.md`, `pc/README.md` (threshold names only)
- Test: `domain/ZoneClassifierTest.kt`, `domain/LiveStateTest.kt`, `domain/relay/RelayServerTest.kt`, `domain/SettingsTest.kt`, `watch/test/view-model.test.js`, `watch/test/mock-relay.test.js`

**Interfaces:**
- Produces:
  - `data class ZoneThresholds(val endurance: Double, val vt1: Double, val vt2: Double, val topZ4: Double, val vo2max: Double)`
  - `Protocol.veZone(minuteVolume, endurance, vt1, vt2, topZ4): Int`, returning 0 for no data, then 1 to 5 by the edges above. VO2max is not a parameter.
  - `ZoneClassifier.zoneFor(ve, thresholds)` is unchanged in shape.
  - `fun ZoneThresholds.Companion.fromLegacy(vt1: Double, vt2: Double, topZ4: Double, vo2max: Double, vo2maxTop: Double = Constants.DEFAULT_VO2MAX): ZoneThresholds = ZoneThresholds(endurance = vt1, vt1 = vt2, vt2 = topZ4, topZ4 = vo2max, vo2max = vo2maxTop)`, the migration for v0.1.0 settings.
  - The `/live` JSON `thresholds` object becomes `{endurance, vt1, vt2, topZ4, vo2max}`. `ThresholdsDto` follows suit.
  - Constants: `DEFAULT_ENDURANCE = 73.0`, `DEFAULT_VT1 = 96.0`, `DEFAULT_VT2 = 112.0`, `DEFAULT_TOP_Z4 = 130.0`, `DEFAULT_VO2MAX = 180.0`. The zone edges are unchanged from v0.1.0; only the names move.
  - Zone labels, in the overlay page, the watch and the PC overlay: `['--', 'Z1', 'Z2', 'Z3', 'Z4', 'Z5']`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// ZoneClassifierTest: replace the existing cases with Tymewear's bands
private val t = ZoneThresholds(endurance = 73.0, vt1 = 96.0, vt2 = 112.0, topZ4 = 130.0, vo2max = 180.0)
@Test fun `zones follow Tymewear's edges`() {
    assertEquals(0, ZoneClassifier.zoneFor(0.0, t))
    assertEquals(1, ZoneClassifier.zoneFor(72.9, t)); assertEquals(2, ZoneClassifier.zoneFor(73.0, t))
    assertEquals(3, ZoneClassifier.zoneFor(96.0, t)); assertEquals(4, ZoneClassifier.zoneFor(112.0, t))
    assertEquals(5, ZoneClassifier.zoneFor(130.0, t)); assertEquals(5, ZoneClassifier.zoneFor(250.0, t))
}

// SettingsTest
@Test fun `v0_1_0 thresholds move one name down`() {
    assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 130.0, 180.0), ZoneThresholds.fromLegacy(73.0, 96.0, 112.0, 130.0))
}
@Test fun `defaults keep the v0_1_0 edges under Tymewear's names`() {
    assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 130.0, 180.0), Settings.DEFAULT.thresholds)
}
```

- Update `LiveStateTest` and `RelayServerTest` to expect `"thresholds":{"endurance":73.0,"vt1":96.0,"vt2":112.0,"topZ4":130.0,"vo2max":180.0}`.
- Update the watch tests and the mock relay to the same object.

- [ ] **Step 2: Run the tests and see them fail.** Run `./gradlew :app:testDebugUnitTest`, and in `watch/` run `npm test`.

- [ ] **Step 3: Implement the rename.**
- **Settings screen:** five fields, labelled "Endurance", "VT1", "VT2", "Top Z4" and "VO2max", in that order. Validation requires Endurance < VT1 < VT2 < Top Z4 < VO2max, with the same error display as now.
- **Settings store:** use new keys `z_endurance`, `z_vt1`, `z_vt2`, `z_topz4` and `z_vo2max`.
  - If `z_vt1` is absent but the old `vt1` key exists, read the four old keys through `ZoneThresholds.fromLegacy`.
  - `save` writes the new keys and removes the old `vt1`, `vt2`, `topz4` and `vo2max` keys.
  - The reserve keys are unchanged.
- **`Protocol.veZone`:** rewrite the comment block to name the zones Z1 to Z5 by Tymewear's edges.
- **Leave alone:** stream codes and `TymeVeZone` values (1 to 5), which don't change.

- [ ] **Step 4: Check nothing still uses the old meaning**

Run: `grep -rnE "DEFAULT_VO2MAX|topZ4|\.vo2max|'Endurance', 'VT1'|VO2Max'" phone/app/src watch pc | grep -v /build/ | grep -v node_modules`

Read every hit. `vo2max` must now mean the top of Z5 only. Nothing may use it as a zone edge.

- [ ] **Step 5: Run all tests (phone and watch); they pass.**

- [ ] **Step 6: Commit**

```bash
git add -A phone watch pc README.md
git commit -m "Name zones and thresholds as Tymewear does: Endurance, VT1, VT2, Top Z4, VO2max; zones Z1-Z5"
```

---

### Task 2: The FIT merger

**Files:**
- Modify: `phone/gradle/libs.versions.toml`, `phone/app/build.gradle.kts`
- Create: `phone/app/src/main/kotlin/com/tymewear/run/domain/fit/BreathingFitMerger.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/fit/BreathingFitMergerTest.kt`

**Interfaces:**
- Consumes: `Stream` (`domain/sync/IntervalsApi.kt`), `StreamCodes` (`domain/sync/StreamAligner.kt`)
- Produces:
  - `object BreathingFitMerger { fun merge(original: ByteArray, activityStart: java.time.Instant, time: List<Double?>, streams: List<Stream>): ByteArray }`
  - `class NotFitException(message: String) : Exception(message)`

These SDK calls were checked against 21.176.0 on 2026-09-23:
- `BufferEncoder(Fit.ProtocolVersion.V2_0)`, `.write(Mesg)`, `.close(): ByteArray`
- `Decode().read(InputStream, MesgListener)` and `Decode().checkFileIntegrity(InputStream)`
- `DeveloperDataIdMesg.setDeveloperDataIndex(Short)` and `.setApplicationId(Int, Byte)`
- `FieldDescriptionMesg.setFieldName(0, String)`, `.setUnits(0, String)` and `.setFitBaseTypeId(FitBaseType.FLOAT32)`
- `DeveloperField(fieldDescription, developerDataId).setValue(Float)` and `Mesg.addDeveloperField(DeveloperField)`
- `RecordMesg(mesg).timestamp.timestamp`, which is FIT seconds (Unix seconds = FIT seconds + 631065600)

A prototype ran through a real Zepp FIT and a real TPV FIT. Every message count stayed the same apart from one added `developer_data_id` and the added `field_description` messages, and the integrity check passed.

- [ ] **Step 1: Add the dependency**

In `libs.versions.toml` under `[versions]` add `garminFit = "21.176.0"`. Under `[libraries]` add `garmin-fit = { module = "com.garmin:fit", version.ref = "garminFit" }`. In `app/build.gradle.kts` `dependencies` add `implementation(libs.garmin.fit)`.

- [ ] **Step 2: Write the failing tests**

```kotlin
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
```

- [ ] **Step 3: Run the tests and see them fail**

Run: `cd phone && ./gradlew :app:testDebugUnitTest --tests "com.tymewear.run.domain.fit.BreathingFitMergerTest"`
Expected: compilation fails; `BreathingFitMerger` is unresolved.

- [ ] **Step 4: Implement**

```kotlin
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
```

- [ ] **Step 5: Run the tests and see them pass**

Run the same command as Step 3. Expected: 5 tests pass. If `Decode().read` with a Kotlin lambda doesn't resolve, use `MesgListener { mesgs.add(it) }`.

- [ ] **Step 6: Check a real file locally (not committed)**

If a real FIT exists outside the repo (`~/Downloads/*.fit`, or ask the controller for one from the scratchpad), run this throwaway test:

```kotlin
@Test fun realFile() { val f = java.io.File(System.getenv("REAL_FIT") ?: return); val b = f.readBytes()
  val m = BreathingFitMerger.merge(b, java.time.Instant.EPOCH, emptyList(), emptyList())
  assertTrue(com.garmin.fit.Decode().checkFileIntegrity(java.io.ByteArrayInputStream(m))) }
```

Run it with `REAL_FIT=/path ./gradlew ...`, then delete the test before committing.

- [ ] **Step 7: Commit**

```bash
git add phone/gradle/libs.versions.toml phone/app/build.gradle.kts phone/app/src/main/kotlin/com/tymewear/run/domain/fit phone/app/src/test/kotlin/com/tymewear/run/domain/fit
git commit -m "phone: merge breathing into an activity's FIT file as Tymewear developer fields"
```

---

### Task 3: The Tymewear client

**Files:**
- Create: `domain/tymewear/TymewearApi.kt`, `domain/tymewear/TymewearClient.kt`
- Test: `domain/tymewear/TymewearClientTest.kt`

**Interfaces:**
- Produces, all in `domain/tymewear/TymewearApi.kt`:

```kotlin
package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.ZoneThresholds
import java.time.Instant

data class TymewearProfile(val id: Long)
data class PartnerCopy(val tpId: Long, val partner: String, val startDate: Instant)
data class TymewearActivity(val id: String, val listedAt: Instant?, val partnerCopies: List<PartnerCopy>)

class TymewearException(val httpCode: Int, message: String) : Exception(message)
/** Tymewear refused the saved sign-in and the stored email and password. */
class TymewearAuthException(message: String) : Exception(message)

/** Where the app keeps the Tymewear sign-in. Android: EncryptedSharedPreferences. Tests: in memory. */
interface CredentialStore {
    val email: String?
    val password: String?
    var token: String?
    fun save(email: String, password: String)
    fun clear()
}

class InMemoryCredentialStore(override var email: String? = null, override var password: String? = null, override var token: String? = null) : CredentialStore {
    override fun save(email: String, password: String) { this.email = email; this.password = password }
    override fun clear() { email = null; password = null; token = null }
}

interface TymewearApi {
    /** Signs in with the given email and password, stores them and the token. Throws TymewearAuthException if refused. */
    fun signIn(email: String, password: String)
    fun profile(): TymewearProfile
    /** The Fitness Profile's markers for "running" or "bike", same names as Tymewear, or null if it has none (404, or any marker missing). */
    fun activeThresholds(userId: Long, sport: String): ZoneThresholds?
    /** Resting and max BR and HR, or null if Tymewear has none. */
    fun restingMax(): ReserveSettings?
    fun recentActivities(userId: Long, limit: Int): List<TymewearActivity>
    fun activity(id: String): TymewearActivity
    fun replaceFile(tpId: Long, fit: ByteArray)
}
```

- `class TymewearClient(private val credentials: CredentialStore, baseUrl: String = "https://api.tymewear.com", private val client: OkHttpClient = OkHttpClient()) : TymewearApi`

These reply shapes were checked against the athlete's account on 2026-09-23:
- **Profile:** `/v2/api/profile/` returns either an object or a one-element array. It has `id` (number). It also has `bike_ve_target_*` / `running_ve_target_*`, but those are NOT the zones: for this athlete they read 68.6/83/111.2/180.5 while the Fitness Profile shows 73/96/112/130/182. Do not use them.
- **Active thresholds:** `/v2/api/users/{id}/thresholds/active/?sport_type=running` (or `bike`) returns `{ "sport_type": "running", "thresholds": { "endurance": {"metrics": {"ve": {"value": 74.9}, "hr": {...}, ...}}, "vt1": {...}, "vt2": {...}, "topz4": {...}, "vo2max": {...} } }`. Read `thresholds.<key>.metrics.ve.value` for keys `endurance`, `vt1`, `vt2`, `topz4`, `vo2max`. The athlete's values on 2026-09-23: running 74.9/94.5/123.1/149.8/230.1, bike 73.2/96.0/112.0/129.6/182.3. `sport_type=cycling` returns 400; only `running` and `bike` are valid.
- **Resting and max values:** `/v2/api/resting-max-values/` returns an object keyed `br_rest`, `hr_rest`, `br_max`, `hr_max` (among others). Each is `{ "value": <number>, ... }` or absent/null.
- **Activity list:** `/v2/api/activities-cursor/?user=<id>&limit=<n>` returns `{ "results": [ { "id": "<uuid>", "time_stamp": "2026 Sep 20 11:18:21", "tz_offset": "1.0", ... } ] }`. `time_stamp` is local time at `tz_offset` hours. The list carries no `third_party_activities`.
- **One activity:** `/v2/api/activities/{id}/` has `third_party_activities: [ { "id": 962296, "partner": "INTERVALS_ICU", "start_date": "2026-09-20T09:23:15Z", ... } ]`. It also carries a large `x` array; parse only the fields needed.

- [ ] **Step 1: Write the failing tests**

```kotlin
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
}
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.tymewear.run.domain.tymewear.TymewearClientTest"`. Expected: compilation fails.

- [ ] **Step 3: Implement `TymewearApi.kt`** with exactly the code in the Interfaces block.

- [ ] **Step 4: Implement `TymewearClient.kt`**

Keep to these rules:
- Use `Json { ignoreUnknownKeys = true }`. Parse to `JsonElement` and read fields by hand, as `IntervalsClient` does.
- `signIn`:
  - `POST /api/session/signin/` with a JSON `{username, password}` body.
  - On 400, 401 or 403, throw `TymewearAuthException("Tymewear did not accept the email and password")`.
  - On any other non-2xx, throw `TymewearException(code, "Tymewear sign-in returned HTTP $code")`.
  - On success, call `credentials.save(email, password)` and set `credentials.token`.
- `authed(request)` sends with the current token:
  - On a 401, try `POST /api/session/refresh/` with the old token. A 200 with `token` stores the new token and retries once.
  - Otherwise, if `credentials.email` and `credentials.password` are set, call the sign-in logic once without saving credentials again, then retry once.
  - If it is still 401, or there is nothing to sign in with, throw `TymewearAuthException("Tymewear stopped accepting the saved sign-in")`.
- Error messages include only the method, the path and the code. Never include the body; it may echo secrets.
- `profile()`: if the body is an array, take its first element. `id` is a number.
- `activeThresholds(userId, sport)`: GET with `sport_type=$sport`. 404 returns null. Any of the five markers missing, or not increasing, returns null.
- `restingMax()`: read `br_rest`, `br_max`, `hr_rest` and `hr_max`, each `.value`. If any is missing or null, return null.
- `recentActivities`:
  - Parse `time_stamp` with `DateTimeFormatter.ofPattern("yyyy MMM dd HH:mm:ss", Locale.ENGLISH)` as a local time.
  - Apply `tz_offset` (hours, which may be fractional, e.g. "5.5") to get an `Instant`. If parsing fails, `listedAt = null`.
- `activity(id)`: read `third_party_activities[]` into `id`, `partner` and `start_date` (ISO instant). Skip entries missing any of them.
- `replaceFile`:
  - Send a `MultipartBody` with form data part `fit_file`, filename `tyme4all.fit`, media type `application/octet-stream`, as `PATCH /api/activities/third-party/$tpId/`.
  - A 4xx throws `TymewearException(code, "Tymewear refused the file (HTTP $code)")`.

- [ ] **Step 5: Run the tests and see them pass.** Expected: 13 pass.

- [ ] **Step 6: Commit**

```bash
git add phone/app/src/main/kotlin/com/tymewear/run/domain/tymewear phone/app/src/test/kotlin/com/tymewear/run/domain/tymewear
git commit -m "phone: a Tymewear client limited to sign-in, profile, activities and file replacement"
```

---

### Task 4: Original file download and the uploader

**Files:**
- Modify: `domain/sync/IntervalsApi.kt`, `domain/sync/IntervalsClient.kt`, and `domain/sync/SyncEngineTest.kt`'s `FakeApi`, which must implement the new method
- Create: `domain/tymewear/TymewearUploader.kt`
- Test: `domain/sync/IntervalsClientTest.kt` (one test), `domain/tymewear/TymewearUploaderTest.kt`

**Interfaces:**
- Consumes:
  - `BreathingFitMerger.merge` and `NotFitException` (Task 2)
  - `TymewearApi`, `TymewearProfile`, `TymewearActivity`, `PartnerCopy`, `TymewearAuthException` and `TymewearException` (Task 3)
  - `ActivitySummary`, `Stream`
- Produces:
  - In `IntervalsApi`: `fun originalFile(activityId: String): ByteArray`
  - `sealed class TymewearOutcome { data object Uploaded; data object NotYet; data class Skipped(val reason: String); data class Failed(val reason: String, val auth: Boolean = false) }`
  - `class TymewearUploader(private val intervals: IntervalsApi, private val tymewear: TymewearApi) { fun upload(activity: ActivitySummary, time: List<Double?>, streams: List<Stream>): TymewearOutcome }`

- [ ] **Step 1: Add `originalFile` with a failing test**

In `IntervalsClientTest`, add:

```kotlin
@Test fun `downloads the original file, gunzipped`() {
    val gz = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write(byteArrayOf(14, 16, 1, 2)) } }.toByteArray()
    server.enqueue(MockResponse().setBody(okio.Buffer().write(gz)).setHeader("Content-Encoding", "gzip"))
    assertArrayEquals(byteArrayOf(14, 16, 1, 2), api.originalFile("i100"))
    assertEquals("/api/v1/activity/i100/file", server.takeRequest().path)
}
```

Then implement `originalFile` in `IntervalsClient`:
- Send a GET with the same auth header and read `body.bytes()`. OkHttp un-gzips transparently when it added `Accept-Encoding` itself.
- If the bytes still start with the gzip magic `0x1f 0x8b`, wrap them in `GZIPInputStream`. Intervals.icu sometimes serves a `.fit.gz` as a plain body.
- A non-2xx throws `IntervalsException`, as `execute` does.

Add `override fun originalFile(activityId: String) = ByteArray(0)` to `SyncEngineTest.FakeApi`, backed by a settable `var original = ByteArray(0)`.

- [ ] **Step 2: Write the uploader's failing tests**

```kotlin
package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.sync.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TymewearUploaderTest {
    private val start = Instant.parse("2026-09-20T09:23:15Z")
    private val run = ActivitySummary("i1", start, "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 600)
    private val time = listOf(0.0, 1.0)
    private val streams = listOf(Stream(StreamCodes.VE, listOf(40.0, 41.0), true))

    private class FakeIntervals(var file: ByteArray) : IntervalsApi {
        override fun listActivities(oldest: Instant, newest: Instant) = emptyList<ActivitySummary>()
        override fun getStreams(activityId: String, types: List<String>) = emptyList<Stream>()
        override fun putStreams(activityId: String, streams: List<Stream>) = UpdateStreamsResult(emptyList(), emptyList())
        override fun verifyKey() = true
        override fun originalFile(activityId: String) = file
    }

    private class FakeTymewear : TymewearApi {
        var listed = listOf<TymewearActivity>()
        var details = mapOf<String, TymewearActivity>()
        var uploaded: Pair<Long, ByteArray>? = null
        var fail: Exception? = null
        override fun signIn(email: String, password: String) {}
        override fun profile() = fail?.let { throw it } ?: TymewearProfile(7)
        override fun activeThresholds(userId: Long, sport: String): com.tymewear.run.domain.ZoneThresholds? = null
        override fun restingMax(): ReserveSettings? = null
        override fun recentActivities(userId: Long, limit: Int) = listed
        override fun activity(id: String) = details.getValue(id)
        override fun replaceFile(tpId: Long, fit: ByteArray) { fail?.let { throw it }; uploaded = tpId to fit }
    }

    /** A real (tiny) FIT: records at the activity's first two seconds. */
    private fun fit(): ByteArray {
        val fitStart = start.epochSecond - 631_065_600L
        val e = com.garmin.fit.BufferEncoder(com.garmin.fit.Fit.ProtocolVersion.V2_0)
        e.write(com.garmin.fit.FileIdMesg().apply { type = com.garmin.fit.File.ACTIVITY })
        for (i in 0..1) e.write(com.garmin.fit.RecordMesg().apply { timestamp = com.garmin.fit.DateTime(fitStart + i) })
        return e.close()
    }

    private fun copyOf(activityStart: Instant, tpId: Long = 972545) =
        TymewearActivity("a1", activityStart.plusSeconds(700), listOf(PartnerCopy(tpId, "INTERVALS_ICU", activityStart)))

    @Test fun `finds Tymewear's copy by start time and uploads the merged file`() {
        val tw = FakeTymewear().apply { listed = listOf(copyOf(start).copy(partnerCopies = emptyList())); details = mapOf("a1" to copyOf(start)) }
        val out = TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams)
        assertEquals(TymewearOutcome.Uploaded, out)
        assertEquals(972545L, tw.uploaded!!.first)
        assertTrue(tw.uploaded!!.second.size > fit().size)
    }

    @Test fun `not yet when Tymewear has no copy`() {
        val tw = FakeTymewear()
        assertEquals(TymewearOutcome.NotYet, TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams))
        assertNull(tw.uploaded)
    }

    @Test fun `ignores a copy from another partner or another start`() {
        val other = TymewearActivity("a2", start.plusSeconds(700), listOf(PartnerCopy(1, "GARMIN", start), PartnerCopy(2, "INTERVALS_ICU", start.plusSeconds(3600))))
        val tw = FakeTymewear().apply { listed = listOf(other); details = mapOf("a2" to other) }
        assertEquals(TymewearOutcome.NotYet, TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams))
    }

    @Test fun `skips Karoo rides and non FIT originals`() {
        val karoo = run.copy(deviceName = "HAMMERHEAD Karoo")
        assertTrue(TymewearUploader(FakeIntervals(fit()), FakeTymewear()).upload(karoo, time, streams) is TymewearOutcome.Skipped)
        val tw = FakeTymewear().apply { listed = listOf(copyOf(start)); details = mapOf("a1" to copyOf(start)) }
        assertTrue(TymewearUploader(FakeIntervals("<gpx/>".toByteArray()), tw).upload(run, time, streams) is TymewearOutcome.Skipped)
        assertNull(tw.uploaded)
    }

    @Test fun `auth failure is a failed outcome marked auth`() {
        val tw = FakeTymewear().apply { fail = TymewearAuthException("Tymewear stopped accepting the saved sign-in") }
        assertEquals(TymewearOutcome.Failed("Tymewear stopped accepting the saved sign-in", auth = true),
            TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams))
    }

    @Test fun `a refused upload is failed with the reason`() {
        val tw = FakeTymewear().apply { listed = listOf(copyOf(start)); details = mapOf("a1" to copyOf(start)) }
        tw.fail = null
        val refusing = object : TymewearApi by tw { override fun replaceFile(tpId: Long, fit: ByteArray) { throw TymewearException(400, "Tymewear refused the file (HTTP 400)") } }
        assertEquals(TymewearOutcome.Failed("Tymewear refused the file (HTTP 400)"), TymewearUploader(FakeIntervals(fit()), refusing).upload(run, time, streams))
    }

    @Test fun `network errors mean try again`() {
        val tw = FakeTymewear().apply { fail = java.io.IOException("timeout") }
        assertEquals(TymewearOutcome.NotYet, TymewearUploader(FakeIntervals(fit()), tw).upload(run, time, streams))
    }
}
```

- [ ] **Step 3: Run the tests and see them fail.** Compilation fails.

- [ ] **Step 4: Implement `TymewearUploader`**

```kotlin
package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.fit.BreathingFitMerger
import com.tymewear.run.domain.fit.NotFitException
import com.tymewear.run.domain.sync.ActivitySummary
import com.tymewear.run.domain.sync.IntervalsApi
import com.tymewear.run.domain.sync.IntervalsException
import com.tymewear.run.domain.sync.Stream
import java.io.IOException
import java.time.Duration
import kotlin.math.abs

sealed class TymewearOutcome {
    data object Uploaded : TymewearOutcome()
    data object NotYet : TymewearOutcome()
    data class Skipped(val reason: String) : TymewearOutcome()
    data class Failed(val reason: String, val auth: Boolean = false) : TymewearOutcome()
}

/** Gets one activity's breathing into Tymewear by replacing the file behind Tymewear's copy of it. */
class TymewearUploader(private val intervals: IntervalsApi, private val tymewear: TymewearApi) {

    fun upload(activity: ActivitySummary, time: List<Double?>, streams: List<Stream>): TymewearOutcome {
        if (activity.deviceName?.contains("karoo", ignoreCase = true) == true) return TymewearOutcome.Skipped("Karoo rides carry breathing already")
        return try {
            val tpId = findCopy(activity) ?: return TymewearOutcome.NotYet
            val merged = BreathingFitMerger.merge(intervals.originalFile(activity.id), activity.startDate, time, streams)
            tymewear.replaceFile(tpId, merged)
            TymewearOutcome.Uploaded
        } catch (e: NotFitException) {
            TymewearOutcome.Skipped("the activity's original file is not a FIT file")
        } catch (e: TymewearAuthException) {
            TymewearOutcome.Failed(e.message ?: "Tymewear refused the sign-in", auth = true)
        } catch (e: TymewearException) {
            if (e.httpCode in 400..499) TymewearOutcome.Failed(e.message ?: "Tymewear refused the file") else TymewearOutcome.NotYet
        } catch (e: IntervalsException) {
            TymewearOutcome.Failed("could not download the original file: ${e.message}")
        } catch (e: IOException) {
            TymewearOutcome.NotYet
        }
    }

    /** Tymewear's id for its Intervals.icu copy of this activity, or null if it has none yet. */
    private fun findCopy(activity: ActivitySummary): Long? {
        val profile = tymewear.profile()
        val listed = tymewear.recentActivities(profile.id, LIST_LIMIT)
        // Tymewear lists an activity when it imports it, some time after the activity started.
        val earliest = activity.startDate.minus(Duration.ofHours(1))
        val candidates = listed.filter { it.listedAt == null || !it.listedAt.isBefore(earliest) }.take(DETAIL_LIMIT)
        for (c in candidates) {
            val copies = c.partnerCopies.ifEmpty { tymewear.activity(c.id).partnerCopies }
            copies.firstOrNull { it.partner == "INTERVALS_ICU" && abs(Duration.between(it.startDate, activity.startDate).seconds) <= 60 }
                ?.let { return it.tpId }
        }
        return null
    }

    private companion object {
        const val LIST_LIMIT = 30
        /** Details are large (per-second arrays), so only this many are opened per attempt. */
        const val DETAIL_LIMIT = 8
    }
}
```

- [ ] **Step 5: Run all unit tests.** `./gradlew :app:testDebugUnitTest`. Expected: everything passes, including the existing `SyncEngineTest` with the extended `FakeApi`.

- [ ] **Step 6: Commit**

```bash
git add -A phone/app/src
git commit -m "phone: find Tymewear's copy of an activity and replace its file with one carrying the breathing"
```

---

### Task 5: Thresholds and reserve from Tymewear

**Files:**
- Modify: `domain/Settings.kt`, `domain/LiveState.kt:61-67`, `android/PrefsSettingsStore.kt`
- Create: `domain/tymewear/TymewearProfileSync.kt`
- Test: `domain/SettingsTest.kt` (added tests), `domain/tymewear/TymewearProfileSyncTest.kt`

**Interfaces:**
- Consumes: `TymewearApi`, `TymewearProfile` (Task 3); `ZoneThresholds` with Tymewear's names (Task 1)
- Produces, new fields on `Settings`, all defaulted so existing callers compile:

```kotlin
val tymewearUpload: Boolean = true,
val useTymewearThresholds: Boolean = true,
val tymewearSignedIn: Boolean = false,
val bikeThresholds: ZoneThresholds? = null,
val runThresholds: ZoneThresholds? = null,
val tymewearReserve: ReserveSettings? = null,
```

- New members on `Settings`:
  - `fun liveThresholds(): ZoneThresholds`
  - `fun thresholdsFor(activityType: String?): ZoneThresholds`
  - `fun effectiveReserve(): ReserveSettings`
- `object TymewearProfileSync { fun apply(settings: Settings, api: TymewearApi): Settings }`

Rules, from spec 4.4:
- **When Tymewear's values apply.** They are used only while `tymewearSignedIn && useTymewearThresholds` is true and the value is non-null. Otherwise the manual `thresholds` and `reserve` apply.
- **`thresholdsFor`.** Run thresholds apply for `Run`, `TrailRun`, `VirtualRun`, `Walk` and `Hike` (case-insensitive). Every other type, including null, uses bike thresholds.
- **`liveThresholds`** uses bike.
- **No mapping.** After Task 1, Tyme4All's names are Tymewear's, so the markers are stored as they come.
- **`apply`.** Reads `profile()`, `activeThresholds(id, "bike")`, `activeThresholds(id, "running")` and `restingMax()` and returns `settings` with `bikeThresholds`, `runThresholds` and `tymewearReserve` replaced by any non-null result. A value that comes back null keeps the stored one. Exceptions propagate to the caller.

- [ ] **Step 1: Write the failing tests**

```kotlin
// SettingsTest additions
private val bike = ZoneThresholds(73.2, 96.0, 112.0, 129.6, 182.3)
private val run = ZoneThresholds(74.9, 94.5, 123.1, 149.8, 230.1)
private val tw = Settings.DEFAULT.copy(tymewearSignedIn = true, bikeThresholds = bike, runThresholds = run,
    tymewearReserve = ReserveSettings(13.4, 67.4, 50.0, 188.0))

@Test fun `thresholds follow the activity's sport when Tymewear is in use`() {
    assertEquals(run, tw.thresholdsFor("Run")); assertEquals(run, tw.thresholdsFor("trailrun"))
    assertEquals(run, tw.thresholdsFor("Hike")); assertEquals(bike, tw.thresholdsFor("VirtualRide"))
    assertEquals(bike, tw.thresholdsFor(null)); assertEquals(bike, tw.liveThresholds())
    assertEquals(ReserveSettings(13.4, 67.4, 50.0, 188.0), tw.effectiveReserve())
}

@Test fun `manual values apply when switched off or signed out`() {
    val off = tw.copy(useTymewearThresholds = false)
    assertEquals(Settings.DEFAULT.thresholds, off.thresholdsFor("Run")); assertEquals(Settings.DEFAULT.reserve, off.effectiveReserve())
    val out = tw.copy(tymewearSignedIn = false)
    assertEquals(Settings.DEFAULT.thresholds, out.liveThresholds())
}

@Test fun `a missing sport falls back to manual`() {
    assertEquals(Settings.DEFAULT.thresholds, tw.copy(runThresholds = null).thresholdsFor("Run"))
}
```

```kotlin
// TymewearProfileSyncTest
package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.ZoneThresholds
import org.junit.Assert.*
import org.junit.Test

class TymewearProfileSyncTest {
    @Test fun `zones match Tymewear's own bands`() {
        val t = ZoneThresholds(74.9, 94.5, 123.1, 149.8, 230.1)
        assertEquals(1, com.tymewear.run.domain.ZoneClassifier.zoneFor(60.0, t))    // Z1, below Endurance
        assertEquals(2, com.tymewear.run.domain.ZoneClassifier.zoneFor(80.0, t))    // Z2, Endurance to VT1
        assertEquals(3, com.tymewear.run.domain.ZoneClassifier.zoneFor(100.0, t))   // Z3, VT1 to VT2
        assertEquals(4, com.tymewear.run.domain.ZoneClassifier.zoneFor(130.0, t))   // Z4, VT2 to Top Z4
        assertEquals(5, com.tymewear.run.domain.ZoneClassifier.zoneFor(160.0, t))   // Z5, Top Z4 and above
    }

    @Test fun `apply keeps stored values when Tymewear returns nothing new`() {
        val stored = ZoneThresholds(1.0, 2.0, 3.0, 4.0, 5.0)
        val api = object : TymewearApi {
            override fun signIn(email: String, password: String) {}
            override fun profile() = TymewearProfile(1)
            override fun activeThresholds(userId: Long, sport: String) =
                if (sport == "bike") ZoneThresholds(73.2, 96.0, 112.0, 129.6, 182.3) else null
            override fun restingMax(): ReserveSettings? = null
            override fun recentActivities(userId: Long, limit: Int) = emptyList<TymewearActivity>()
            override fun activity(id: String) = throw UnsupportedOperationException()
            override fun replaceFile(tpId: Long, fit: ByteArray) = throw UnsupportedOperationException()
        }
        val out = TymewearProfileSync.apply(Settings.DEFAULT.copy(runThresholds = stored), api)
        assertEquals(ZoneThresholds(73.2, 96.0, 112.0, 129.6, 182.3), out.bikeThresholds)
        assertEquals(stored, out.runThresholds)
        assertNull(out.tymewearReserve)
    }
}
```

- [ ] **Step 2: Run the tests and see them fail.**

- [ ] **Step 3: Implement.** Add the fields and three functions to `Settings`, and write `TymewearProfileSync` as specified. In `LiveState.payload`:
- change `ZoneClassifier.zoneFor(ve, settings.thresholds)` to `settings.liveThresholds()`;
- change the `thresholds = settings.thresholds...` DTO to use `settings.liveThresholds()`;
- change the `reserve = settings.reserve...` DTO to use `settings.effectiveReserve()`.

In `PrefsSettingsStore`, persist the new fields:
- Booleans: `tw_upload`, `tw_use_thresholds`, `tw_signed_in`.
- Thresholds, per sport, as five floats under `tw_bike_endurance|vt1|vt2|topz4|vo2max` and `tw_run_*`, plus a boolean `tw_bike_set` / `tw_run_set` that says whether the sport is present.
- Reserve: `tw_res_*` plus `tw_res_set`.

- [ ] **Step 4: Run all unit tests; they pass.** Existing `LiveStateTest` expectations hold, because the defaults leave Tymewear off.

- [ ] **Step 5: Commit**

```bash
git add -A phone/app/src
git commit -m "phone: per-sport thresholds and reserve from Tymewear, with manual values as the fallback"
```

---

### Task 6: The second sync step

**Files:**
- Modify: `domain/session/SessionStore.kt` (`SessionMeta`), `domain/sync/SyncScheduler.kt`, `domain/sync/SyncEngine.kt`
- Test: `domain/sync/SyncEngineTest.kt`, `domain/sync/SyncSchedulerTest.kt`

**Interfaces:**
- Consumes: `TymewearUploader`, `TymewearOutcome` (Task 4); `Settings.thresholdsFor` and `effectiveReserve` (Task 5)
- Produces:
  - New `SessionMeta` fields, defaulted: `tymewearState: String? = null` (null means not applicable; otherwise `pending|synced|failed|skipped`), `tymewearMessage: String? = null`, `tymewearAttemptMs: Long? = null`.
  - `SyncEngine(api: IntervalsApi, store: SessionStore, uploader: TymewearUploader? = null)`.
  - `SyncOutcome.Synced` gains `val tymewear: TymewearOutcome? = null`.
  - `SyncEngine.syncTymewear(sessionId: String, settings: Settings, nowMs: Long): TymewearOutcome?` retries only the Tymewear step.
  - In `SyncScheduler`: `dueTymewear(metas, nowMs)` and `expiredTymewear(metas, nowMs)`. They work like `due` and `expired`, but on `syncState == "synced" && tymewearState == "pending"`, timed by `tymewearAttemptMs` and measured from `endMs`.

Behaviour:
- **Thresholds.** `run` builds the series with `settings.thresholdsFor(activity.type)` and aligns with `settings.effectiveReserve()`.
- **After a successful push**, when `uploader != null && settings.tymewearUpload && settings.tymewearSignedIn`:
  - Call `uploader.upload(activity, time, aligned)`.
  - Record the state: `Uploaded` → `synced`, `NotYet` → `pending`, `Skipped` → `skipped`, `Failed` → `failed`, with the message.
  - Return `Synced(..., tymewear = outcome)`.
  - If Tymewear is off or not signed in, `tymewearState` stays null.
- **`syncTymewear`** needs a session already `synced` with an `activityId`.
  - It re-lists activities around the session to get the `ActivitySummary`, fetches `time` and `heartrate`, and rebuilds and aligns the series exactly as `run` does.
  - It then runs only the uploader and records the outcome. It never calls `putStreams`.
- **An exception inside the Tymewear step** is caught and recorded as `failed`. It never turns the Intervals.icu outcome into a failure.

- [ ] **Step 1: Write the failing tests** in `SyncEngineTest`

Add a helper `fakeUploader(outcome)`: a `TymewearUploader` subclass is not possible because the class is final, so give `TymewearUploader` an interface. Add `fun interface TymewearStep { fun upload(activity: ActivitySummary, time: List<Double?>, streams: List<Stream>): TymewearOutcome }`, make `TymewearUploader` implement it, and have `SyncEngine` take a `TymewearStep?` parameter named `uploader`. Then:

```kotlin
private val twSettings = settings.copy(tymewearSignedIn = true)
private val zeppRun = ActivitySummary("i1", Instant.ofEpochMilli(startMs + 3_000), "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 600)
private val twoRows = listOf(Stream("time", listOf(0.0, 1.0, 2.0, 3.0)), Stream("heartrate", listOf(120.0, 121.0, 122.0, 123.0)))

@Test fun `uploads to Tymewear after a successful push`() {
    val store = SessionStore(tmp.root); val id = session(store)
    var seen: List<Stream>? = null
    val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
    val out = SyncEngine(api, store) { _, _, s -> seen = s; TymewearOutcome.Uploaded }.sync(id, twSettings, startMs + 700_000)
    assertEquals(TymewearOutcome.Uploaded, (out as SyncOutcome.Synced).tymewear)
    assertEquals(api.lastPut, seen)
    assertEquals("synced", store.meta(id)!!.tymewearState)
}

@Test fun `a Tymewear failure leaves the intervals result synced`() {
    val store = SessionStore(tmp.root); val id = session(store)
    val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
    val out = SyncEngine(api, store) { _, _, _ -> throw RuntimeException("boom") }.sync(id, twSettings, startMs + 700_000)
    assertTrue(out is SyncOutcome.Synced)
    assertEquals("synced", store.meta(id)!!.syncState)
    assertEquals("failed", store.meta(id)!!.tymewearState)
}

@Test fun `no Tymewear state when not signed in`() {
    val store = SessionStore(tmp.root); val id = session(store)
    val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
    SyncEngine(api, store) { _, _, _ -> fail("must not upload"); TymewearOutcome.Uploaded }.sync(id, settings, startMs + 700_000)
    assertNull(store.meta(id)!!.tymewearState)
}

@Test fun `retrying Tymewear never pushes to intervals again`() {
    val store = SessionStore(tmp.root); val id = session(store)
    val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
    SyncEngine(api, store) { _, _, _ -> TymewearOutcome.NotYet }.sync(id, twSettings, startMs + 700_000)
    assertEquals("pending", store.meta(id)!!.tymewearState)
    api.lastPut = null
    val again = SyncEngine(api, store) { _, _, _ -> TymewearOutcome.Uploaded }.syncTymewear(id, twSettings, startMs + 900_000)
    assertEquals(TymewearOutcome.Uploaded, again)
    assertNull(api.lastPut)
    assertEquals("synced", store.meta(id)!!.tymewearState)
}

@Test fun `zones use the activity's sport thresholds`() {
    val store = SessionStore(tmp.root); val id = session(store)
    val api = FakeApi().apply { activities = listOf(zeppRun); streams = twoRows }
    // VE 40 is Z1 under the default thresholds and Z4 (VT2 30 to Top Z4 45) under these run thresholds.
    val s = twSettings.copy(runThresholds = ZoneThresholds(10.0, 20.0, 30.0, 45.0, 60.0))
    SyncEngine(api, store).sync(id, s, startMs + 700_000)
    assertEquals(4.0, api.lastPut!!.first { it.type == StreamCodes.ZONE }.data[2])
}
```

The expected Z4 follows from Task 1's `veZone`: VT2 ≤ 40 < Top Z4.

In `SyncSchedulerTest`, add tests that `dueTymewear` returns a synced session whose `tymewearState` is `pending`:
- once 2 minutes have passed since `tymewearAttemptMs`;
- not within 2 minutes;
- not after 6 hours from `endMs`, when it belongs to `expiredTymewear` instead.

- [ ] **Step 2: Run the tests and see them fail.**

- [ ] **Step 3: Implement** the fields, the scheduler functions and the engine changes.
- Extract the "fetch time/hr, build series, align" code in `run` into a private function returning `Triple(time, aligned, activity)`. Both `run` and `syncTymewear` use it.
- Wrap the Tymewear step in `try { ... } catch (e: Exception) { record failed with e.message }`.

- [ ] **Step 4: Run all unit tests; they pass.**

- [ ] **Step 5: Commit**

```bash
git add -A phone/app/src
git commit -m "phone: send each synced session's breathing to Tymewear as its own step, retried on its own"
```

---

### Task 7: Android wiring and screens

**Files:**
- Create: `android/EncryptedCredentialStore.kt`
- Modify:
  - `phone/gradle/libs.versions.toml` and `phone/app/build.gradle.kts`: add `androidx.security:security-crypto:1.0.0` as `libs.androidx.security.crypto`
  - `android/Graph.kt`, `android/RecorderService.kt`, `android/Notifications.kt`, `android/ui/SettingsScreen.kt`, `android/ui/SessionsScreen.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces:
  - `class EncryptedCredentialStore(context: Context) : CredentialStore`, backed by `EncryptedSharedPreferences.create("tymewear_credentials", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context, AES256_SIV, AES256_GCM)`, with keys `email`, `password` and `token`.
  - `Graph.tymewearCredentials: CredentialStore`, set in `Graph.init`.

- [ ] **Step 1: Credential store and Graph.** Implement `EncryptedCredentialStore`: `clear()` removes all three keys, and `save` writes email and password. Initialise it in `Graph.init`.

- [ ] **Step 2: The sync pass.** In `RecorderService.runSyncPass`, after the Intervals.icu key check:
- Build `val tw = if (settings.tymewearSignedIn) TymewearClient(Graph.tymewearCredentials) else null`.
- If `tw != null`, refresh the settings once per pass, catching all errors:

  ```kotlin
  try { Graph.settings.save(TymewearProfileSync.apply(Graph.settings.load(), tw)) } catch (e: Exception) { Timber.w("Tymewear profile refresh failed: ${e.javaClass.simpleName}") }
  ```

  Then reload the settings.
- Create the engine with `SyncEngine(IntervalsClient(key), Graph.sessionStore, tw?.let { TymewearUploader(IntervalsClient(key), it) })`.
- After the existing due loop, add a second loop:
  - For `SyncScheduler.expiredTymewear(metas, now)`, set `tymewearState = "failed"`, message "Tymewear never showed the activity".
  - For `SyncScheduler.dueTymewear(metas, now)`, call `engine.syncTymewear(m.id, settings, now)`.
- On a `Failed(auth = true)` outcome from either path, call `Notifications.tymewearSignIn(this)`.
- **Service lifetime:** `SyncScheduler.anyPending` must also count Tymewear-pending sessions inside the window, so the service stays alive while an upload is waiting. Add that to `anyPending` in `SyncScheduler`, with a test in `SyncSchedulerTest`.

- [ ] **Step 3: Notifications.**
- `synced(ctx, sessionId, activityId, activityLabel, tymewear: TymewearOutcome?)`:
  - title "Breathing added to Intervals.icu and Tymewear" when `tymewear == Uploaded`;
  - otherwise the existing title;
  - when `tymewear` is `Skipped` or `Failed`, add a second line "Tymewear: <reason>".
- `tymewearUploaded(ctx, sessionId, label)` for retries that later succeed.
- `tymewearSignIn(ctx)`: "Sign in to Tymewear again", opening the app.
- None of them include the email, token or password.

- [ ] **Step 4: Settings card.** In `SettingsScreen`, a new `Card` titled "Tymewear", above the Intervals.icu card.
- **Not signed in:**
  - the line "Send your breathing to Tymewear and use your Tymewear thresholds. Your password is kept encrypted on this phone.";
  - an email field and a password field (`PasswordVisualTransformation`, `KeyboardType.Password`);
  - a **Sign in** button.

  Signing in, on `Dispatchers.IO`:
  - calls `TymewearClient(Graph.tymewearCredentials).signIn(email, password)`;
  - then saves `TymewearProfileSync.apply(settings.copy(tymewearSignedIn = true), client)`;
  - shows a snackbar "Signed in to Tymewear" or "Tymewear did not accept that email and password";
  - clears the password field either way.
- **Signed in:**
  - "Signed in to Tymewear";
  - the thresholds line, e.g. `Bike: Endurance 73.2 · VT1 96 · VT2 112 · Top Z4 129.6 · VO2max 182.3` and the same for Run, from `bikeThresholds` and `runThresholds`, or "No thresholds in Tymewear yet";
  - switches **Also send breathing to Tymewear** (`tymewearUpload`) and **Use thresholds from Tymewear** (`useTymewearThresholds`);
  - **Sign out**, which calls `Graph.tymewearCredentials.clear()` and saves `tymewearSignedIn = false`.
- While `tymewearSignedIn && useTymewearThresholds`, the existing threshold and reserve fields are disabled (`enabled = false`), with the caption "From Tymewear. Switch off Use thresholds from Tymewear to edit."

- [ ] **Step 5: Sessions tab.** `SessionRow`:
- Shows `Tymewear: <tymewearState>` under `Sync: ...` when `tymewearState != null`, and `tymewearMessage` when present.
- **Retry sync:** when the session is `synced` and its `tymewearState` is `pending` or `failed`, call `engine.syncTymewear`; otherwise the existing `sync`.
- Build the engine there as in Step 2, with the uploader when signed in.

- [ ] **Step 6: Build and run all tests**

Run: `cd phone && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 7: Check for secrets and forbidden calls**

Run:
```bash
grep -rnE "Timber\.[a-z]\(.*(password|token|email)" phone/app/src/main
grep -rnE "tag-|run-algo|respond/|/pin/|\.delete\(" phone/app/src/main/kotlin/com/tymewear/run/domain/tymewear
```
Expected: no output from either.

- [ ] **Step 8: Commit**

```bash
git add -A phone
git commit -m "phone: Tymewear sign-in, upload after each sync, thresholds in Settings and state on the Sessions tab"
```

---

### Task 8: Docs, version and release build

**Files:**
- Modify: `phone/app/build.gradle.kts` (`versionCode = 2`, `versionName = "0.2.0"`), `README.md`, `phone/README.md`

- [ ] **Step 1: README.md**
- Update "How it works" step 4: once the breathing is on Intervals.icu, and if you signed in to Tymewear, the phone also sends it to Tymewear.
- Replace the sentence "whether it also picks up streams added to an activity afterwards has not been confirmed yet" with the fact: it doesn't, which is why Tyme4All uploads to Tymewear itself.
- Add a section **Tymewear** covering:
  - what is sent (the activity's original file from Intervals.icu with the breathing added; Tymewear's copy is replaced, and Intervals.icu is untouched);
  - that zones come from your Tymewear Fitness Profile (Endurance, VT1, VT2, Top Z4), per sport;
  - that the password is kept encrypted on the phone and sent only to Tymewear;
  - that Tymewear's API is undocumented and may change, in which case the upload stops and the rest of the app keeps working.
- Update the "Nothing leaves your phone except the push to Intervals.icu" bullet so it also names Tymewear.

- [ ] **Step 2: phone/README.md.** Add a setup step "Sign in to Tymewear (optional)" after the Intervals.icu key step, and a short troubleshooting note covering the Sessions tab's Tymewear line and Retry.

- [ ] **Step 3: Version bump and build**

Run: `cd phone && ./gradlew :app:testDebugUnitTest assembleRelease`
Expected: `BUILD SUCCESSFUL`. `app/build/outputs/apk/release/tyme4all.apk` exists and is signed with the release key: check that `apksigner verify --print-certs` shows the same certificate SHA-256 as v0.1.0's APK. Download v0.1.0's APK from the release to compare. If the `TYME4ALL_*` properties are missing, stop and report; do not publish a debug-signed release.

- [ ] **Step 4: Commit**

```bash
git add README.md phone/README.md phone/app/build.gradle.kts
git commit -m "Release 0.2.0: breathing to Tymewear and thresholds from Tymewear"
```

- [ ] **Step 5: Stop before publishing.** Report the APK path, its SHA-256 and draft release notes to the controller. Publishing the GitHub release (`gh release create v0.2.0 phone/app/build/outputs/apk/release/tyme4all.apk --title "Tyme4All 0.2.0" --notes-file ...`) and pushing `main` happen only after the athlete says so.
