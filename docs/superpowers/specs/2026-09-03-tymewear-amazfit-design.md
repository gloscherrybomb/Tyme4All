# K-Breathe Run: Tymewear VitalPro with an Amazfit Cheetah 2 Ultra

**Date:** 2026-09-03
**Status:** Draft for review
**Related:** `TymewearKaroo` (K-Breathe for Hammerhead Karoo), `Suunto` (VE Zones SuuntoPlus app)

## 1. Goal

Run with an Amazfit Cheetah 2 Ultra and a Tymewear VitalPro breathing strap, and get:

1. **Live breathing data on the watch** during the run: minute ventilation (VE), breathing rate (BR), tidal volume (TV), the current ventilation zone, and the mobilization index (MI), which combines breathing reserve with the watch's own heart rate.
2. **Gap-free recording** of the breathing data for the whole run.
3. **The correct, merged data in Intervals.icu**, alongside the watch's own pace, heart rate and GPS.

All of this happens with **no interaction after initial configuration**. The runner starts and stops the run on the watch exactly as they would without the strap.

## 2. Platform constraints that shape the design

These were established from the Zepp OS developer documentation and the Intervals.icu OpenAPI spec (`https://intervals.icu/api/v1/docs`) on 2026-09-03.

| Constraint | Consequence |
|---|---|
| Zepp OS apps can act as a BLE central (API level 3.0+), but **background App Services may not use any BLE central API** and may not use timers. | The watch cannot hold the strap connection in the background. Recording cannot live on the watch. |
| A Zepp OS **workout extension** (custom data page inside the native Workout app) is **paused whenever it is not the visible page**: callbacks disabled, timers frozen. | The extension is fine for display, useless for recording. |
| No Zepp OS API writes developer fields into the native workout FIT file. | Breathing data cannot be recorded into the watch's activity file at record time. |
| The Zepp cloud has no public API. Manual FIT export from the Zepp phone app is the only local route to the watch file. | Any design that needs the watch file on the phone requires a manual step. |
| The Zepp phone app **syncs every workout to Intervals.icu automatically** within minutes (official integration). | Intervals.icu is the zero-touch meeting point for watch data and breathing data. |
| Intervals.icu API: `GET /api/v1/activity/{id}/streams{ext}` returns the activity's streams; `PUT /api/v1/activity/{id}/streams` (JSON, same shape as GET) **updates or creates stored streams, including custom streams**, aligned to the activity time axis. Stream upload requires a Supporter subscription. | Breathing data can be attached to the existing Intervals.icu activity in place. No delete, no re-upload, and every Amazfit metric is preserved. |
| Zepp OS side services (the phone half of a mini program) can `fetch()` HTTP URLs, and there is precedent for fetching `http://localhost:<port>` on the same phone. | The phone app can feed the watch through the Zepp app. |
| Cheetah 2 Ultra: round 480x480, listed at API level 4.3 / Zepp OS 5.0, updated to Zepp OS 6 in June 2026. Workout extensions need API level 3.6+. | Extension support is expected but the official supported-device list has not been updated to include it. **Unverified until the watch arrives.** |

## 3. Architecture

Three cooperating parts. The phone is the hub.

```
 VitalPro strap ──BLE──▶ Phone app (Android, Kotlin)
                            │  ├─ records breathing session (raw per-breath log)
                            │  ├─ loopback HTTP relay  (127.0.0.1:41415)
                            │  └─ Intervals.icu client (poll, fetch streams, push streams)
                            │
                  Zepp side service (JS, inside Zepp phone app)
                            │  polls relay while extension is on screen
                            ▼
                  Zepp workout extension (JS, on watch)
                            │  one data page in the native Run
                            │
 Watch ──Zepp sync──▶ Zepp cloud ──official integration──▶ Intervals.icu ◀── phone pushes breathing streams
```

### 3.1 Phone app: **K-Breathe Run** (Android)

Kotlin, minSdk 26, targetSdk 34, matching the Karoo app. Ported from `TymewearKaroo` with the Karoo SDK layer removed:

| Ported as-is or lightly adapted | Left behind |
|---|---|
| `Protocol.kt` (UUIDs, packet parser; FIT field definitions dropped) | Everything depending on `karoo-ext` (data types, `TymewearExtension`, Glance views) |
| `BleManager.kt`, `BleStatus.kt`, `ScanThrottle.kt`, `BleDiagnostics.kt` | Beta ventilatory state: `VeBaseline`, `SessionScale`, `LoadGate`, `ThresholdEvidence`, `SessionPipeline`, `RideEndPrompt` (see section 6.5) |
| `DataWatchdog.kt`, `DataFreshness.kt` | Power inputs |
| `ZoneClassifier.kt`, `Constants.kt` (thresholds, zone colours, resting and max BR/HR defaults) | VE graph, time-in-zone chart, smoothing receivers |
| `TymewearData.kt` (rolling buffers, zone times, `recomputeMi`), trimmed of power | Live HR handling on the phone (HR is never live on the phone; see section 4.1) |
| `BleForegroundService.kt` as the basis for the recorder service | |

Responsibilities:

1. **Strap connection.** A foreground service keeps the strap connected whenever it is in range and the service is enabled, reconnecting on loss using the Karoo app's scan-throttle and watchdog logic. The strap is therefore already streaming when the run starts.
2. **Session recording.** See section 5.
3. **Relay server.** See section 4.
4. **Intervals.icu sync.** See section 6.
5. **Settings.** The only configuration the runner ever does. See section 7.

### 3.2 Zepp OS mini program: **K-Breathe** (watch + side service)

One Zepp OS project with `module.data-widget` (workout extension) and a side service.

- **Workout extension** (device, JS). One page, described in section 8. Lifecycle: `onInit`/`build` when the run starts with the extension configured, `onResume`/`onPause` as it gains and loses the screen, `onDestroy` when the run ends.
- **Side service** (phone, JS, inside the Zepp app). Polls the relay while the extension is visible, forwards values to the extension over the Zepp messaging channel, and forwards session start and stop events from the extension to the relay.

## 4. Live data flow (display)

1. The phone app keeps the latest breathing values, thresholds and status behind a loopback-only HTTP server: bind `127.0.0.1`, fixed port `41415`, no authentication (loopback only; nothing off-device can reach it).
2. The extension, on `onResume`, tells the side service to start polling. The side service calls `GET /live` once per second and forwards the response to the extension. On `onPause` it tells the side service to stop.
3. Breath packets arrive every 2 to 4 seconds, so a 1 s poll costs nothing in freshness.
4. The extension shows stale or disconnected states from the `status` field, never from its own timers (which are frozen when paused anyway).

Relay endpoints:

| Method, path | Purpose | Response |
|---|---|---|
| `GET /live` | Latest values | `{ ve, br, tv, ie, zone, batteryPct, status, thresholds: {vt1, vt2, topZ4, vo2max}, reserve: {...}, sessionId, updatedAtMs }` |
| `POST /session/start` | Watch started a run | `{ sessionId }`; idempotent if a session is already open |
| `POST /session/stop` | Watch ended a run | `{ sessionId }`; no-op if none is open |
| `GET /health` | Liveness for the side service | `{ ok: true, version }` |

`status` is one of `connected`, `stale` (no breath packet for longer than the freshness window), `disconnected`, `off` (service disabled).

`/live` also carries `reserve: { restingBr, maxBr, restingHr, maxHr }` so the watch can compute the mobilization index locally.

### 4.1 Heart rate and the mobilization index on the watch

The phone never sees live heart rate and never connects to a heart rate monitor. The Tymewear HR monitor (TymeHR) pairs with the watch as a normal external HR sensor, so the watch's heart rate is the strap's whenever it is worn and working, and the optical sensor's otherwise. The extension reads that value through the Zepp OS heart rate sensor API. The extension therefore computes the HR-dependent numbers itself, using the Karoo app's formula (`TymewearData.recomputeMi`):

```
%HRR = (hr - restingHr) / (maxHr - restingHr) * 100
%BRR = (br - restingBr) / (maxBr - restingBr) * 100, floored at 0
MI   = %BRR / %HRR * 100,  or 0 when %HRR < 1 or any input is missing
```

`br` is the smoothed breathing rate from `/live`; `hr` is read on each poll. Whether the heart rate sensor API is available inside a workout extension is a day-one check (section 11).

## 5. Session control and recording (zero touch)

### 5.1 Start and stop

- **Start:** the extension's `onInit` fires when the native run starts with the extension on a data page. It sends `start` to the side service, which calls `POST /session/start`. The phone opens a session and begins writing.
- **Stop:** the extension's `onDestroy` fires when the run ends. Same path with `stop`.
- **Fallback stop:** if no stop arrives, the session closes automatically after the strap has been disconnected for 10 minutes, or after 8 hours, whichever is first. This covers the Zepp app being killed mid-run.
- **Fallback start:** none automatic. A session without a watch signal is started by the button in the phone app. This is deliberate: the strap streams whenever worn, so "strap connected" is not a reliable run signal.
- **Manual override:** start and stop buttons in the phone app, for runs without the watch.

Whether `onInit` and `onDestroy` fire reliably at run start and end is documented loosely and is a day-one check (section 11).

### 5.2 What is recorded

Per session, one file under app storage, named by start time: a **raw log** (`<start>.jsonl`) with every parsed breath packet: wall-clock timestamp, `tvRaw`, inhale and exhale durations, packet timestamp and battery, plus session start and stop markers. This is the source of truth. No FIT file is written on the phone; there is nowhere it needs to go.

The **per-second series** (VE, BR, TV, I:E ratio, zone) is derived from the raw log when it is needed, at sync time. Derivation matches the Karoo app so numbers agree across devices: each breath packet updates the rolling buffers (`RollingBuffer`, capacity 8) and each second carries the current smoothed values; the zone comes from `ZoneClassifier.zoneFor(ve, thresholds)` with the thresholds configured at that moment. Because the series is derived, changing thresholds and re-syncing recomputes the zone stream with no data loss.

Sessions are kept for 90 days, then pruned.

The raw log contains no heart rate. The watch records HR (from the paired TymeHR, or its optical sensor as fallback) into its own activity, and HR-dependent streams are computed at sync time from the Intervals.icu heart rate stream (section 6.2).

## 6. Intervals.icu sync (the merge)

Zero touch. The phone waits for the watch's run to appear and attaches the breathing data to it.

### 6.1 One-time setup (by the runner, once)

1. Intervals.icu Supporter subscription (required for stream upload).
2. API key from Intervals.icu settings, entered in the phone app.
3. Custom activity streams created in Intervals.icu (Charts, Custom Streams, Add Stream) with these exact codes and units. The phone app shows this list with a copy button and checks on first sync that each exists:

   | Code | Units | Name |
   |---|---|---|
   | `tyme_minute_volume` | L/min | VE |
   | `tyme_breath_rate` | brpm | Breathing rate |
   | `tyme_tidal_volume` | L | Tidal volume |
   | `tyme_inhale_exhale_ratio` | ratio | I:E ratio |
   | `tyme_ve_zone` | zone | VE zone |
   | `tyme_percent_brr` | % | Breathing reserve used |
   | `tyme_mobilization_index` | % | Mobilization index |

   The codes match the developer field names Tymewear uses in FIT files, so if any other Tymewear-recorded activity lands in Intervals.icu with those record fields, it shares the same streams and charts. That is a convenience, not a requirement.

4. Zepp app linked to Intervals.icu (Zepp app: Profile, third-party account linking).

### 6.2 Flow after a session closes

1. **Poll.** Every 2 minutes for up to 6 hours: `GET /api/v1/athlete/0/activities?oldest=<sessionStart-2h>&newest=<sessionEnd+2h>`. Candidate: an activity whose `start_date` is within 5 minutes of the session start and whose `source` or `device_name` indicates Amazfit/Zepp. Ties resolve to the closest start.
2. **Fetch time axis and heart rate.** `GET /api/v1/activity/{id}/streams.json?types=time,heartrate`. The `time` stream gives seconds since activity start for each sample. Intervals.icu keeps its own row count; we never change it.
3. **Align.** For each activity sample time `t`, look up the breathing values at `activityStart + t` from the session's per-second series. Outside the session or during a strap dropout the value is `null`. This uses the existing row count, so only the streams we send are touched (Intervals.icu rule: same row count means only supplied columns update).
4. **Derive HR-dependent streams.** With the activity's `heartrate` sample at each `t` and the configured resting and max BR and HR, compute `%BRR` and `MI` per sample using the formula in section 4.1. `null` wherever HR or BR is missing.
5. **Push.** `PUT /api/v1/activity/{id}/streams` with one `ActivityStream` per code: `{ type: <code>, custom: true, data: [ ... ] }`. Check `UpdateStreamsResult.updated` names all seven. Record the activity id and result against the session.
6. **Notify.** Android notification: "Run synced to Intervals.icu", tapping opens the activity in the browser. On failure: "Sync failed, tap to retry", with the error.
7. **Clock skew guard.** Before aligning, compare the activity's `start_date` with the session start. If the offset exceeds 5 minutes, refuse to auto-merge and notify, rather than misalign silently. A manual pick in the phone app can override.

### 6.3 Idempotence and re-runs

- A session records which activity it was pushed to and when. Re-running the sync (button in the session list) recomputes and re-pushes; Intervals.icu overwrites the streams.
- If thresholds or reserve settings change, `tyme_ve_zone`, `tyme_percent_brr` and `tyme_mobilization_index` can be recomputed from the raw log plus the activity's heart rate and re-pushed for any kept session.
- Sessions with no matching activity after 6 hours are marked "unmatched" and left for manual matching. They are not deleted.

### 6.4 What is deliberately not done

- No upload of the phone's FIT to Intervals.icu (would create a duplicate activity).
- No deletion of anything in Intervals.icu.
- No Tymewear dashboard upload (no public API), and no FIT file on the phone at all. Intervals.icu is the single destination.
- No session-level custom activity fields in the first version. Time-in-zone can be derived in Intervals.icu from the `tyme_ve_zone` stream with a custom field script if wanted.

### 6.5 Beta ventilatory state: deferred, not excluded

The Karoo app's Beta (strap scale and day quality) learns a baseline of VE against heart rate and power from steady riding, then locks today's strap scale within the first part of a ride and re-colours zones live. It needs both power and heart rate, and its value is the live in-ride correction.

For running the inputs differ: HR is available (live on the watch, post hoc in Intervals.icu) but power is only the watch's running-power estimate, and the phone has neither live. A live version would need the watch to stream HR back to the phone and the phone to run the sampler during the run, which the paused-extension constraint makes unreliable. A post-hoc version at sync time is feasible from the raw log plus Intervals.icu HR (and running power if present), and could push `tyme_ve_scale` and `tyme_day_quality` as custom activity fields. That is a follow-on once the core pipeline is proven on real runs; the raw log and derived-at-sync design keep the door open.

## 7. Settings (phone app)

Everything configurable lives here. There is nothing to set on the watch.

- **Zone thresholds** (L/min): VT1, VT2, Top Z4, VO2max. Defaults from the Karoo app (73, 96, 112, 130) as placeholders only, with the same warning that they must be replaced by the runner's own values from a Tymewear threshold test.
- **Reserve settings** for the mobilization index: resting BR, max BR, resting HR, max HR. Defaults from the Karoo app (12, 55, 60, 190 as placeholders).
- **Strap:** optional 4-digit sensor ID filter (as on the Karoo), service enabled switch.
- **Intervals.icu:** API key, connection test, custom-stream check.
- **Behaviour:** fallback stop timeout (default 10 minutes), session retention (default 90 days).

Thresholds and reserve settings are included in every `/live` response, so the watch colours and computes MI with the current values immediately.

## 8. Watch page (workout extension)

Round 480x480, one page, no scrolling. Content:

- **VE** large, centre, with the zone colour as the background band (the Karoo zone palette: teal, blue, amber, orange, red).
- **BR**, **TV** and **MI %** in a row below, smaller. MI shows `--` when heart rate is unavailable.
- **Zone name** small under the VE figure (Endurance, VT1, VT2, Top Z4, VO2Max).
- **Status marker** in the top edge: green when connected, amber when stale, grey with a short label when the strap is disconnected or the phone is unreachable. The whole background turns grey in the last case so a glance is enough.

The extension holds no thresholds or state of its own beyond the last received payload and the latest heart rate reading. It never does BLE.

## 9. Error handling

| Situation | Behaviour |
|---|---|
| Strap disconnects mid-run | Phone reconnects (existing Karoo logic). Records carry `null` breathing values during the gap. Watch shows disconnected within the freshness window. |
| Zepp app killed by Android | Display stops until the runner reopens the Zepp app or Android restores it. Recording continues on the phone. Fallback stop closes the session. Setup guidance tells the runner to exclude the Zepp app and K-Breathe Run from battery optimisation. |
| Phone app killed | Foreground service restarts (`START_STICKY`); a session file that was open is closed on restart with the last written second. |
| Relay unreachable from side service | Extension shows "phone?" state. Side service retries each second. |
| Run started before phone app is running | Start event is lost. The runner sees the "phone?" state and opens the phone app, which then auto-starts a session on its next `/session/start` or the manual button. |
| Intervals.icu activity never appears | Session marked unmatched after 6 hours, notification, manual match available. |
| Intervals.icu rejects a stream (missing custom stream) | Notification naming the missing codes. |
| Two sessions overlap one activity | Closest start wins; the other is marked unmatched. |

## 10. Testing

**Phone app (JVM unit tests, TDD):**
- Parser and zone tests carried over from the Karoo app.
- Per-second derivation: from a raw log, the derived VE, BR, TV and zone series match the Karoo app's recorded values for the same packets (fixtures: existing ride CSVs in `TymewearKaroo/app/src/test/resources/fixtures`).
- Alignment: given an Intervals.icu `time` stream and a session series, produces the right `data` arrays, including nulls in gaps, offsets and skew rejection.
- MI derivation: given aligned BR and an Intervals.icu `heartrate` stream, `%BRR` and `MI` match the Karoo formula, with nulls where either input is missing and the `%HRR < 1` guard applied.
- Intervals.icu client against recorded responses from the live API (captured once with the runner's key, secrets stripped).
- Session state machine: start, stop, fallback stop, restart recovery.
- Relay: endpoint contract tests over loopback.

**Watch program:**
- Developed against the Zepp OS simulator with a mock side service that replays a recorded `/live` sequence.
- Layout checked on the 480x480 round target.
- MI computation in the extension unit-tested against the same cases as the phone-side derivation, so the live figure and the synced stream agree.

**Integration (needs the watch):** section 11.

## 11. Day-one checklist with the real watch

Each item is a go/no-go for the corresponding part of the design.

1. Zepp developer mode works on the Cheetah 2 Ultra and a sideloaded mini program installs.
2. The workout extension appears in the Run app's data page configuration.
3. `onInit` fires at run start and `onDestroy` at run end (log via side service).
4. Side service can fetch `http://127.0.0.1:41415/health` while the phone app runs.
4a. The heart rate sensor API returns live values inside the workout extension, and when the TymeHR is paired those values are the strap's, not the optical sensor's.
5. A full run: watch shows live values, phone records without gaps, session start and stop arrive.
6. The Zepp app survives the run in the background with battery optimisation disabled.
7. Intervals.icu receives the run, and the streams push succeeds and shows in a custom chart.

If item 4a fails, MI is dropped from the watch page and remains only in the synced streams. If item 3 fails, start and stop move to the extension's `onResume` (first visibility) and the fallback stop. If item 4 fails, the relay moves to the phone's Wi-Fi hotspot address as a fallback, or the side service is replaced by direct fetch from the extension if the API allows it.

## 12. Risks

- **Zepp app background survival on Android.** Mitigation: battery optimisation guidance, and recording never depends on it.
- **Workout extension support on Cheetah 2 Ultra** is expected from the API level but not officially listed.
- **Extension lifecycle timing** for start and stop is loosely documented. Mitigation: fallback stop, idempotent start.
- **Intervals.icu Supporter requirement** for stream upload.
- **Clock alignment** between phone and Intervals.icu activity start. Mitigation: skew guard, manual match.

## 13. Out of scope for the first version

Beta ventilatory state (deferred, section 6.5), VE graph, time-in-zone chart, power inputs, live heart rate on the phone, FIT file output of any kind, Tymewear dashboard upload, session-level custom fields in Intervals.icu, iOS, other Zepp OS watches (though nothing prevents them).

## 14. Repository layout

```
TymewearAmazfit/
  docs/superpowers/specs/   this document, later the plan
  phone/                    Android app, K-Breathe Run
  watch/                    Zepp OS mini program (extension + side service)
  README.md
```
