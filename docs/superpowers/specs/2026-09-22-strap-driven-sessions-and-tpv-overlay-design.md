# Strap-driven sessions and a TrainingPeaks Virtual overlay

**Date:** 2026-09-22
**Status:** Approved in discussion, awaiting spec review
**Extends:** `2026-09-03-tymewear-amazfit-design.md` (K-Breathe Run phone app and K-Breathe watch extension)
**Related:** `TymewearKaroo` (writes the same Tymewear stream codes into the Karoo FIT)

## 1. Goal

Record Tymewear VitalPro breathing data and merge it into the matching Intervals.icu activity for **any** activity the runner records elsewhere, in particular:

1. Runs recorded on the Amazfit watch (existing target, unproven on hardware).
2. Indoor rides on **TrainingPeaks Virtual (TPV)** on a Windows PC. TPV uploads its rides to Intervals.icu through its own integration, so the ride is already there to merge into.

Additionally, while riding in TPV, show the live breathing values as an **always-on-top overlay** on the Windows screen.

Nothing in this design requires the watch to signal start or stop, and nothing requires the PC to trigger recording. The strap being worn and streaming is the trigger.

## 2. Facts this design rests on

Established 2026-09-22.

| Fact | Consequence |
|---|---|
| TPV has an Intervals.icu integration; rides appear automatically with a non-Strava source. | The existing `PUT /api/v1/activity/{id}/streams` merge works on TPV rides. |
| Intervals.icu refuses stream edits on Strava-sourced activities. | The Strava exclusion in the matcher stays. |
| TPV on Windows saves FIT files to `C:\Users\<you>\TPVirtual\<USERID>\FitFiles` at ride end. | Not used by this design; recorded as the hook for a future PC-side recorder. |
| The Tymewear stream codes (`TymeVentilation` etc.) already match the Karoo app's developer field names, and Karoo rides show in the Tymewear dashboard via its Intervals.icu integration. | No change to codes. TPV rides and Amazfit runs share the Karoo charts. |
| The VitalPro accepts one BLE central at a time. | Phone records; the PC never connects to the strap. |
| The phone app never sees live heart rate. | No live mobilization index (MI) on the overlay. MI still arrives in Intervals.icu at sync. |

## 3. Architecture

Unchanged shape: the phone is the hub. Two additions, marked `+`.

```
 VitalPro strap ──BLE──▶ Phone app (Android, Kotlin)
                            ├─ strap-driven session recording          (+ changed rule)
                            ├─ loopback relay 127.0.0.1:41415 (watch)  (unchanged)
                            ├─ LAN relay <wifi-ip>:41415, token        (+ new listener)
                            └─ Intervals.icu client: overlap match, push streams (+ changed matcher)

 Windows PC:  overlay.ps1 ── polls http://<phone>:41415/live?token=… ──▶ topmost WPF window  (+ new)
 TPV ──own integration──▶ Intervals.icu ◀── phone pushes breathing streams
```

The watch extension is not touched.

## 4. Strap-driven sessions (phone)

### 4.1 Rule

`SessionController` gains a third source of start and stop besides the watch and the manual buttons:

- **Open** when a breath packet arrives and no session is open. `source = "strap"`.
- **Close** when no breath packet has arrived for the **idle timeout** (setting, default 3 minutes), or the 8 hour cap is reached.

The existing stop rule "strap disconnected for `fallbackStopMinutes`" is replaced by the idle timeout, because a disconnected strap produces no packets and the idle rule covers it. `fallbackStopMinutes` becomes `idleStopMinutes` in `Settings`, default 3, and the settings screen label changes to "Stop session after no breathing data for". The 10 minute default from the original design was chosen to survive a mid-run strap dropout while the watch drove start and stop; with strap-driven sessions a dropout longer than 3 minutes simply produces two sessions, and overlap matching (section 5) merges both into the same activity, so the shorter timeout costs nothing.

Watch `POST /session/start` and the manual Start button still open a session if none is open and are otherwise no-ops, as today. Watch `POST /session/stop` and the manual Stop button still close the open session. A session closed by the watch or the button reopens on the next breath packet, so the runner can stop a session by removing the strap, not only by pressing Stop.

Sessions shorter than 60 seconds are still marked `skipped`. Sessions that never match an activity are marked `unmatched` after 6 hours and pruned with the normal 90 day retention. These are the cost of strap-driven start and are accepted.

### 4.2 Interaction with strap presence

The Companion Device presence logic (`StrapPresenceService`) already keeps the recorder service running while a session is open, so a strap-driven session is never cut by presence detection. No change.

### 4.3 Recording

Unchanged: raw per-breath log per session, series derived at sync time.

## 5. Overlap matching (phone)

### 5.1 Rule

`ActivityMatcher.pick` changes from "start within 5 minutes" to **largest time overlap**:

```
sessionSpan  = [session.startMs, session.endMs]
activitySpan = [activity.startDate, activity.startDate + activity.elapsedTimeS]
overlap      = max(0, min(ends) - max(starts))
```

- Exclude activities with `source == "STRAVA"` (case-insensitive), as today.
- Exclude activities with `overlap < MIN_OVERLAP_MS` (5 minutes).
- Pick the largest overlap. Ties (equal to the millisecond) resolve to the earliest start.
- The Amazfit preference is removed. TPV rides, Karoo rides and Amazfit runs are all valid targets.

`ActivitySummary` gains `elapsedTimeS: Int?` from the Intervals.icu `elapsed_time` field. An activity with no `elapsed_time` is treated as zero length and therefore never matches automatically; it remains reachable through "Match by id".

### 5.2 Two activities for one session

When a watch recording and a TPV recording both overlap the same session, the larger overlap wins and the session's `syncMessage` names the runner-up, for example `pushed 7 streams to i12345; also overlapped i12346 (Amazfit)`. The runner can push the same session to the other activity with "Match by id"; the session then records the latest activity id, as today. Pushing to both automatically is deliberately not done.

### 5.3 Search window and alignment

`SyncEngine.sync` already lists activities over a ±24 hour window and `StreamAligner` already emits `null` outside the session, so a session that starts before or ends after the activity aligns correctly. The clock skew guard from the original design (refuse when session and activity start differ by more than 5 minutes) is **removed**; overlap matching makes it meaningless, and a wrong clock now shows up as a poor or missing overlap rather than a silent misalignment. `Constants.MATCH_WINDOW_MS` is replaced by `Constants.MIN_OVERLAP_MS`.

## 6. Relay on the LAN (phone)

### 6.1 Listeners

`RelayServer` currently binds `127.0.0.1:41415`. It is split so that the same request handler can be served from two `NanoHTTPD` instances:

| Listener | Bind | Auth | On when |
|---|---|---|---|
| Loopback | `127.0.0.1:41415` | none | always (watch side service) |
| LAN | Wi-Fi interface address, port `41415` | `token` query parameter or `Authorization: Bearer` | setting **LAN overlay** is on |

The LAN listener binds the current Wi-Fi IPv4 address, not `0.0.0.0`, so it is never exposed on mobile data. It is restarted when the Wi-Fi address changes (network callback) and stopped when Wi-Fi drops. Requests without a valid token get `401 {"error":"unauthorized"}`. `POST /session/start` and `/session/stop` are **not** served on the LAN listener (`404`); the overlay is display-only and nothing on the network may start or stop recording.

### 6.2 Token and discovery

A 16 byte random token, base64url, generated once and stored in settings; a "Regenerate" button invalidates the old one. The Status tab shows, while the LAN listener is up:

- the overlay URL as text with a Copy button: `http://192.168.1.23:41415/overlay?token=<token>`
- the same URL as a QR code (a small pure-Kotlin QR encoder or `zxing-core`; decide at implementation time by dependency size).

No mDNS. The phone's address is expected to be stable on a home network, and the overlay script keeps the last URL.

### 6.3 New endpoint

`GET /overlay` serves a self-contained HTML page (inline CSS and JS, no external resources) that polls `/live` once a second with the same token and renders the same layout as the Windows overlay. This is the fallback when PowerShell is not wanted: open it in any browser on any device on the Wi-Fi, including a tablet propped next to the trainer. `LivePayload` is unchanged.

## 7. Windows overlay

### 7.1 Form

`pc/overlay.ps1`: one PowerShell 5.1 script, no installer, no dependencies beyond Windows (`PresentationFramework` via `Add-Type`). A `pc/overlay.cmd` launcher starts it with `-WindowStyle Hidden`. `pc/README.md` explains setup in five steps.

### 7.2 Behaviour

- Window: borderless, `Topmost`, `AllowsTransparency` with a dark background at about 70 % opacity, no taskbar entry, draggable, remembers its position. Default size roughly 260x140 device-independent pixels, placed top-right.
- Polls `GET /live?token=…` every second with a 900 ms timeout on a background timer, never blocking the UI thread.
- Shows: **VE** large with the zone colour as the panel background (Karoo palette: teal, blue, amber, orange, red), **zone name** under it, **BR** and **TV** in a small row, and a **status** dot: green `connected`, amber `stale`, grey `disconnected` / `off`, red with the text `phone?` when the poll fails or times out. After 5 consecutive failures the panel greys out entirely.
- Right-click menu: Set phone URL, Opacity (50/70/90), Quit.
- Config in `%APPDATA%\KBreathe\overlay.json`: `url`, `x`, `y`, `opacity`. On first run with no config it prompts for the URL (paste from the phone's Copy button).
- No MI. The panel shows nothing HR-dependent.

### 7.3 Day-one check on the PC

TPV must run **borderless windowed** or windowed, not exclusive fullscreen, for a topmost window to stay visible. If TPV only offers exclusive fullscreen on this PC, the fallback is a second monitor or `GET /overlay` on a phone or tablet. This is the first thing to test before polishing the script.

## 8. Settings changes (phone)

| Setting | Change |
|---|---|
| `fallbackStopMinutes` | renamed `idleStopMinutes`, default 3, label "Stop session after no breathing data for" |
| `lanOverlayEnabled` | new, default off |
| `lanToken` | new, generated on first enable |

Everything else unchanged.

## 9. Error handling

| Situation | Behaviour |
|---|---|
| Strap dropout longer than the idle timeout mid-ride | Two sessions; both match the same activity by overlap and both push. The second push overwrites only the samples it has values for? **No**: each push sends full-length arrays with `null` outside its session, so the second push would blank the first. Therefore `SyncEngine` merges: before pushing, if another `synced` session already targets the same activity id, its series is included in the alignment so the pushed arrays carry both. |
| Strap worn without any activity | Session becomes `unmatched` after 6 hours, then pruned. No notification for unmatched strap-driven sessions shorter than 15 minutes, to avoid noise from fitting the strap. Longer ones notify as today. |
| Wi-Fi address changes mid-ride | LAN listener rebinds; the overlay's URL is stale. The overlay shows `phone?`; the runner re-copies the URL. Accepted for the first version. |
| Token leaked on the home network | Reader sees breathing values only. Regenerate from the Status tab. |
| Overlay hidden by exclusive fullscreen TPV | Section 7.3 fallback. |
| Two activities overlap one session | Section 5.2. |

The merge-on-second-push rule in the first row is the one non-obvious piece of logic; it gets its own tests.

## 10. Testing

**Phone JVM tests (TDD):**
- `SessionController`: opens on first breath when idle; does not reopen while open; closes after idle timeout; closes at 8 hour cap; watch and manual start/stop remain no-ops or closes as specified; a session stopped by the button reopens on the next breath.
- `ActivityMatcher`: activity fully inside session; session fully inside activity; partial overlap each side; below minimum overlap; Strava excluded; two candidates with different overlap; tie on overlap; missing `elapsed_time`.
- `SyncEngine`: second session for an already-synced activity merges the earlier series into the pushed arrays; `syncMessage` names a runner-up activity.
- `RelayServer`: loopback serves without token; LAN listener rejects missing and wrong token with 401; LAN listener returns 404 for `POST /session/*`; `GET /overlay` returns HTML.
- `IntervalsClient`: parses `elapsed_time` from the recorded activity list fixture.

**Windows overlay:** manual checklist in `pc/README.md`: first-run URL prompt, values update within 2 seconds of the phone, zone colour changes, `phone?` state when the phone's Wi-Fi is switched off, position and opacity persist across restart, stays above TPV in borderless mode.

**End to end:** one TPV ride wearing the strap with the phone on the same Wi-Fi. Pass looks like: overlay shows live values throughout; the phone's Sessions tab shows one session `synced` to the TPV activity within a few minutes of the ride ending; the seven `Tyme*` streams chart on that activity in Intervals.icu; the ride appears in the Tymewear dashboard with breathing data.

## 11. Out of scope

- PC-side recording of the strap and the FitFiles folder watcher (possible follow-on, section 2 records the hook).
- Any change to the watch extension.
- Live MI on the overlay.
- mDNS discovery of the phone.
- Pushing one session to two activities automatically.
- Tymewear dashboard upload beyond what Intervals.icu already provides.

## 12. Repository layout

```
TymewearAmazfit/
  docs/superpowers/specs/   this document
  phone/                    Android app (changes in sections 4 to 6, 8)
  watch/                    unchanged
  pc/                       overlay.ps1, overlay.cmd, README.md   (new)
```
