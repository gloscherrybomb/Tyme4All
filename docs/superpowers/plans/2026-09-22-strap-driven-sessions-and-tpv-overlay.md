# Strap-Driven Sessions and TPV Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the K-Breathe Run phone app record a breathing session whenever the strap streams (no watch or PC trigger), merge it into whichever Intervals.icu activity overlaps it (Amazfit run, TrainingPeaks Virtual ride, anything else), and show the live values as an always-on-top overlay on a Windows PC running TrainingPeaks Virtual.

**Architecture:** All changes are to the existing Android app under `phone/` plus one new PowerShell script under `pc/`. The session controller opens on the first breath packet and closes on an idle timeout. The matcher ranks Intervals.icu activities by time overlap. The sync engine folds every session that targets the same activity into one push so a mid-ride dropout does not blank earlier data. The loopback relay gains a second, token-protected listener on the Wi-Fi address that serves live values and a self-contained HTML overlay page. The Windows overlay is a WPF window built from PowerShell that polls that listener once a second.

**Tech Stack:** Kotlin 2.0.0, Android Gradle Plugin 8.2.2, minSdk 26, targetSdk 34, Jetpack Compose Material 3, kotlinx-serialization-json 1.7.1, NanoHTTPD 2.3.1, OkHttp 4.12.0 with MockWebServer, ZXing core 3.5.3 (new, QR code), JUnit 4.13.2. Windows: PowerShell 5.1, WPF via `PresentationFramework`.

**Spec:** `docs/superpowers/specs/2026-09-22-strap-driven-sessions-and-tpv-overlay-design.md`

## Global Constraints

- Package `com.tymewear.run`; pure logic in `domain/`, Android shells in `android/`; every pure unit gets a JVM JUnit 4 test under `phone/app/src/test`. No Robolectric.
- Idle stop timeout default **3 minutes** (`idleStopMinutes`); session cap **8 hours**; sessions under **60 s** are `skipped`.
- Minimum overlap for an automatic match **5 minutes** (`Constants.MIN_OVERLAP_MS`). Strava-sourced activities and activities whose `device_name` contains "Karoo" are never matched (the Karoo records the Tyme* fields itself). No Amazfit preference.
- Relay port **41415** on both loopback (no auth) and the Wi-Fi IPv4 address (token required). The LAN listener never serves `POST /session/*`.
- Token: 16 random bytes, base64url without padding (22 characters).
- Stream codes unchanged: `TymeVentilation`, `TymeBreathRate`, `TymeTidalVolume`, `TymeIERatio`, `TymeVeZone`, `TymeBreathReserve`, `TymeMobilizationIndex`.
- Zone colours (index 0..5): `#424242`, `#4db6ac`, `#0277bd`, `#f57f17`, `#ef6c00`, `#c62828`. Zone names: `--`, `Endurance`, `VT1`, `VT2`, `Top Z4`, `VO2Max`. Status colours: ok `#66bb6a`, warn `#ffb300`, grey `#9e9e9e`.
- Unmatched notification only for sessions of **15 minutes** or longer. Recording notification body refreshed at most every **30 s**.
- Run tests from `phone/`: `./gradlew :app:testDebugUnitTest` (all) or with `--tests 'com.tymewear.run.domain.session.SessionControllerTest'` for one class. Build: `./gradlew assembleDebug`.
- Commit after every task with the message given, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## File Structure

```
phone/app/src/main/kotlin/com/tymewear/run/
  domain/
    Constants.kt                      IDLE_STOP_MS, MIN_OVERLAP_MS replace the fallback and window constants   (modify)
    Settings.kt                       idleStopMinutes, lanOverlayEnabled, lanToken                            (modify)
    NotificationPolicy.kt             pure decisions and texts for the recording/unmatched notifications      (create)
    session/SessionController.kt      strap-driven open, idle close                                           (modify)
    sync/IntervalsApi.kt              ActivitySummary.elapsedTimeS                                            (modify)
    sync/IntervalsClient.kt           parse elapsed_time                                                      (modify)
    sync/ActivityMatcher.kt           overlap ranking                                                         (modify)
    sync/StreamAligner.kt             align a list of series                                                  (modify)
    sync/SyncEngine.kt                overlap choice, runner-up message, multi-session merge, activity label  (modify)
    relay/RelayServer.kt              host parameter, token mode, /overlay                                    (modify)
    relay/OverlayPage.kt              the HTML overlay page as a string                                       (create)
    relay/LanToken.kt                 token generator                                                         (create)
  android/
    PrefsSettingsStore.kt             new keys                                                                (modify)
    Graph.kt                          idleStopMs, lanOverlayUrl flow                                          (modify)
    Notifications.kt                  recording notification, reworded texts                                  (modify)
    RecorderService.kt                recording notification loop, LAN relay lifecycle, unmatched policy      (modify)
    LanRelayManager.kt                Wi-Fi address tracking and LAN listener lifecycle                       (create)
    ui/SettingsScreen.kt              idle stop field, LAN overlay switch, regenerate token                   (modify)
    ui/StatusScreen.kt                overlay URL, copy, QR                                                   (modify)
    ui/QrImage.kt                     QR bitmap composable                                                    (create)
phone/app/src/test/kotlin/com/tymewear/run/
  domain/SettingsTest.kt, NotificationPolicyTest.kt (create)
  domain/session/SessionControllerTest.kt
  domain/sync/ActivityMatcherTest.kt, IntervalsClientTest.kt, StreamAlignerTest.kt, SyncEngineTest.kt
  domain/relay/RelayServerTest.kt, LanTokenTest.kt (create)
phone/gradle/libs.versions.toml       zxing-core                                                              (modify)
phone/app/build.gradle.kts            implementation(libs.zxing.core)                                        (modify)
pc/overlay.ps1, pc/overlay.cmd, pc/README.md                                                                  (create)
phone/README.md, README.md                                                                                    (modify)
```

---

### Task 1: Strap-driven session controller with an idle stop setting

**Files:**
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/Constants.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/Settings.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/session/SessionController.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/PrefsSettingsStore.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/Graph.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/RecorderService.kt` (settings collector, line 74)
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/ui/SettingsScreen.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/session/SessionControllerTest.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/SettingsTest.kt`

**Interfaces:**
- Consumes: `SessionStore.create(startMs, source)`, `SessionStore.idFor(startMs)`, `SessionStore.meta(id)`, `SessionStore.finish(id, endMs, reason)`, `SessionEvent.*`.
- Produces: `SessionController(store, idleStopMs: Long = Constants.IDLE_STOP_MS, maxSessionMs: Long = Constants.MAX_SESSION_MS)` with `var idleStopMs: Long`; `onBreath` opens a session with source `"strap"` when none is open; `tick` returns `"idle"` or `"max-length"`. `Settings.idleStopMinutes: Int` (default 3) replaces `fallbackStopMinutes`. `Constants.IDLE_STOP_MS`.

- [ ] **Step 1: Rewrite the controller tests**

Replace the whole of `phone/app/src/test/kotlin/com/tymewear/run/domain/session/SessionControllerTest.kt` with:

```kotlin
package com.tymewear.run.domain.session

import com.tymewear.run.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun ctl() = SessionController(SessionStore(tmp.root), idleStopMs = 180_000, maxSessionMs = 3_600_000)
    private val d = Protocol.BreathingData(20.0, 1.5, 30.0, 1.0, 100, 100, 150, 0, 1)

    @Test
    fun `start is idempotent and stop closes`() {
        val c = ctl()
        val id = c.start(1_000, "watch")
        assertEquals(id, c.start(2_000, "watch"))
        assertEquals(id, c.activeSessionId)
        assertEquals(id, c.stop(5_000, "watch"))
        assertNull(c.activeSessionId)
        assertNull(c.stop(6_000, "watch"))
        val store = SessionStore(tmp.root)
        assertEquals(5_000L, store.meta(id)!!.endMs)
        assertEquals("watch", (store.events(id).last() as SessionEvent.Stop).reason)
    }

    @Test
    fun `first breath opens a strap session and logs the breath`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        val id = c.activeSessionId!!
        assertEquals(SessionStore.idFor(1_000), id)
        val events = SessionStore(tmp.root).events(id)
        assertEquals("strap", (events[0] as SessionEvent.Start).source)
        assertEquals(listOf("start", "breath"), events.map { it::class.simpleName!!.lowercase() })
    }

    @Test
    fun `breath inside a watch session does not open another`() {
        val c = ctl()
        val id = c.start(1_000, "watch")
        c.onBreath(d, 1_500)
        c.onBattery(50, 1_600)
        assertEquals(id, c.activeSessionId)
        val events = SessionStore(tmp.root).events(id)
        assertEquals(listOf("start", "breath", "battery"), events.map { it::class.simpleName!!.lowercase() })
    }

    @Test
    fun `breath after a manual stop reopens a new session`() {
        val c = ctl()
        val first = c.start(1_000, "manual")
        c.stop(5_000, "manual")
        c.onBreath(d, 9_000)
        val second = c.activeSessionId!!
        assertNotEquals(first, second)
        assertEquals("strap", (SessionStore(tmp.root).events(second)[0] as SessionEvent.Start).source)
    }

    @Test
    fun `reopening within the same second gets a distinct id`() {
        val c = ctl()
        val first = c.start(1_000, "manual")
        c.stop(1_200, "manual")
        c.onBreath(d, 1_800)
        val second = c.activeSessionId!!
        assertNotEquals(first, second)
        val store = SessionStore(tmp.root)
        assertEquals(1_200L, store.meta(first)!!.endMs)
        assertNull(store.meta(second)!!.endMs)
    }

    @Test
    fun `idle timeout closes the session after the last breath`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        c.onBreath(d, 10_000)
        assertNull(c.tick(10_000 + 180_000))
        assertEquals("idle", c.tick(10_000 + 180_001))
        assertNull(c.activeSessionId)
        val id = SessionStore.idFor(1_000)
        assertEquals("idle", (SessionStore(tmp.root).events(id).last() as SessionEvent.Stop).reason)
    }

    @Test
    fun `idle timeout counts from start when no breath has arrived`() {
        val c = ctl()
        c.start(1_000, "watch")
        assertNull(c.tick(1_000 + 180_000))
        assertEquals("idle", c.tick(1_000 + 180_001))
    }

    @Test
    fun `strap events are logged but do not close the session`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        c.onStrap(false, 2_000)
        c.onStrap(true, 3_000)
        assertNull(c.tick(100_000))
        val events = SessionStore(tmp.root).events(c.activeSessionId!!)
        assertEquals(listOf("start", "breath", "strap", "strap"), events.map { it::class.simpleName!!.lowercase() })
    }

    @Test
    fun `strap events outside a session are not logged anywhere`() {
        val c = ctl()
        c.onStrap(true, 1_000)
        c.onStrap(false, 2_000)
        assertNull(c.activeSessionId)
        assertEquals(0, SessionStore(tmp.root).list().size)
    }

    @Test
    fun `max session length closes even while breathing`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        c.onBreath(d, 1_000 + 3_600_000)
        assertEquals("max-length", c.tick(1_000 + 3_600_001))
    }

    @Test
    fun `startup recovery closes an unfinished session at its last event`() {
        val store = SessionStore(tmp.root)
        val log = store.create(1_000, "watch")
        log.append(SessionEvent.Breath(5_000, d.breathRate, d.tidalVolume, d.ieRatio, d.tvRaw, d.inhaleDurationCs, d.exhaleDurationCs, d.timestamp40ms))
        log.close()
        val c = SessionController(store)
        c.recoverOnStartup(nowMs = 9_000)
        assertEquals(5_000L, store.meta(SessionStore.idFor(1_000))!!.endMs)
        assertEquals("restart", (store.events(SessionStore.idFor(1_000)).last() as SessionEvent.Stop).reason)
    }

    @Test
    fun `recoverOnStartup does not close the in-memory active session`() {
        val c = ctl()
        val activeId = c.start(1_000, "watch")
        val store = SessionStore(tmp.root)
        store.create(500_000, "watch").close()
        val otherId = SessionStore.idFor(500_000)

        c.recoverOnStartup(9_000_000)

        assertEquals(activeId, c.activeSessionId)
        assertNull(store.meta(activeId)!!.endMs)
        assertEquals(500_000L, store.meta(otherId)!!.endMs)
        assertEquals("restart", (store.events(otherId).last() as SessionEvent.Stop).reason)
    }

    @Test
    fun `idleStopMs is live and applies to the next tick`() {
        val c = ctl()
        c.onBreath(d, 1_000)
        c.idleStopMs = 60_000
        assertEquals("idle", c.tick(61_001))
    }

    @Test
    fun `listener is told about changes including strap-opened sessions`() {
        val c = ctl()
        val seen = mutableListOf<String?>()
        c.listener = { seen.add(it) }
        c.onBreath(d, 1_000)
        val id = c.activeSessionId
        c.stop(2_000, "manual")
        assertEquals(listOf(id, null), seen)
    }
}
```

- [ ] **Step 2: Update the settings default test**

In `phone/app/src/test/kotlin/com/tymewear/run/domain/SettingsTest.kt` replace `assertEquals(10, d.fallbackStopMinutes)` with `assertEquals(3, d.idleStopMinutes)`.

- [ ] **Step 3: Run the two test classes to verify they fail to compile**

Run from `phone/`:
```bash
./gradlew :app:testDebugUnitTest --tests 'com.tymewear.run.domain.session.SessionControllerTest' --tests 'com.tymewear.run.domain.SettingsTest'
```
Expected: compilation FAILS with unresolved `idleStopMs` and `idleStopMinutes`.

- [ ] **Step 4: Constants and Settings**

In `Constants.kt` replace `const val FALLBACK_STOP_DISCONNECTED_MS = 10 * 60 * 1000L` with:
```kotlin
    const val IDLE_STOP_MS = 3 * 60 * 1000L
```
Leave `MATCH_WINDOW_MS` for now (Task 2 replaces it).

In `Settings.kt` rename the field and default:
```kotlin
    val idleStopMinutes: Int,
```
and in `DEFAULT`:
```kotlin
            idleStopMinutes = 3,
```

- [ ] **Step 5: Rewrite SessionController**

Replace the whole of `phone/app/src/main/kotlin/com/tymewear/run/domain/session/SessionController.kt` with:

```kotlin
package com.tymewear.run.domain.session

import com.tymewear.run.domain.Constants
import com.tymewear.run.domain.Protocol

/**
 * Opens and closes recording sessions. The strap is the normal trigger: the first breath
 * packet opens a session and an idle timeout with no packets closes it. Watch and manual
 * start/stop still work but are no longer required.
 */
class SessionController(
    private val store: SessionStore,
    idleStopMs: Long = Constants.IDLE_STOP_MS,
    private val maxSessionMs: Long = Constants.MAX_SESSION_MS,
) {
    @Volatile var idleStopMs: Long = idleStopMs
    private val lock = Any()
    private var log: SessionLog? = null
    private var id: String? = null
    private var startMs: Long = 0
    private var lastBreathMs: Long? = null

    var listener: ((String?) -> Unit)? = null

    val activeSessionId: String? get() = synchronized(lock) { id }

    fun start(nowMs: Long, source: String): String {
        var opened: String? = null
        val result = synchronized(lock) { id ?: openLocked(nowMs, source).also { opened = it } }
        opened?.let { notify(it) }
        return result
    }

    fun stop(nowMs: Long, reason: String): String? {
        val result = synchronized(lock) { stopLocked(nowMs, reason) }
        if (result != null) notify(null)
        return result
    }

    fun onBreath(d: Protocol.BreathingData, nowMs: Long) {
        var opened: String? = null
        synchronized(lock) {
            if (id == null) opened = openLocked(nowMs, "strap")
            lastBreathMs = nowMs
            log?.append(SessionEvent.Breath(nowMs, d.breathRate, d.tidalVolume, d.ieRatio, d.tvRaw, d.inhaleDurationCs, d.exhaleDurationCs, d.timestamp40ms))
        }
        opened?.let { notify(it) }
    }

    fun onBattery(pct: Int, nowMs: Long) = synchronized(lock) { log?.append(SessionEvent.Battery(nowMs, pct)) }

    fun onStrap(connected: Boolean, nowMs: Long): Unit = synchronized(lock) {
        log?.append(SessionEvent.Strap(nowMs, connected))
    }

    /** Apply the idle and maximum-length rules. Returns the stop reason when it stopped the session. */
    fun tick(nowMs: Long): String? {
        val reason = synchronized(lock) {
            if (id == null) return@synchronized null
            val since = lastBreathMs ?: startMs
            val r = when {
                nowMs - since > idleStopMs -> "idle"
                nowMs - startMs > maxSessionMs -> "max-length"
                else -> null
            }
            if (r != null) stopLocked(nowMs, r)
            r
        }
        if (reason != null) notify(null)
        return reason
    }

    fun recoverOnStartup(nowMs: Long) = synchronized(lock) {
        val activeId = id
        for (m in store.list()) {
            if (m.endMs == null && m.id != activeId) {
                val lastEventMs = store.events(m.id).lastOrNull()?.tMs ?: m.startMs
                val endMs = lastEventMs.coerceAtMost(nowMs)
                store.finish(m.id, endMs, "restart")
            }
        }
    }

    /** Must be called while holding [lock]. Session ids have one-second resolution, so a
     *  session reopened in the same second as one that just closed is pushed forward until
     *  its id is free rather than overwriting the closed session's files. */
    private fun openLocked(nowMs: Long, source: String): String {
        var t = nowMs
        while (store.meta(SessionStore.idFor(t)) != null) t += 1_000
        val newLog = store.create(t, source)
        val newId = SessionStore.idFor(t)
        log = newLog
        id = newId
        startMs = t
        lastBreathMs = null
        return newId
    }

    /** Must be called while holding [lock]. */
    private fun stopLocked(nowMs: Long, reason: String): String? {
        val current = id ?: return null
        log?.close()
        log = null
        id = null
        lastBreathMs = null
        store.finish(current, nowMs, reason)
        return current
    }

    private fun notify(v: String?) { listener?.invoke(v) }
}
```

- [ ] **Step 6: Update the Android callers**

`PrefsSettingsStore.kt`: replace `.putInt("fallback_stop_min", settings.fallbackStopMinutes)` with `.putInt("idle_stop_min", settings.idleStopMinutes)` and `fallbackStopMinutes = prefs.getInt("fallback_stop_min", d.fallbackStopMinutes),` with `idleStopMinutes = prefs.getInt("idle_stop_min", d.idleStopMinutes),`.

`Graph.kt`: replace the `sessions = ...` line with:
```kotlin
        sessions = SessionController(sessionStore, idleStopMs = settings.load().idleStopMinutes * 60_000L)
```

`RecorderService.kt` line 74: replace `Graph.sessions.fallbackDisconnectedMs = s.fallbackStopMinutes * 60_000L` with:
```kotlin
                    Graph.sessions.idleStopMs = s.idleStopMinutes * 60_000L
```

`SettingsScreen.kt`: rename every `fallbackStopMinutes` to `idleStopMinutes` and `fallbackI` to `idleI`, and change the field label line to:
```kotlin
        IntField("Stop session after no breathing data for (minutes)", idleStopMinutes) { idleStopMinutes = it }
```
and the `Settings(...)` construction argument to `idleStopMinutes = idleI,`.

- [ ] **Step 7: Run the whole test suite**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: all PASS. (`RelayServerTest` still constructs `SessionController(SessionStore(tmp.root))` with defaults, which compiles.)

- [ ] **Step 8: Commit**

```bash
git add -A phone
git commit -m "phone: open sessions on the first breath and close on idle

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Overlap matching with the activity's elapsed time

**Files:**
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/Constants.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/sync/IntervalsApi.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/sync/IntervalsClient.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/sync/ActivityMatcher.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/sync/SyncEngine.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/sync/ActivityMatcherTest.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/sync/IntervalsClientTest.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/sync/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `ActivitySummary`, `SessionMeta.startMs/endMs`.
- Produces: `ActivitySummary.elapsedTimeS: Int? = null` (7th, defaulted); `ActivityMatcher.Ranked(activity, overlapMs)`, `ActivityMatcher.rank(candidates, sessionStartMs, sessionEndMs, minOverlapMs = Constants.MIN_OVERLAP_MS): List<Ranked>`, `ActivityMatcher.pick(...)`: `ActivitySummary?`; `Constants.MIN_OVERLAP_MS`; `SyncOutcome.Synced(activityId, activityLabel, updated)`; sync message `pushed N streams to <id>` optionally followed by `; also overlapped <id> (<device or source>)`.

- [ ] **Step 1: Rewrite the matcher tests**

Replace `phone/app/src/test/kotlin/com/tymewear/run/domain/sync/ActivityMatcherTest.kt` with:

```kotlin
package com.tymewear.run.domain.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityMatcherTest {
    private val t0 = Instant.parse("2026-09-22T18:00:00Z")
    private val sessionStart = t0.toEpochMilli()
    private val sessionEnd = t0.plusSeconds(3_600).toEpochMilli()   // one hour session

    private fun a(id: String, startOffsetSec: Long, elapsedSec: Int?, source: String? = "TPV", device: String? = null) =
        ActivitySummary(id, t0.plusSeconds(startOffsetSec), null, "Ride", source, device, elapsedSec)

    @Test fun `activity fully inside the session matches with its own length`() {
        val r = ActivityMatcher.rank(listOf(a("in", 600, 1_800)), sessionStart, sessionEnd)
        assertEquals("in", r.single().activity.id)
        assertEquals(1_800_000L, r.single().overlapMs)
    }

    @Test fun `session fully inside the activity matches with the session length`() {
        val r = ActivityMatcher.rank(listOf(a("big", -600, 7_200)), sessionStart, sessionEnd)
        assertEquals(3_600_000L, r.single().overlapMs)
    }

    @Test fun `partial overlaps on either side are measured`() {
        val before = ActivityMatcher.rank(listOf(a("b", -1_800, 3_600)), sessionStart, sessionEnd).single()
        val after = ActivityMatcher.rank(listOf(a("a", 1_800, 3_600)), sessionStart, sessionEnd).single()
        assertEquals(1_800_000L, before.overlapMs)
        assertEquals(1_800_000L, after.overlapMs)
    }

    @Test fun `below the minimum overlap is dropped and at it is kept`() {
        assertNull(ActivityMatcher.pick(listOf(a("short", 3_600 - 299, 3_600)), sessionStart, sessionEnd))
        assertEquals("edge", ActivityMatcher.pick(listOf(a("edge", 3_600 - 300, 3_600)), sessionStart, sessionEnd)!!.id)
    }

    @Test fun `no elapsed time never matches automatically`() {
        assertNull(ActivityMatcher.pick(listOf(a("nolen", 0, null)), sessionStart, sessionEnd))
    }

    @Test fun `largest overlap wins regardless of device`() {
        val r = ActivityMatcher.rank(
            listOf(a("amaz", 0, 900, source = "ZEPP", device = "Amazfit Cheetah 2 Ultra"), a("tpv", 0, 3_600)),
            sessionStart, sessionEnd,
        )
        assertEquals(listOf("tpv", "amaz"), r.map { it.activity.id })
        assertEquals("tpv", ActivityMatcher.pick(r.map { it.activity }, sessionStart, sessionEnd)!!.id)
    }

    @Test fun `equal overlap resolves to the earliest start`() {
        val r = ActivityMatcher.rank(listOf(a("later", 60, 1_800), a("earlier", 0, 1_800)), sessionStart, sessionEnd)
        assertEquals(listOf("earlier", "later"), r.map { it.activity.id })
    }

    @Test fun `strava sourced activities are never picked`() {
        assertNull(ActivityMatcher.pick(listOf(a("s", 0, 3_600, source = "STRAVA")), sessionStart, sessionEnd))
        assertNull(ActivityMatcher.pick(listOf(a("s", 0, 3_600, source = "strava")), sessionStart, sessionEnd))
    }

    @Test fun `karoo recordings are never picked because the karoo records breathing itself`() {
        assertNull(ActivityMatcher.pick(listOf(a("k", 0, 3_600, source = "UPLOAD", device = "Hammerhead Karoo 3")), sessionStart, sessionEnd))
    }

    @Test fun `activity entirely outside the session has zero overlap`() {
        assertNull(ActivityMatcher.pick(listOf(a("gone", 7_200, 3_600)), sessionStart, sessionEnd))
    }
}
```

- [ ] **Step 2: Add the elapsed_time assertion to the client test**

In `IntervalsClientTest.kt`, test `lists activities with basic auth and utc window`, change the first JSON object to include `"elapsed_time":3725` (after `"device_name":"Amazfit Cheetah 2 Ultra"`), and add after `assertNull(list[1].name)`:
```kotlin
        assertEquals(3725, list[0].elapsedTimeS)
        assertNull(list[1].elapsedTimeS)
```

- [ ] **Step 3: Update SyncEngineTest fixtures and add the runner-up test**

In `SyncEngineTest.kt`, every `ActivitySummary(...)` construction currently has six arguments. Append `, 600` (elapsed seconds) as a seventh argument to each, so for example line 62 becomes:
```kotlin
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs + 3_000), "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 600))
```
The test session is 600 s long (`session(store)` default), so an activity starting at +3 s with 600 s elapsed overlaps 597 s, above the 300 s minimum.

Add this test to the class:
```kotlin
    @Test fun `runner up activity is named in the sync message`() {
        val store = SessionStore(tmp.root); val id = session(store)
        val api = FakeApi().apply {
            activities = listOf(
                ActivitySummary("tpv", Instant.ofEpochMilli(startMs), "Ride", "Ride", "TPV", null, 600),
                ActivitySummary("amaz", Instant.ofEpochMilli(startMs + 60_000), "Run", "Run", "ZEPP", "Amazfit Cheetah 2 Ultra", 540),
            )
            streams = listOf(Stream("time", listOf(0.0, 1.0)))
        }
        val out = SyncEngine(api, store).sync(id, settings, startMs + 700_000)
        assertTrue(out is SyncOutcome.Synced)
        assertEquals("tpv", api.lastPutId)
        assertEquals("pushed 7 streams to tpv; also overlapped amaz (Amazfit Cheetah 2 Ultra)", store.meta(id)!!.syncMessage)
        assertEquals("Ride", (out as SyncOutcome.Synced).activityLabel)
    }
```

- [ ] **Step 4: Run the sync tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tymewear.run.domain.sync.*'
```
Expected: compilation FAILS (`rank`, `elapsedTimeS`, `activityLabel` unresolved).

- [ ] **Step 5: Constants, ActivitySummary, client**

`Constants.kt`: replace `const val MATCH_WINDOW_MS = 5 * 60 * 1000L` with:
```kotlin
    const val MIN_OVERLAP_MS = 5 * 60 * 1000L
```

`IntervalsApi.kt` line 5:
```kotlin
data class ActivitySummary(val id: String, val startDate: Instant, val name: String?, val type: String?, val source: String?, val deviceName: String?, val elapsedTimeS: Int? = null)
```

`IntervalsClient.kt`: add `import kotlinx.serialization.json.intOrNull` and in `listActivities` add after `deviceName = o.str("device_name"),`:
```kotlin
                elapsedTimeS = (o["elapsed_time"] as? JsonPrimitive)?.intOrNull,
```

- [ ] **Step 6: Rewrite ActivityMatcher**

```kotlin
package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Constants
import kotlin.math.max
import kotlin.math.min

/** Ranks Intervals.icu activities by how much of their time span overlaps the session. */
object ActivityMatcher {
    data class Ranked(val activity: ActivitySummary, val overlapMs: Long)

    fun rank(
        candidates: List<ActivitySummary>,
        sessionStartMs: Long,
        sessionEndMs: Long,
        minOverlapMs: Long = Constants.MIN_OVERLAP_MS,
    ): List<Ranked> =
        candidates
            .filter { it.source?.equals("STRAVA", ignoreCase = true) != true }
            // The Karoo records the same Tyme* fields itself; pushing phone data over them would
            // replace a complete recording with a partial one.
            .filter { it.deviceName?.contains("karoo", ignoreCase = true) != true }
            .map { Ranked(it, overlapMs(it, sessionStartMs, sessionEndMs)) }
            .filter { it.overlapMs >= minOverlapMs }
            .sortedWith(compareByDescending<Ranked> { it.overlapMs }.thenBy { it.activity.startDate })

    fun pick(candidates: List<ActivitySummary>, sessionStartMs: Long, sessionEndMs: Long, minOverlapMs: Long = Constants.MIN_OVERLAP_MS): ActivitySummary? =
        rank(candidates, sessionStartMs, sessionEndMs, minOverlapMs).firstOrNull()?.activity

    fun overlapMs(a: ActivitySummary, sessionStartMs: Long, sessionEndMs: Long): Long {
        val aStart = a.startDate.toEpochMilli()
        val aEnd = aStart + (a.elapsedTimeS ?: 0) * 1_000L
        return max(0L, min(aEnd, sessionEndMs) - max(aStart, sessionStartMs))
    }
}
```

- [ ] **Step 7: Replace SyncEngine.kt in full**

Replace the whole of `phone/app/src/main/kotlin/com/tymewear/run/domain/sync/SyncEngine.kt` with:

```kotlin
package com.tymewear.run.domain.sync

import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.session.SeriesBuilder
import com.tymewear.run.domain.session.SessionMeta
import com.tymewear.run.domain.session.SessionStore
import java.time.Instant

sealed class SyncOutcome {
    data class Synced(val activityId: String, val activityLabel: String, val updated: List<String>) : SyncOutcome()
    data object NotYet : SyncOutcome()
    data class Skipped(val reason: String) : SyncOutcome()
    data class Failed(val message: String) : SyncOutcome()
}

/** The activity to push to, and the next-best overlapping activity if there was one. */
private data class Choice(val activity: ActivitySummary, val runnerUp: ActivitySummary?)

class SyncEngine(private val api: IntervalsApi, private val store: SessionStore) {

    fun sync(sessionId: String, settings: Settings, nowMs: Long): SyncOutcome = run(sessionId, settings, nowMs) { meta ->
        val start = Instant.ofEpochMilli(meta.startMs)
        val end = Instant.ofEpochMilli(meta.endMs!!)
        // Intervals.icu interprets oldest/newest in the athlete's local zone, not UTC, so a
        // narrow UTC-based window can clip an activity near a zone boundary. Widen it to ±24 h;
        // the real selection happens in ActivityMatcher's overlap ranking.
        val candidates = api.listActivities(start.minusSeconds(86_400), end.plusSeconds(86_400))
        val ranked = ActivityMatcher.rank(candidates, meta.startMs, meta.endMs)
        ranked.firstOrNull()?.let { Choice(it.activity, ranked.getOrNull(1)?.activity) }
    }

    fun syncTo(sessionId: String, activityId: String, settings: Settings, nowMs: Long): SyncOutcome = run(sessionId, settings, nowMs) { meta ->
        val start = Instant.ofEpochMilli(meta.startMs)
        val found = api.listActivities(start.minusSeconds(86_400), start.plusSeconds(86_400)).firstOrNull { it.id == activityId }
            ?: throw IntervalsException(404, "activity $activityId not found near the session")
        Choice(found, null)
    }

    private fun run(sessionId: String, settings: Settings, nowMs: Long, choose: (SessionMeta) -> Choice?): SyncOutcome {
        val meta = store.meta(sessionId) ?: return SyncOutcome.Failed("unknown session")
        fun record(state: String, msg: String, activityId: String? = meta.activityId) =
            store.updateMeta(sessionId) { it.copy(syncState = state, syncMessage = msg, lastSyncAttemptMs = nowMs, activityId = activityId) }

        if (settings.intervalsApiKey.isNullOrBlank()) { record("skipped", "no api key"); return SyncOutcome.Skipped("no api key") }
        val end = meta.endMs ?: run { record("pending", "session still open"); return SyncOutcome.NotYet }
        if (end - meta.startMs < 60_000) { record("skipped", "session shorter than 60 s"); return SyncOutcome.Skipped("session shorter than 60 s") }

        return try {
            val choice = choose(meta) ?: run { record("pending", "no overlapping activity yet"); return SyncOutcome.NotYet }
            val activity = choice.activity
            val streams = api.getStreams(activity.id, listOf("time", "heartrate"))
            val time = streams.firstOrNull { it.type == "time" }?.data
            if (time.isNullOrEmpty()) { record("failed", "activity has no time stream", activity.id); return SyncOutcome.Failed("activity has no time stream") }
            val hr = streams.firstOrNull { it.type == "heartrate" }?.data
            val series = SeriesBuilder.build(store.events(sessionId), settings.thresholds)
            val aligned = StreamAligner.align(time, hr, activity.startDate, series, settings.reserve)
            val result = api.putStreams(activity.id, aligned)
            val missing = StreamCodes.ALL - result.updated.toSet()
            if (missing.isNotEmpty()) {
                val msg = "streams not accepted: ${missing.joinToString(",")}"
                record("failed", msg, activity.id); SyncOutcome.Failed(msg)
            } else {
                val msg = buildString {
                    append("pushed ${result.updated.size} streams to ${activity.id}")
                    choice.runnerUp?.let { append("; also overlapped ${it.id} (${it.deviceName ?: it.source ?: "unknown device"})") }
                }
                record("synced", msg, activity.id)
                SyncOutcome.Synced(activity.id, activity.name ?: activity.id, result.updated)
            }
        } catch (e: IntervalsException) {
            record("failed", e.message ?: "intervals error"); SyncOutcome.Failed(e.message ?: "intervals error")
        } catch (e: java.io.IOException) {
            record("pending", "network: ${e.message}"); SyncOutcome.NotYet
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "unknown error"
            record("failed", msg); SyncOutcome.Failed(msg)
        }
    }
}
```

- [ ] **Step 8: Fix the one Android call site**

`RecorderService.kt` in `runSyncPass` uses `out.activityId` only; it still compiles. Nothing to change here yet (Task 4 uses the label).

- [ ] **Step 9: Run the whole suite**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: all PASS.

- [ ] **Step 10: Commit**

```bash
git add -A phone
git commit -m "phone: match activities by time overlap instead of start proximity

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Merge every session that targets the same activity into one push

**Files:**
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/sync/StreamAligner.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/sync/SyncEngine.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/sync/StreamAlignerTest.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/sync/SyncEngineTest.kt`

**Interfaces:**
- Produces: `StreamAligner.align(timeStream, hrStream, activityStart, series: List<Series>, reserve)`. For each activity sample the first series that has a non-null `ve` at that second supplies the values.

- [ ] **Step 1: Update the aligner tests to pass a list, and add a two-series case**

In `StreamAlignerTest.kt`, wrap every existing `series` argument passed to `StreamAligner.align(...)` in `listOf(...)`. Then add:

```kotlin
    @Test fun `two series fill different parts of the same axis`() {
        val start = Instant.ofEpochSecond(2_000)
        val s1 = Series(start.epochSecond, listOf(
            Sample(start.epochSecond, 40.0, 20.0, 2.0, 1.0, 1),
            Sample(start.epochSecond + 1, null, null, null, null, null),
        ))
        val s2 = Series(start.epochSecond + 1, listOf(
            Sample(start.epochSecond + 1, 60.0, 20.0, 3.0, 1.0, 1),
        ))
        val out = StreamAligner.align(listOf(0.0, 1.0, 2.0), null, start, listOf(s1, s2), r)
        assertEquals(listOf(40.0, 60.0, null), out.first { it.type == StreamCodes.VE }.data)
    }
```
The file already imports `Sample`, `Series` and `Instant` and defines `r`.

- [ ] **Step 2: Add the merge test to SyncEngineTest**

Session A is 320 s long with one breath at +5 s (VE 40); session B runs +330 s to +700 s with one breath at +340 s (VE 60). The activity starts at the session-A start and lasts 900 s, so both sessions clear the 5 minute overlap.

```kotlin
    @Test fun `a second session for an already synced activity carries the first session's data too`() {
        val store = SessionStore(tmp.root)
        val a = session(store, lengthMs = 320_000)                       // breath at +5 s, VE 40
        val bStart = startMs + 330_000
        store.create(bStart, "strap").apply {
            append(SessionEvent.Breath(startMs + 340_000, 20.0, 3.0, 1.0, 300, 100, 100, 1)); close()   // VE 60
        }
        val b = SessionStore.idFor(bStart)
        store.finish(b, startMs + 700_000, "idle")

        val api = FakeApi().apply {
            activities = listOf(ActivitySummary("i1", Instant.ofEpochMilli(startMs), "Ride", "Ride", "TPV", null, 900))
            streams = listOf(Stream("time", listOf(0.0, 5.0, 340.0, 800.0)))
        }
        val engine = SyncEngine(api, store)
        assertTrue(engine.sync(a, settings, startMs + 1_000_000) is SyncOutcome.Synced)
        assertEquals(listOf(null, 40.0, null, null), api.lastPut!!.first { it.type == StreamCodes.VE }.data)

        assertTrue(engine.sync(b, settings, startMs + 1_000_000) is SyncOutcome.Synced)
        assertEquals(listOf(null, 40.0, 60.0, null), api.lastPut!!.first { it.type == StreamCodes.VE }.data)
        assertEquals("i1", store.meta(b)!!.activityId)
    }
```

- [ ] **Step 3: Run the sync tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tymewear.run.domain.sync.*'
```
Expected: compilation FAILS on the `List<Series>` argument.

- [ ] **Step 4: Change StreamAligner to take a list**

Replace the `align` function in `StreamAligner.kt`:
```kotlin
    fun align(timeStream: List<Double?>, hrStream: List<Double?>?, activityStart: Instant, series: List<Series>, reserve: ReserveSettings): List<Stream> {
        val n = timeStream.size
        val ve = ArrayList<Double?>(n); val br = ArrayList<Double?>(n); val tv = ArrayList<Double?>(n)
        val ie = ArrayList<Double?>(n); val zone = ArrayList<Double?>(n); val brr = ArrayList<Double?>(n); val mi = ArrayList<Double?>(n)
        for (i in 0 until n) {
            val t = timeStream[i]
            val s = if (t == null) null else sampleAt(series, activityStart.epochSecond + floor(t).toLong())
            val hr = hrStream?.getOrNull(i)
            ve.add(s?.ve); br.add(s?.br); tv.add(s?.tv); ie.add(s?.ie); zone.add(s?.zone?.toDouble())
            brr.add(Reserve.percentBrr(s?.br, reserve))
            mi.add(Reserve.mobilizationIndex(s?.br, hr, reserve))
        }
        return listOf(
            Stream(StreamCodes.VE, ve, true), Stream(StreamCodes.BR, br, true), Stream(StreamCodes.TV, tv, true),
            Stream(StreamCodes.IE, ie, true), Stream(StreamCodes.ZONE, zone, true),
            Stream(StreamCodes.BRR, brr, true), Stream(StreamCodes.MI, mi, true),
        )
    }

    /** The first series with a live sample at this second; sessions never overlap in time, so order only matters for ties. */
    private fun sampleAt(series: List<Series>, epochSec: Long): Sample? =
        series.firstNotNullOfOrNull { s -> s.at(epochSec)?.takeIf { it.ve != null } }
```
Add `import com.tymewear.run.domain.session.Sample`.

- [ ] **Step 5: SyncEngine builds every series for the activity**

In `SyncEngine.kt` replace
```kotlin
            val series = SeriesBuilder.build(store.events(sessionId), settings.thresholds)
            val aligned = StreamAligner.align(time, hr, activity.startDate, series, settings.reserve)
```
with
```kotlin
            // Other sessions already pushed to this activity must ride along, or this push's
            // nulls outside its own span would blank them.
            val others = store.list().filter { it.id != sessionId && it.activityId == activity.id && it.syncState == "synced" && it.endMs != null }
            val allSeries = (listOf(meta) + others).map { SeriesBuilder.build(store.events(it.id), settings.thresholds) }
            val aligned = StreamAligner.align(time, hr, activity.startDate, allSeries, settings.reserve)
```

- [ ] **Step 6: Run the whole suite**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add -A phone
git commit -m "phone: fold every session on an activity into one stream push

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Recording notification and reworded sync notifications

**Files:**
- Create: `phone/app/src/main/kotlin/com/tymewear/run/domain/NotificationPolicy.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/Notifications.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/RecorderService.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/NotificationPolicyTest.kt`

**Interfaces:**
- Consumes: `SessionMeta`, `LivePayload.ve`, `SyncOutcome.Synced.activityLabel` (Task 2).
- Produces: `NotificationPolicy.notifyUnmatched(meta): Boolean`, `NotificationPolicy.recordingBody(startMs, ve, zone: ZoneId): String`, `NotificationPolicy.RECORDING_REFRESH_MS`; `Notifications.recording(ctx, body)`, `Notifications.clearRecording(ctx)`, `Notifications.ID_RECORDING = 2`.

- [ ] **Step 1: Write the policy test**

```kotlin
package com.tymewear.run.domain

import com.tymewear.run.domain.session.SessionMeta
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    private val m = SessionMeta(id = "s", startMs = 0)

    @Test fun `unmatched notifies only for sessions of fifteen minutes or more`() {
        assertFalse(NotificationPolicy.notifyUnmatched(m))                               // still open
        assertFalse(NotificationPolicy.notifyUnmatched(m.copy(endMs = 15 * 60_000L - 1)))
        assertTrue(NotificationPolicy.notifyUnmatched(m.copy(endMs = 15 * 60_000L)))
    }

    @Test fun `recording body shows start time and rounded VE`() {
        val start = 1_790_000_000_000L   // 2026-09-21T14:13:20Z
        assertEquals("Since 14:13 · VE 34 L/min", NotificationPolicy.recordingBody(start, 34.4, ZoneOffset.UTC))
        assertEquals("Since 14:13 · VE -- L/min", NotificationPolicy.recordingBody(start, null, ZoneOffset.UTC))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tymewear.run.domain.NotificationPolicyTest'
```
Expected: compilation FAILS, `NotificationPolicy` unresolved.

- [ ] **Step 3: Write NotificationPolicy**

```kotlin
package com.tymewear.run.domain

import com.tymewear.run.domain.session.SessionMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Pure decisions behind the Android notifications, so they can be tested on the JVM. */
object NotificationPolicy {
    const val UNMATCHED_MIN_MS = 15 * 60 * 1000L
    const val RECORDING_REFRESH_MS = 30_000L

    /** Short strap-driven sessions (fitting the strap, a false start) end unmatched and should not nag. */
    fun notifyUnmatched(meta: SessionMeta): Boolean {
        val end = meta.endMs ?: return false
        return end - meta.startMs >= UNMATCHED_MIN_MS
    }

    fun recordingBody(startMs: Long, ve: Double?, zone: ZoneId): String {
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(startMs))
        val veText = ve?.roundToInt()?.toString() ?: "--"
        return "Since $time · VE $veText L/min"
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Same command. Expected: PASS.

- [ ] **Step 5: Notifications.kt changes**

Add the constant and two functions, and reword the two existing ones:

```kotlin
    const val ID_RECORDING = 2
```
```kotlin
    fun recording(ctx: Context, body: String) = post(ctx, ID_RECORDING,
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("Recording breathing data")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .build())

    fun clearRecording(ctx: Context) =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_RECORDING)
```
Change `synced` to take the label:
```kotlin
    fun synced(ctx: Context, sessionId: String, activityId: String, activityLabel: String) = post(ctx, sessionId.hashCode(),
        NotificationCompat.Builder(ctx, CHANNEL_SYNC)
            .setContentTitle("Breathing data synced to Intervals.icu")
            .setContentText("Added to $activityLabel")
```
(rest of the builder unchanged). Change `unmatched` texts:
```kotlin
            .setContentTitle("No Intervals.icu activity found for a breathing session")
            .setContentText("Open K-Breathe Run to match it by hand")
```

- [ ] **Step 6: RecorderService changes**

Add these imports to `RecorderService.kt`:
```kotlin
import com.tymewear.run.domain.NotificationPolicy
import java.time.ZoneId
```
Add these two fields next to `lastNotificationText`:
```kotlin
    private var recordingShownFor: String? = null
    private var lastRecordingPostMs = 0L
```
Replace the whole `housekeeping()` function with:
```kotlin
    private suspend fun housekeeping() {
        var lastSyncMs = 0L
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            Graph.sessions.tick(now)?.let { Timber.i("Session closed: $it") }
            if (relay == null) {
                try {
                    relay = RelayServer(Constants.RELAY_PORT, Graph.live, Graph.settings, Graph.sessions, version = BuildConfig.VERSION_NAME)
                        .also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                } catch (e: java.io.IOException) {
                    Timber.e(e, "relay failed to bind")
                    relay = null
                }
            }
            val status = Graph.live.status(now)
            val session = Graph.sessions.activeSessionId
            val text = buildString {
                append(
                    when (status) {
                        StrapStatus.CONNECTED -> "Strap connected"
                        StrapStatus.STALE -> "Strap data stale"
                        StrapStatus.DISCONNECTED -> "Waiting for strap"
                        StrapStatus.OFF -> "Service off"
                    }
                )
                if (session != null) append(" · recording ").append(session)
                if (relay == null) append(" · relay down")
            }
            if (text != lastNotificationText) {
                lastNotificationText = text
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(Notifications.ID_SERVICE, Notifications.serviceNotification(this, text))
            }

            // Recording notification: shown while a session is open, body refreshed at most every 30 s.
            if (session == null) {
                if (recordingShownFor != null) { Notifications.clearRecording(this); recordingShownFor = null }
            } else if (session != recordingShownFor || now - lastRecordingPostMs >= NotificationPolicy.RECORDING_REFRESH_MS) {
                val startMs = Graph.sessionStore.meta(session)?.startMs ?: now
                val ve = Graph.live.payload(Graph.settings.load(), now).ve
                Notifications.recording(this, NotificationPolicy.recordingBody(startMs, ve, ZoneId.systemDefault()))
                recordingShownFor = session
                lastRecordingPostMs = now
            }

            if (now - lastSyncMs >= 60_000) { lastSyncMs = now; runSyncPass(now) }
            if (now - lastPruneMs >= 86_400_000) { lastPruneMs = now; Graph.sessionStore.prune(now, Graph.settings.load().retentionDays) }

            val settings = Graph.settings.load()
            if (ServiceLifecycle.shouldStop(Graph.strapPresence, session != null, settings.serviceEnabled)) {
                Timber.i("Stopping service: presence=${Graph.strapPresence}, session=$session, serviceEnabled=${settings.serviceEnabled}")
                stopSelf()
                return
            }

            delay(10_000)
        }
    }
```
(Task 5 later changes the `RelayServer(...)` construction inside it to the host form; leave it as above for now.)

Replace the whole `runSyncPass()` function with:
```kotlin
    private suspend fun runSyncPass(now: Long) = withContext(Dispatchers.IO) {
        try {
            val settings = Graph.settings.load()
            val metas = Graph.sessionStore.list()
            for (m in SyncScheduler.expired(metas, now)) {
                Graph.sessionStore.updateMeta(m.id) { it.copy(syncState = "unmatched", syncMessage = "no Intervals.icu activity overlapped this session within 6 hours") }
                if (NotificationPolicy.notifyUnmatched(m)) Notifications.unmatched(this@RecorderService, m.id)
            }
            val key = settings.intervalsApiKey ?: return@withContext
            val engine = SyncEngine(IntervalsClient(key), Graph.sessionStore)
            for (m in SyncScheduler.due(metas, now)) {
                try {
                    when (val out = engine.sync(m.id, settings, now)) {
                        is SyncOutcome.Synced -> Notifications.synced(this@RecorderService, m.id, out.activityId, out.activityLabel)
                        is SyncOutcome.Failed -> Notifications.syncFailed(this@RecorderService, m.id, out.message)
                        else -> {}
                    }
                } catch (e: Exception) {
                    Timber.w(e, "sync pass failed")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "sync pass failed")
        }
    }
```
Replace `onDestroy()` with:
```kotlin
    override fun onDestroy() {
        connector.stop()
        relay?.stop(); relay = null
        Notifications.clearRecording(this)
        scope.cancel()
        super.onDestroy()
    }
```

- [ ] **Step 7: Build and test**

```bash
./gradlew :app:testDebugUnitTest assembleDebug
```
Expected: tests PASS, APK builds.

- [ ] **Step 8: Commit**

```bash
git add -A phone
git commit -m "phone: recording notification and activity-neutral sync notifications

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Token-protected LAN relay with an overlay page (domain)

**Files:**
- Create: `phone/app/src/main/kotlin/com/tymewear/run/domain/relay/LanToken.kt`
- Create: `phone/app/src/main/kotlin/com/tymewear/run/domain/relay/OverlayPage.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/relay/RelayServer.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/domain/Settings.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/PrefsSettingsStore.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/RecorderService.kt` (two `RelayServer(...)` constructions)
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/ui/SettingsScreen.kt` (pass the two new fields through on save; UI comes in Task 6)
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/relay/LanTokenTest.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/relay/RelayServerTest.kt`
- Test: `phone/app/src/test/kotlin/com/tymewear/run/domain/SettingsTest.kt`

**Interfaces:**
- Produces: `RelayServer(host: String, port: Int, live, settings, sessions, clock, version, token: String? = null)`. With `token == null` (loopback) behaviour is unchanged plus `GET /overlay`. With a token: every request needs `?token=<t>` or `Authorization: Bearer <t>` else `401`; `POST /session/*` is `404`. `LanToken.generate(random: SecureRandom = SecureRandom()): String` (22 chars). `OverlayPage.HTML: String`. `Settings.lanOverlayEnabled: Boolean` (default false), `Settings.lanToken: String?` (default null).

- [ ] **Step 1: Token test**

```kotlin
package com.tymewear.run.domain.relay

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTokenTest {
    @Test fun `token is 22 url-safe characters and differs each time`() {
        val a = LanToken.generate(SecureRandom())
        val b = LanToken.generate(SecureRandom())
        assertEquals(22, a.length)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]{22}")))
        assertNotEquals(a, b)
    }
}
```

- [ ] **Step 2: Relay tests for LAN mode and the overlay page**

In `RelayServerTest.kt`, change the `@Before` construction to the new signature:
```kotlin
        server = RelayServer("127.0.0.1", 0, live, InMemorySettingsStore(), sessions, clock = { 1_000_000 }, version = "t")
```
Change `call` to accept optional headers:
```kotlin
    private fun call(method: String, path: String, port: Int = server.listeningPort, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to body
    }
```
Add these tests:
```kotlin
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
            assertEquals(200, call("GET", "/live?token=s3cret", p).first)
            assertEquals(200, call("GET", "/health", p, mapOf("Authorization" to "Bearer s3cret")).first)
            assertEquals(200, call("GET", "/overlay?token=s3cret", p).first)
            assertEquals(404, call("POST", "/session/start?token=s3cret", p).first)
            assertEquals(404, call("POST", "/session/stop?token=s3cret", p).first)
            assertNull(sessions.activeSessionId)
        } finally { lan.stop() }
    }
```
Add `import org.junit.Assert.assertNull`.

- [ ] **Step 3: Settings default test**

In `SettingsTest.kt` `defaults match the spec` add:
```kotlin
        assertEquals(false, d.lanOverlayEnabled)
        assertEquals(null, d.lanToken)
```

- [ ] **Step 4: Run the relay and settings tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tymewear.run.domain.relay.*' --tests 'com.tymewear.run.domain.SettingsTest'
```
Expected: compilation FAILS.

- [ ] **Step 5: Settings fields**

`Settings.kt`: add after `retentionDays`:
```kotlin
    val lanOverlayEnabled: Boolean = false,
    val lanToken: String? = null,
```
`PrefsSettingsStore.kt` save: add
```kotlin
            .putBoolean("lan_overlay_enabled", settings.lanOverlayEnabled)
            .putString("lan_token", settings.lanToken ?: "")
```
read: add
```kotlin
            lanOverlayEnabled = prefs.getBoolean("lan_overlay_enabled", d.lanOverlayEnabled),
            lanToken = prefs.getString("lan_token", "")!!.ifBlank { null },
```
`SettingsScreen.kt`: in the `Settings(...)` construction inside the Save button add:
```kotlin
                        lanOverlayEnabled = initial.lanOverlayEnabled,
                        lanToken = initial.lanToken,
```
(Task 6 replaces these with live UI state.)

- [ ] **Step 6: LanToken**

```kotlin
package com.tymewear.run.domain.relay

import java.security.SecureRandom
import java.util.Base64

object LanToken {
    /** 16 random bytes as unpadded base64url: 22 characters, safe in a URL query. */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
```

- [ ] **Step 7: OverlayPage**

```kotlin
package com.tymewear.run.domain.relay

/** Self-contained overlay page served at GET /overlay. Polls /live once a second with the same token. */
object OverlayPage {
    val HTML: String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>K-Breathe</title>
<style>
  html,body{margin:0;height:100%;background:#111;color:#fff;font-family:system-ui,Segoe UI,Roboto,sans-serif}
  #panel{box-sizing:border-box;min-height:100%;padding:24px;background:#424242;transition:background .3s}
  #panel.dead{background:#616161}
  .top{display:flex;align-items:center;gap:8px;font-size:14px;color:#bdbdbd}
  #dot{width:12px;height:12px;border-radius:50%;background:#9e9e9e}
  #ve{font-size:18vw;font-weight:700;line-height:1}
  .unit{font-size:14px;color:#bdbdbd}
  #zone{font-size:5vw}
  .row{display:flex;gap:32px;font-size:6vw;margin-top:12px}
  .row span small{display:block;font-size:14px;color:#bdbdbd}
</style></head>
<body><div id="panel">
  <div class="top"><div id="dot"></div><div id="status">connecting</div></div>
  <div id="ve">--</div><div class="unit">L/min</div><div id="zone"></div>
  <div class="row"><span><small>BR</small><b id="br">--</b></span><span><small>TV</small><b id="tv">--</b></span></div>
</div>
<script>
  var ZONE_COLORS=['#424242','#4db6ac','#0277bd','#f57f17','#ef6c00','#c62828'];
  var ZONE_NAMES=['--','Endurance','VT1','VT2','Top Z4','VO2Max'];
  var token=new URLSearchParams(location.search).get('token');
  var liveUrl='/live'+(token?'?token='+encodeURIComponent(token):'');
  var failures=0;
  function el(id){return document.getElementById(id)}
  function render(p){
    failures=0; el('panel').classList.remove('dead');
    var live=p.status==='connected';
    el('ve').textContent=live&&p.ve!=null?Math.round(p.ve):'--';
    el('br').textContent=live&&p.br!=null?Math.round(p.br):'--';
    el('tv').textContent=live&&p.tv!=null?p.tv.toFixed(1):'--';
    var z=live?p.zone:0;
    el('zone').textContent=live?ZONE_NAMES[z]||'':'';
    el('panel').style.background=ZONE_COLORS[z]||ZONE_COLORS[0];
    el('status').textContent=p.status;
    el('dot').style.background=p.status==='connected'?'#66bb6a':p.status==='stale'?'#ffb300':'#9e9e9e';
  }
  function fail(){
    failures++; el('status').textContent='phone?'; el('dot').style.background='#c62828';
    if(failures>=5){el('panel').classList.add('dead');el('panel').style.background='';}
  }
  function poll(){
    var ctl=new AbortController(); var t=setTimeout(function(){ctl.abort()},900);
    fetch(liveUrl,{signal:ctl.signal,cache:'no-store'}).then(function(r){if(!r.ok)throw new Error(r.status);return r.json()})
      .then(render).catch(fail).then(function(){clearTimeout(t)});
  }
  poll(); setInterval(poll,1000);
</script></body></html>
"""
}
```

- [ ] **Step 8: RelayServer host and token mode**

Replace the class in `RelayServer.kt`:

```kotlin
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
```
Add `import java.security.MessageDigest`. NanoHTTPD lower-cases header names, so `headers["authorization"]` is correct.

- [ ] **Step 9: Update the two RelayServer constructions in RecorderService**

Both `RelayServer(Constants.RELAY_PORT, Graph.live, ...)` become:
```kotlin
                relay = RelayServer("127.0.0.1", Constants.RELAY_PORT, Graph.live, Graph.settings, Graph.sessions, version = BuildConfig.VERSION_NAME)
```

- [ ] **Step 10: Run the whole suite and build**

```bash
./gradlew :app:testDebugUnitTest assembleDebug
```
Expected: all PASS, APK builds.

- [ ] **Step 11: Commit**

```bash
git add -A phone
git commit -m "phone: token-protected relay mode and a self-contained overlay page

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: LAN listener lifecycle, settings switch, URL and QR on the Status tab (Android)

**Files:**
- Create: `phone/app/src/main/kotlin/com/tymewear/run/android/LanRelayManager.kt`
- Create: `phone/app/src/main/kotlin/com/tymewear/run/android/ui/QrImage.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/Graph.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/RecorderService.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/ui/SettingsScreen.kt`
- Modify: `phone/app/src/main/kotlin/com/tymewear/run/android/ui/StatusScreen.kt`
- Modify: `phone/gradle/libs.versions.toml`, `phone/app/build.gradle.kts`
- Modify: `phone/app/src/main/AndroidManifest.xml` (`ACCESS_NETWORK_STATE`)

**Interfaces:**
- Consumes: `RelayServer(host, port, ..., token)` (Task 5), `Settings.lanOverlayEnabled`, `Settings.lanToken`, `LanToken.generate()`.
- Produces: `LanRelayManager(context, makeServer: (host: String, token: String) -> RelayServer)` with `apply(enabled: Boolean, token: String?)` and `stop()`; `Graph.lanOverlayUrl: MutableStateFlow<String?>`.

This task is Android-only and has no JVM test; verify by building and by the manual check in Step 8.

- [ ] **Step 1: Dependency and permission**

`phone/gradle/libs.versions.toml`: under `[versions]` add `zxing = "3.5.3"`; under `[libraries]` add
```toml
zxing-core = { module = "com.google.zxing:core", version.ref = "zxing" }
```
`phone/app/build.gradle.kts` dependencies: add `implementation(libs.zxing.core)`.

`AndroidManifest.xml`: add
```xml
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

- [ ] **Step 2: Graph flow**

In `Graph.kt` add:
```kotlin
    /** Overlay URL of the LAN relay while it is bound, else null. Written by LanRelayManager. */
    val lanOverlayUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
```

- [ ] **Step 3: LanRelayManager**

```kotlin
package com.tymewear.run.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.tymewear.run.domain.Constants
import com.tymewear.run.domain.relay.RelayServer
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.Inet4Address
import timber.log.Timber

/**
 * Runs the token-protected relay on the phone's Wi-Fi IPv4 address while the LAN overlay
 * setting is on. Binds the concrete address, never 0.0.0.0, so it is never reachable over
 * mobile data. Rebinds when the address changes and stops when Wi-Fi is lost.
 */
class LanRelayManager(
    context: Context,
    private val makeServer: (host: String, token: String) -> RelayServer,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var server: RelayServer? = null
    private var boundHost: String? = null
    private var token: String? = null

    @Synchronized
    fun apply(enabled: Boolean, newToken: String?) {
        if (!enabled || newToken == null) { stop(); return }
        if (newToken != token) { stop(); token = newToken }
        if (callback == null) register()
    }

    @Synchronized
    fun stop() {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (e: Exception) { Timber.w(e, "unregister failed") } }
        callback = null
        stopServer()
    }

    private fun register() {
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { rebind(lp) }
            override fun onLost(network: Network) { synchronized(this@LanRelayManager) { stopServer() } }
        }
        cm.registerNetworkCallback(req, cb)
        callback = cb
    }

    @Synchronized
    private fun rebind(lp: LinkProperties) {
        val t = token ?: return
        val ip = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        if (ip == null) { stopServer(); return }
        if (ip == boundHost && server != null) return
        stopServer()
        try {
            server = makeServer(ip, t).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            boundHost = ip
            Graph.lanOverlayUrl.value = "http://$ip:${Constants.RELAY_PORT}/overlay?token=$t"
            Timber.i("LAN relay bound on $ip")
        } catch (e: IOException) {
            Timber.e(e, "LAN relay failed to bind on $ip")
            server = null; boundHost = null
            Graph.lanOverlayUrl.value = null
        }
    }

    private fun stopServer() {
        server?.stop(); server = null; boundHost = null
        Graph.lanOverlayUrl.value = null
    }
}
```

- [ ] **Step 4: Wire it into RecorderService**

Add a field:
```kotlin
    private lateinit var lanRelay: LanRelayManager
```
In `onCreate()` after `connector = ...`:
```kotlin
        lanRelay = LanRelayManager(this) { host, token ->
            RelayServer(host, Constants.RELAY_PORT, Graph.live, Graph.settings, Graph.sessions, version = BuildConfig.VERSION_NAME, token = token)
        }
```
In the settings collector (`Graph.settings.changes.collect { s -> ... }`) add, right after the `idleStopMs` line:
```kotlin
                    lanRelay.apply(s.lanOverlayEnabled, s.lanToken)
```
In `onDestroy()` add `lanRelay.stop()` before `relay?.stop()`.

- [ ] **Step 5: Settings screen switch and regenerate**

In `SettingsScreen.kt` add state after `retentionDays`:
```kotlin
    var lanOverlay by remember { mutableStateOf(initial.lanOverlayEnabled) }
    var lanToken by remember { mutableStateOf(initial.lanToken) }
```
Add a section after the `IntField("Retention days", ...)` line and its divider:
```kotlin
        Text("LAN overlay")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Serve live values on Wi-Fi for the PC overlay")
            Switch(checked = lanOverlay, onCheckedChange = {
                lanOverlay = it
                if (it && lanToken == null) lanToken = LanToken.generate()
            })
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { lanToken = LanToken.generate() }) { Text("Regenerate token") }
            Text(if (lanToken == null) "No token yet" else "Token set; save to apply")
        }
        Text("The overlay URL appears on the Status tab once saved and Wi-Fi is connected.")

        HorizontalDivider()
```
Replace the two placeholder arguments from Task 5 in the `Settings(...)` construction with:
```kotlin
                        lanOverlayEnabled = lanOverlay,
                        lanToken = lanToken,
```
Add `import com.tymewear.run.domain.relay.LanToken`.

- [ ] **Step 6: QR composable**

```kotlin
package com.tymewear.run.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Renders [text] as a QR code. Sized by the caller's modifier; the bitmap is 512 px square. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val size = 512
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
    Image(bitmap.asImageBitmap(), contentDescription = "Overlay URL as QR code", modifier = modifier)
}
```

- [ ] **Step 7: Status screen section**

In `StatusScreen.kt` add imports `androidx.compose.runtime.collectAsState`, `androidx.compose.foundation.layout.size`, `android.content.ClipData`, `android.content.ClipboardManager`, and add after the "Battery optimisation" section:
```kotlin
        HorizontalDivider()

        Text("PC overlay")
        val lanUrl by Graph.lanOverlayUrl.collectAsState()
        val lanEnabled = Graph.settings.load().lanOverlayEnabled
        when {
            !lanEnabled -> Text("Off. Turn on \"LAN overlay\" on the Settings tab.")
            lanUrl == null -> Text("Waiting for Wi-Fi. The URL appears once the phone has a Wi-Fi address.")
            else -> {
                Text(lanUrl!!)
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("overlay url", lanUrl))
                }) { Text("Copy URL") }
                QrImage(lanUrl!!, Modifier.size(220.dp))
                Text("Open this on the PC (pc/overlay.ps1) or in any browser on the same Wi-Fi.")
            }
        }
```

- [ ] **Step 8: Build, install, check by hand**

```bash
./gradlew :app:testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/k-breathe-run.apk
```
Manual check, recorded in the commit message body if anything deviates: enable LAN overlay on Settings, Save, open Status: URL appears within a few seconds while on Wi-Fi. From a laptop on the same Wi-Fi, open the URL in a browser: the overlay page renders and shows `disconnected` or live values. Open the URL without the token: `401`. Toggle Wi-Fi off: URL disappears from Status.

- [ ] **Step 9: Commit**

```bash
git add -A phone
git commit -m "phone: serve the relay on Wi-Fi with a token and show the overlay URL as QR

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Windows overlay script

**Files:**
- Create: `pc/overlay.ps1`
- Create: `pc/overlay.cmd`
- Create: `pc/README.md`

**Interfaces:**
- Consumes: `GET /live?token=…` JSON with fields `ve`, `br`, `tv`, `zone` (0..5), `status` (`connected|stale|disconnected|off`), from the URL the phone shows (the `/overlay` URL; the script swaps the path for `/live`).
- Produces: a topmost WPF window; config at `%APPDATA%\KBreathe\overlay.json` with `url`, `x`, `y`, `opacity`.

No automated test; the README carries the manual checklist. The script is developed on the Mac and run on the PC, so write it carefully and keep it PowerShell 5.1 compatible (no `??`, no ternary).

- [ ] **Step 1: overlay.ps1**

```powershell
# K-Breathe overlay for TrainingPeaks Virtual. Polls the phone's relay once a second and shows
# VE, zone, BR and TV in a small always-on-top panel. Display only: it never starts or stops
# anything on the phone. PowerShell 5.1, WPF, no dependencies.
param([string]$Url)

Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Net.Http
[System.Reflection.Assembly]::LoadWithPartialName('Microsoft.VisualBasic') | Out-Null

$configDir  = Join-Path $env:APPDATA 'KBreathe'
$configPath = Join-Path $configDir 'overlay.json'
$config = @{ url = ''; x = -1; y = -1; opacity = 0.7 }
if (Test-Path $configPath) {
  try {
    $saved = Get-Content $configPath -Raw | ConvertFrom-Json
    foreach ($k in 'url', 'x', 'y', 'opacity') { if ($null -ne $saved.$k) { $config[$k] = $saved.$k } }
  } catch { }
}
if ($Url) { $config.url = $Url }

function Save-Config {
  New-Item -ItemType Directory -Force -Path $configDir | Out-Null
  $config | ConvertTo-Json | Set-Content -Path $configPath -Encoding UTF8
}
function Ask-Url([string]$current) {
  $v = [Microsoft.VisualBasic.Interaction]::InputBox('Paste the overlay URL shown on the phone Status tab', 'K-Breathe overlay', $current)
  if ($v) { return $v.Trim() } else { return $current }
}
if (-not $config.url) { $config.url = Ask-Url ''; if (-not $config.url) { exit }; Save-Config }

$zoneColors = '#424242', '#4db6ac', '#0277bd', '#f57f17', '#ef6c00', '#c62828'
$zoneNames  = '--', 'Endurance', 'VT1', 'VT2', 'Top Z4', 'VO2Max'

[xml]$xaml = @"
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="K-Breathe" Width="260" Height="140" WindowStyle="None" AllowsTransparency="True"
        Background="Transparent" Topmost="True" ShowInTaskbar="False" ResizeMode="NoResize">
  <Border Name="Panel" CornerRadius="10" Background="#424242" Padding="12">
    <Grid>
      <Grid.RowDefinitions>
        <RowDefinition Height="Auto"/><RowDefinition Height="*"/><RowDefinition Height="Auto"/>
      </Grid.RowDefinitions>
      <StackPanel Orientation="Horizontal" Grid.Row="0">
        <Ellipse Name="Dot" Width="10" Height="10" Fill="#9e9e9e" Margin="0,0,6,0" VerticalAlignment="Center"/>
        <TextBlock Name="Status" Text="connecting" Foreground="#bdbdbd" FontSize="12"/>
      </StackPanel>
      <StackPanel Grid.Row="1" Orientation="Horizontal" VerticalAlignment="Center">
        <TextBlock Name="Ve" Text="--" Foreground="White" FontSize="44" FontWeight="Bold"/>
        <StackPanel VerticalAlignment="Bottom" Margin="8,0,0,8">
          <TextBlock Text="L/min" Foreground="#bdbdbd" FontSize="12"/>
          <TextBlock Name="Zone" Text="" Foreground="White" FontSize="14"/>
        </StackPanel>
      </StackPanel>
      <TextBlock Name="Row" Grid.Row="2" Text="BR --    TV --" Foreground="White" FontSize="16"/>
    </Grid>
  </Border>
</Window>
"@

$window = [Windows.Markup.XamlReader]::Load((New-Object System.Xml.XmlNodeReader $xaml))
$panel  = $window.FindName('Panel');  $dot  = $window.FindName('Dot');  $status = $window.FindName('Status')
$veText = $window.FindName('Ve');     $zone = $window.FindName('Zone'); $row    = $window.FindName('Row')
$window.Opacity = [double]$config.opacity
if ([double]$config.x -ge 0) { $window.Left = [double]$config.x; $window.Top = [double]$config.y }
else {
  $wa = [System.Windows.SystemParameters]::WorkArea
  $window.Left = $wa.Right - $window.Width - 16; $window.Top = $wa.Top + 16
}

function Brush([string]$hex) { return [System.Windows.Media.BrushConverter]::new().ConvertFromString($hex) }

$script:failures = 0
function Render($p) {
  $script:failures = 0
  $live = $p.status -eq 'connected'
  if ($live -and $null -ne $p.ve) { $veText.Text = [string][math]::Round($p.ve) } else { $veText.Text = '--' }
  $br = '--'; $tv = '--'
  if ($live -and $null -ne $p.br) { $br = [string][math]::Round($p.br) }
  if ($live -and $null -ne $p.tv) { $tv = ('{0:N1}' -f $p.tv) }
  $row.Text = "BR $br    TV $tv"
  $z = 0; if ($live -and $p.zone -ge 0 -and $p.zone -lt $zoneColors.Count) { $z = [int]$p.zone }
  $zone.Text = $(if ($live) { $zoneNames[$z] } else { '' })
  $panel.Background = Brush $zoneColors[$z]
  $status.Text = $p.status
  $dotColor = switch ($p.status) { 'connected' { '#66bb6a' } 'stale' { '#ffb300' } default { '#9e9e9e' } }
  $dot.Fill = Brush $dotColor
}
function RenderFailure {
  $script:failures++
  $status.Text = 'phone?'; $dot.Fill = Brush '#c62828'
  if ($script:failures -ge 5) { $panel.Background = Brush '#616161'; $veText.Text = '--'; $row.Text = 'BR --    TV --'; $zone.Text = '' }
}

$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromMilliseconds(900)
$script:pending = $null
$timer = New-Object System.Windows.Threading.DispatcherTimer
$timer.Interval = [TimeSpan]::FromSeconds(1)
$timer.Add_Tick({
  if ($null -ne $script:pending) {
    if (-not $script:pending.IsCompleted) { return }
    $t = $script:pending; $script:pending = $null
    if ($t.Status -eq 'RanToCompletion') {
      try { Render ($t.Result | ConvertFrom-Json) } catch { RenderFailure }
    } else { RenderFailure }
  }
  $liveUrl = $config.url -replace '/overlay(\?|$)', '/live$1'
  $script:pending = $http.GetStringAsync($liveUrl)
})

$menu = New-Object System.Windows.Controls.ContextMenu
$mi = New-Object System.Windows.Controls.MenuItem; $mi.Header = 'Set phone URL...'
$mi.Add_Click({ $config.url = Ask-Url $config.url; Save-Config; $script:failures = 0 }); $menu.Items.Add($mi) | Out-Null
foreach ($o in 0.5, 0.7, 0.9) {
  $m = New-Object System.Windows.Controls.MenuItem; $m.Header = ('Opacity {0}%' -f [int]($o * 100)); $m.Tag = $o
  $m.Add_Click({ $config.opacity = [double]$this.Tag; $window.Opacity = [double]$this.Tag; Save-Config }); $menu.Items.Add($m) | Out-Null
}
$q = New-Object System.Windows.Controls.MenuItem; $q.Header = 'Quit'; $q.Add_Click({ $window.Close() }); $menu.Items.Add($q) | Out-Null
$window.ContextMenu = $menu

$window.Add_MouseLeftButtonDown({ $window.DragMove() })
$window.Add_Closing({ $config.x = $window.Left; $config.y = $window.Top; Save-Config; $timer.Stop() })

$timer.Start()
$window.ShowDialog() | Out-Null
```

- [ ] **Step 2: overlay.cmd**

```bat
@echo off
rem Launches the K-Breathe overlay with the console hidden. Double-click, or make a shortcut to this file.
start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0overlay.ps1" %*
```

- [ ] **Step 3: pc/README.md**

```markdown
# K-Breathe PC overlay

A small always-on-top panel for Windows that shows live breathing values from the K-Breathe Run
phone app while you ride in TrainingPeaks Virtual (TPV). It is display only. Recording and the
Intervals.icu merge happen on the phone; see `../phone/README.md`.

## Setup (once)

1. On the phone, Settings tab: turn on **LAN overlay**, tap **Save**.
2. Phone on the same Wi-Fi as the PC. Status tab, "PC overlay": copy the URL (or scan the QR code
   on the PC's phone-camera app and paste).
3. Copy `overlay.ps1` and `overlay.cmd` anywhere on the PC.
4. Double-click `overlay.cmd`. Paste the URL when asked. The panel appears top-right.
5. In TPV, set the display mode to **borderless windowed** (or windowed), not exclusive
   fullscreen. Exclusive fullscreen hides every other window, including this one.

The URL is kept in `%APPDATA%\KBreathe\overlay.json` together with the panel position and opacity.

## Using it

- Drag the panel anywhere. Right-click for opacity, to change the phone URL, or to quit.
- The dot is green when the strap is connected, amber when data is stale, grey when the strap is
  disconnected or the service is off, and red with `phone?` when the phone cannot be reached.
  After five failed polls the panel goes grey.
- The background colour is the current VE zone: grey (no zone), teal Endurance, blue VT1, amber VT2,
  orange Top Z4, red VO2Max.
- There is no mobilization index on the overlay; MI needs heart rate, which the phone does not have
  live. It is in Intervals.icu after the sync.

## If the URL stops working

The phone's Wi-Fi address can change (router reboot, new network). The panel shows `phone?`.
Copy the new URL from the phone's Status tab and use right-click, **Set phone URL...**.

## Fallback without PowerShell

The same URL opens in any browser on any device on the Wi-Fi, for example a tablet next to the
trainer. The page is served by the phone and needs nothing installed.

## Manual checklist (run once after any change to overlay.ps1)

1. First run with no config prompts for the URL and then shows the panel top-right.
2. With the strap on and the phone app recording, VE, BR, TV and the zone colour update within
   two seconds of the phone's own Status tab.
3. Turn the phone's Wi-Fi off: the dot turns red with `phone?`, and after five seconds the panel
   greys out. Turn Wi-Fi back on: values return without restarting the script.
4. Drag the panel, set opacity 50 %, quit, start again: position and opacity are restored.
5. Start TPV in borderless windowed mode: the panel stays on top of TPV.
```

- [ ] **Step 4: Syntax-check the script from the Mac if PowerShell is installed, otherwise skip**

```bash
command -v pwsh >/dev/null && pwsh -NoProfile -Command '[void][System.Management.Automation.Language.Parser]::ParseFile("pc/overlay.ps1", [ref]$null, [ref]$errs); $errs | ForEach-Object { $_.Message }; "parse errors: $($errs.Count)"' || echo "pwsh not installed; parse check skipped"
```
Expected: `parse errors: 0`, or the skip message. (WPF itself only runs on Windows; the manual checklist covers behaviour.)

- [ ] **Step 5: Commit**

```bash
git add pc
git commit -m "pc: always-on-top PowerShell overlay for TrainingPeaks Virtual

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Documentation

**Files:**
- Modify: `phone/README.md`
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-22-strap-driven-sessions-and-tpv-overlay-design.md` (status line only)

- [ ] **Step 1: phone/README.md**

Make these edits:

1. First paragraph: replace "the watch half ... does not exist yet — see `../watch/` (planned)" with "the watch half is the Zepp OS extension in `../watch/`, and `../pc/` holds the Windows overlay for TrainingPeaks Virtual". Replace "during a run" with "during any activity".
2. "What it does": replace the third bullet with "Opens a session when the strap starts streaming and closes it after 3 minutes without breathing data (configurable). The watch relay endpoints and the manual buttons still work but are not needed." Replace "waits for the matching Amazfit/Zepp activity" with "waits for the Intervals.icu activity that overlaps the session by at least 5 minutes (an Amazfit run, a TrainingPeaks Virtual ride, a Karoo ride, anything except Strava imports)". Add a bullet: "Serves the same live values, token-protected, on the phone's Wi-Fi address for the PC overlay."
3. "Sessions" section, replace entirely:

```markdown
## Sessions

Put the strap on: the first breath packet opens a session, and you get a **Recording breathing
data** notification. Take it off (or stop breathing into it): after 3 minutes without breathing
data the session closes and the notification disappears. Settings tab, "Stop session after no
breathing data for" changes the 3 minutes. A session also closes at 8 hours.

The watch's `POST /session/start` and `/session/stop` on `127.0.0.1:41415` and the Status tab's
**Start session** / **Stop session** buttons still work: they open a session early or close one
early. A session closed by the button reopens on the next breath, so to really stop, take the
strap off.

Sessions shorter than 60 seconds are marked `skipped` and are not synced. Session data is kept
for 90 days, then pruned.

Because starting is automatic, put the strap on a couple of minutes before the activity: presence
detection and the strap connection take up to a minute, and the session must overlap the activity
by at least 5 minutes to match.

**Karoo rides.** The strap accepts one Bluetooth connection, and the Karoo's own K-Breathe extension
records the same breathing fields into its FIT file. Before a Karoo ride, turn **Service enabled**
off on the Settings tab so the phone leaves the strap to the Karoo; turn it back on afterwards.
If the phone does take the strap during a Karoo ride, the sync never pushes to a Karoo activity,
so the Karoo's own recording is never overwritten.
```
4. "Intervals.icu sync" section: replace "looking for an activity that starts within 5 minutes of the session" with "looking for the activity whose time span overlaps the session the most, with at least 5 minutes of overlap. Strava imports are skipped because Intervals.icu does not allow editing them, and Karoo activities are skipped because the Karoo records breathing itself." Add after the bullet list: "If two activities overlap the same session (for example a watch recording and a TPV recording of the same ride), the larger overlap gets the data and the other is named in the session's message; use **Match by id** to push to it as well. If the strap dropped out for more than 3 minutes mid-activity you get two sessions; both match the same activity and the second push carries the first session's data too, nothing is blanked."
5. Notifications table: replace with

| Notification | Meaning |
|---|---|
| Recording breathing data | A session is open; the body shows the start time and current VE |
| Breathing data synced to Intervals.icu | Streams pushed; tap opens the activity |
| Intervals.icu sync failed | Push failed; the text names the reason |
| No Intervals.icu activity found for a breathing session | No overlapping activity after 6 hours (only for sessions of 15 minutes or more); match by hand on the Sessions tab |

6. Add a section before "Developer notes":

```markdown
## PC overlay (TrainingPeaks Virtual)

Settings tab: turn on **LAN overlay** and save. The Status tab then shows an overlay URL and QR
code while the phone is on Wi-Fi. That URL serves the live values (token-protected, read-only,
no session control) to anything on the same network: the Windows script in `../pc/`, or a
browser on a tablet. **Regenerate token** on the Settings tab invalidates the old URL.
```
7. "First real run" section: rename to "First real session" and replace step 2 with:

```markdown
2. **Record a session.** Pair the strap on the Status tab, then swipe the app away. Put the strap
   on: within about two minutes the "Recording breathing data" notification should appear without
   opening the app. Start an activity on any device that syncs to Intervals.icu (the Amazfit
   watch, TrainingPeaks Virtual, a Karoo). Ride or run for at least 6 minutes, stop the activity,
   take the strap off. About 3 minutes later the notification disappears. Within a few minutes
   more, the Sessions tab shows the session as `synced` with an activity id and you get the synced
   notification.
2a. **Reboot check.** Restart the phone, do not open the app, put the strap on: the recording
   notification should still appear. If either wake-up test fails, exclude the app from battery
   optimisation on the Status tab and repeat; note whether that fixed it.
```

- [ ] **Step 2: Root README.md**

Add a subproject bullet:
```markdown
- [`pc/`](pc/README.md) — Windows always-on-top overlay for TrainingPeaks Virtual, fed by the phone app over Wi-Fi.
```
Under "Design" add the new spec and plan:
```markdown
The 2026-09-22 extension (strap-driven sessions, overlap matching, PC overlay) is in [`docs/superpowers/specs/2026-09-22-strap-driven-sessions-and-tpv-overlay-design.md`](docs/superpowers/specs/2026-09-22-strap-driven-sessions-and-tpv-overlay-design.md) with its plan at [`docs/superpowers/plans/2026-09-22-strap-driven-sessions-and-tpv-overlay.md`](docs/superpowers/plans/2026-09-22-strap-driven-sessions-and-tpv-overlay.md).
```
Change the first line's description from "recorded on the phone and merged into Intervals.icu" to "recorded on the phone whenever the strap is worn and merged into the matching Intervals.icu activity, whatever recorded it".

- [ ] **Step 3: Spec status**

In the spec change `**Status:** Approved in discussion, awaiting spec review` to `**Status:** Implemented 2026-09-22; hardware checks pending (phone README, First real session)`.

- [ ] **Step 4: Commit**

```bash
git add README.md phone/README.md docs/superpowers/specs/2026-09-22-strap-driven-sessions-and-tpv-overlay-design.md
git commit -m "docs: strap-driven sessions, overlap matching and the PC overlay

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Spec coverage check

| Spec section | Task |
|---|---|
| 4 Strap-driven sessions, idle timeout, setting rename | 1 |
| 4.2 Presence keeps service alive during a session | already true (`ServiceLifecycle`), no change |
| 5 Overlap matching, elapsed_time, runner-up message, skew guard removed | 2 |
| 6.1 Loopback + LAN listener, token, no session control on LAN | 5 (domain), 6 (lifecycle) |
| 6.2 Token, regenerate, URL text + QR on Status | 5, 6 |
| 6.3 `GET /overlay` page | 5 |
| 7 Windows overlay script, config, menu, failure states | 7 |
| 7.3 Borderless-windowed day-one check | 7 (README) |
| 8 Settings changes | 1, 5, 6 |
| 8a Notifications | 4 |
| 8b Wake-up checks | 8 (README checklist) |
| 9 Two sessions on one activity merge | 3 |
| 9 Unmatched under 15 minutes silent | 4 |
| 10 Tests | each task |
| 11 Out of scope | nothing added |
