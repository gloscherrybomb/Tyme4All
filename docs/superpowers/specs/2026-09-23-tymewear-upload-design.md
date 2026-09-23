# Breathing to Tymewear, and thresholds from Tymewear

**Date:** 2026-09-23
**Status:** Implemented in v0.2.0
**Extends:** `2026-09-22-strap-driven-sessions-and-tpv-overlay-design.md`
**Release:** v0.2.0

## 1. Goal

1. After Tyme4All adds breathing to an Intervals.icu activity, the same breathing reaches the athlete's Tymewear dashboard, so Tymewear analyses the session like one it recorded itself.
2. Tyme4All uses the athlete's own ventilatory thresholds and resting/max values from Tymewear instead of the placeholder defaults, per sport.

Both are optional and need a Tymewear sign-in in the app.

## 2. Facts this design rests on

Established 2026-09-23 against the athlete's own accounts.

| Fact | Consequence |
|---|---|
| Tymewear's Intervals.icu integration copies each new activity within seconds of it reaching Intervals.icu, using the original file. | Tymewear already has the activity, without breathing, by the time Tyme4All pushes streams. |
| Streams added to an Intervals.icu activity afterwards never reach Tymewear. A test watched for 15 minutes: Tymewear's copy did not change. The integration reports `can_sync_historical_data: false`, so there is no backfill. | Pushing to Intervals.icu alone never gets breathing into Tymewear. |
| The dashboard's per-activity "upload file" call, `PATCH /api/activities/third-party/{tpId}/` (multipart field `fit_file`), replaces the file behind an activity Tymewear pulled from a partner. Tymewear re-ran its analysis within 30 seconds of a test upload and the VE channel filled. | The upload is the way in. It replaces the whole file. |
| Tymewear reads breathing from FIT developer fields named `tyme_breath_rate`, `tyme_tidal_volume`, `tyme_minute_volume` (float32), the names K-Breathe writes on the Karoo. The Karoo rides with them show breathing in Tymewear. | The merged file uses those names. |
| `GET /api/v1/activity/{id}/file` on Intervals.icu returns the original upload, gzipped (checked for a Zepp run and a TPV ride; both FIT). | The merge starts from the original file, so power, HR, GPS, laps and device information survive. |
| Tymewear's API: `POST /api/session/signin/` `{username, password}` returns `{token}`; requests carry `Authorization: Token <token>` and `X-Source: v2`; `POST /api/session/refresh/` swaps a still-valid token. The profile (`GET /v2/api/profile/`) carries the user `id`. `GET /v2/api/users/{id}/thresholds/active/?sport_type=running|bike` carries the Fitness Profile's markers (Endurance, VT1, VT2, Top Z4, VO2max), each with its VE; the profile's `*_ve_target_*` fields are different numbers and are not the zones; `GET /v2/api/resting-max-values/` carries `br_rest`, `hr_rest`, `br_max`, `hr_max`. | Sign-in and thresholds need no other calls. |
| Tymewear's activity list (`GET /v2/api/activities-cursor/?user=<profile id>&limit=N`, newest first) and detail (`GET /v2/api/activities/{id}/`) show, for a pulled activity, `third_party_activities[]` with `id` (the `tpId`), `partner: "INTERVALS_ICU"` and `start_date` equal to the Intervals.icu activity's `start_date`. | Tymewear's copy is found by partner and start time. |
| Tymewear's API has no scoped tokens and is undocumented. | The password must be kept to sign in again unattended (athlete's choice, 2026-09-23). Every call is written down here; nothing else is called. |

## 3. Decisions the athlete made

- **Credentials:** keep the email and password on the phone in Android's encrypted storage, so the app can sign in again on its own.
- **Thresholds:** fill them from Tymewear, with one switch to use manual values instead.
- **Live zones:** the live view (Wi-Fi page, PC overlay, watch page) uses bike thresholds. Zones added afterwards use the activity's own sport.

## 4. Design

### 4.1 Tymewear client (`domain/tymewear/`)

`TymewearApi` interface and an OkHttp `TymewearClient`, like `IntervalsApi`/`IntervalsClient`.

| Call | Use |
|---|---|
| `signIn(email, password): String` | token |
| `refresh(token): String?` | fresh token, or null |
| `profile(): TymewearProfile` | the user id |
| `activeThresholds(userId, sport): ZoneThresholds?` | the five VE markers for `running` or `bike` |
| `restingMax(): RestingMax?` | resting and max BR and HR |
| `recentActivities(limit): List<TymewearActivity>` | id, start, `third_party_activities` (id, partner, start) |
| `activity(id): TymewearActivity` | the same fields for one activity, when the list omits them |
| `replaceFile(tpId, fitBytes)` | the upload |

Nothing else. No delete, tag, pin, re-run or accept call exists in the client.

Authentication: a cached token is used first. On a 401 the client refreshes once, then signs in once with the stored credentials, then gives up with `TymewearAuthException`. The email, password and token never appear in a log line, an exception message or the session log.

Credentials live in `EncryptedSharedPreferences` (`androidx.security:security-crypto`), apart from the ordinary settings store. Signing out deletes them and the token.

### 4.2 The file merge (`domain/fit/BreathingFitMerger`)

Pure JVM, using the Garmin FIT SDK (`com.garmin:fit`).

```
merge(original: ByteArray, activityStart: Instant, time: List<Double?>, streams: List<Stream>): ByteArray
```

- Decodes every message of the original file in order.
- Adds one `developer_data_id` message (Tyme4All's own 16-byte application id) and one `field_description` per field before the first record:

  | Number | Name | Units | From stream |
  |---|---|---|---|
  | 0 | `tyme_breath_rate` | brpm | `TymeBreathRate` |
  | 1 | `tyme_tidal_volume` | vol/br | `TymeTidalVolume` |
  | 2 | `tyme_minute_volume` | vol/min | `TymeVentilation` |
  | 3 | `tyme_inhale_exhale_ratio` | sec/sec | `TymeIERatio` |

- For each `record` message, looks up the stream value at that record's timestamp (the same whole-second alignment `StreamAligner` already produced for Intervals.icu), and adds the developer fields that are non-null.
- Leaves every other message and every existing field as it was, and writes a valid FIT (header, CRC).
- Throws `NotFitException` if the input is not a FIT file.

The streams passed in are the ones `StreamAligner` produced for the Intervals.icu push, so Intervals.icu and Tymewear get the same numbers.

### 4.3 The second sync step

`SyncEngine` keeps its Intervals.icu push unchanged. When that push succeeds and Tymewear upload is on, a second step runs, `TymewearUploader.upload(activity, aligned)`:

1. Skip, with a reason, if the activity is a Karoo ride or the original file is not a FIT.
2. Find Tymewear's copy: the newest 30 activities, each with a `third_party_activities` entry whose `partner` is `INTERVALS_ICU` and whose `start_date` is within 60 s of the Intervals.icu activity's start. None found means "not in Tymewear yet".
3. Download the original file from Intervals.icu (new `IntervalsApi.originalFile(id): ByteArray`).
4. Merge (4.2) and `replaceFile(tpId, merged)`.

Each session gets its own Tymewear fields in `SessionMeta`: `tymewearState` (`pending`, `synced`, `failed`, `skipped`), `tymewearMessage`, `tymewearAttemptMs`. The existing scheduler already retries pending sessions every 2 minutes for up to 6 hours; a session whose Intervals.icu push is done but whose Tymewear step is `pending` stays in that loop, and the retry repeats only the Tymewear step.

Outcomes:

| Situation | Tymewear state |
|---|---|
| Upload accepted | `synced` |
| Tymewear copy not found yet, or network error | `pending` (retried) |
| Karoo ride, non-FIT original, upload switched off | `skipped` |
| Auth fails after refresh and re-sign-in | `pending` ("Tymewear stopped accepting the sign-in"), no more Tymewear calls until the next sign-in, and a "Sign in to Tymewear again" notification |
| Tymewear rejects the upload (4xx) | `failed`, with the reason |
| Still `pending` after 6 hours | `failed`, "Tymewear never showed the activity" |

A Tymewear failure never changes the Intervals.icu result. The file on Intervals.icu is never modified.

Retry on the Sessions tab repeats both steps for a session not yet synced to Intervals.icu. For a session already synced whose Tymewear step is `pending` or `failed`, it repeats only the Tymewear step; while Tymewear is off, it repeats only the Intervals.icu push and says why. A second upload replaces Tymewear's file again, which is harmless.

A refused sign-in stops every Tymewear call until the user signs in again. An upload refused for the sign-in stays `pending`, so it goes ahead after the next sign-in, within the same 6 hours. The 6-hour give-up runs whether or not Tymewear is on.

### 4.4 Thresholds and reserve from Tymewear

`Settings` gains:

- `useTymewearThresholds: Boolean` (default true once signed in)
- `bikeThresholds: ZoneThresholds?`, `runThresholds: ZoneThresholds?`, `tymewearReserve: ReserveSettings?`, the values last read from Tymewear, and when.

They are read when the app launches and right after sign-in, and kept if a read fails. A threshold Tymewear leaves empty falls back to the manual value. Tyme4All uses Tymewear's names (4.6), so the markers are stored as they come.

Which thresholds apply:

| Use | Switch on | Switch off |
|---|---|---|
| Live view (relay, overlay, watch page) | bike | manual |
| Zones added to a run, trail run, virtual run, walk or hike | run | manual |
| Zones added to any other activity | bike | manual |
| Reserve (MI and %BRR) | Tymewear's resting/max values | manual |

`SeriesBuilder` already takes thresholds as a parameter; `SyncEngine` chooses them per activity from the activity's `type`.

VE is in Tymewear's own units, per person and per sport, so Tymewear's thresholds match Tymewear's VE.

### 4.5 Screens and notifications

Settings tab, a new **Tymewear** card:

- Not signed in: email, password, **Sign in**. A short line: what it is for, and that the password is kept encrypted on the phone.
- Signed in: "Signed in to Tymewear", the thresholds now in use ("Bike VT1 68.6 · VT2 111.2 · Run VT1 83.4 · VT2 125.3"), and **Sign out**.
- Switches: **Also send breathing to Tymewear** (on) and **Use thresholds from Tymewear** (on). When the second is on, the manual threshold and reserve fields are shown but disabled.

Sessions tab: each session shows its Tymewear state beside the Intervals.icu one, and why a `pending` step waits while Tymewear is off. Retry is described in 4.3.

Notification after a sync: "Breathing added to intervals.icu and Tymewear", or "…to intervals.icu. Tymewear: <reason>" when that step failed or was skipped.

### 4.6 Tymewear's names throughout

The athlete's ruling, 2026-09-23: everything matches Tymewear. Tyme4All inherited the Karoo app's names. There, fields called VT1, VT2, Top Z4 and VO2max are really the edges Tymewear calls Endurance, VT1, VT2 and Top Z4, and zones are labelled by threshold name. From v0.2.0:

- Thresholds are Endurance, VT1, VT2, Top Z4 and VO2max, as on Tymewear's Fitness Profile. The first four are zone edges. VO2max is the top of Z5.
- Zones are Z1 to Z5: Z1 is below Endurance, Z2 is Endurance to VT1, Z3 is VT1 to VT2, Z4 is VT2 to Top Z4, and Z5 is Top Z4 and above.
- This covers the code, the Settings labels, the live JSON (`thresholds: {endurance, vt1, vt2, topZ4, vo2max}`), the Wi-Fi overlay, the PC overlay, the watch page and the docs.
- Settings saved by v0.1.0 move one name down on first load (old VT1 becomes Endurance, and so on), with VO2max taking its default. The zone edges themselves don't change.
- The `TymeVeZone` stream keeps its values 1 to 5.

## Changes after approval

- Thresholds and resting/max values are read when the app launches and right after sign-in, not on each sync.
- A refused sign-in stops all Tymewear calls until the user signs in again. Uploads refused for the sign-in stay `pending` and go ahead after that sign-in, within 6 hours.
- Tymewear's names throughout (4.6).
- Retry of a session whose only failure is the Tymewear step repeats only the Tymewear step.

## 5. Testing

- `BreathingFitMerger`: build a FIT with the SDK (records with HR, power, distance, a lap, a session, a device message), merge known streams, and decode the result. Check that:
  - every original message and field is still there;
  - the four developer fields are on the right records and absent where the stream is null;
  - the file passes the SDK's integrity check.
  - Also SDK-built files shaped like a Zepp run and a TPV ride. The repo is public, so real activity files are checked locally only and never committed.
- `TymewearClient` against `MockWebServer`:
  - sign-in body, headers, refresh, then re-sign-in on a 401;
  - no secret in any exception message;
  - the upload is a multipart `PATCH` with `fit_file`.
- `TymewearUploader` with fake APIs: found, not yet, Karoo skip, non-FIT skip, auth failure, 4xx, 6-hour give-up.
- `SyncEngine`: the Intervals.icu result is unchanged by any Tymewear outcome; a retry repeats only the Tymewear step once Intervals.icu is done.
- Threshold choice per activity type, and the fallback to manual values.
- End to end: the athlete's next strap session. Tymewear's copy must show breathing.

## 6. Release v0.2.0

- `versionCode = 2`, `versionName = "0.2.0"`.
- The README gets a Tymewear section: what it does, that the password stays encrypted on the phone, what is uploaded, and that Tymewear's API is undocumented and may change.
- The phone README gets a Tymewear setup step.
- A signed release APK (`./gradlew assembleRelease`, key from `~/.gradle/gradle.properties`) attached to a GitHub release `v0.2.0` with release notes. The release is published only after the athlete says so.

## 7. Out of scope

- Uploading sessions that match no Intervals.icu activity straight to Tymewear.
- Karoo rides (they carry breathing already).
- Anything Tymewear-side beyond the upload: tags, threshold acceptance, algorithm re-runs.
- Live heart rate on the phone.
