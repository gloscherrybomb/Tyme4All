# K-Breathe Run

Android phone app that records breathing data from a Tymewear VitalPro strap during a run and merges it into the matching Intervals.icu activity. It is the phone half of the K-Breathe project; the watch half (a Zepp OS workout extension for the Amazfit Cheetah 2 Ultra) does not exist yet — see `../watch/` (planned) and the design spec at `../docs/superpowers/specs/2026-09-03-tymewear-amazfit-design.md`.

## What it does

- Keeps the Tymewear VitalPro strap connected in the background and records every breath packet to a per-session raw log.
- Serves the latest breathing values over a loopback-only HTTP relay (`127.0.0.1:41415`) so a watch extension (or anything else on-device) can display them live.
- Starts and stops sessions from the relay (driven by the watch), from fallback timers, or from manual buttons.
- After a session ends, waits for the matching Amazfit/Zepp activity to appear on Intervals.icu, derives seven breathing streams from the raw log, and pushes them onto that activity.
- Notifies on sync success, sync failure, or no match found, and lets you retry or match by hand.

It does not produce a FIT file, does not connect to a heart rate sensor, and does not talk to the Tymewear dashboard. Intervals.icu is the only sync destination.

## Requirements

- Android device, minSdk 26.
- Tymewear VitalPro strap.
- An Intervals.icu account with an active **Supporter** subscription (required for custom stream upload).
- Something recording your run to Intervals.icu — normally the Amazfit watch via the Zepp app, but any device whose activities land on Intervals.icu works.

## Build and install

```bash
cd phone
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/k-breathe-run.apk
```

## First-time setup

Do this once, on the Status and Settings tabs.

1. **Permissions.** On the Status tab, tap **Request permissions** and grant everything asked (Bluetooth, notifications, and any others the dialog lists).
2. **Battery optimisation.** Still on the Status tab, tap **Battery optimisation** and exclude K-Breathe Run. Then, in Android's own system settings, also exclude the **Zepp** app. If either app is left under battery optimisation, Android can kill it mid-run and the watch display or the recording can drop out.
3. **Intervals.icu Supporter.** Confirm your Intervals.icu account has an active Supporter subscription — stream upload fails without one.
4. **API key.** In Intervals.icu, go to Settings → Developer and copy your API key. Paste it into the **API key** field on the Settings tab and tap **Test** to confirm it's accepted.
5. **Custom streams.** In Intervals.icu, open any activity → Charts → Custom Streams → Add Stream, and create these seven codes with these exact units:

   | Code | Units |
   |---|---|
   | `TymeVentilation` | L/min |
   | `TymeBreathRate` | brpm |
   | `TymeTidalVolume` | L |
   | `TymeIERatio` | ratio |
   | `TymeVeZone` | zone |
   | `TymeBreathReserve` | % |
   | `TymeMobilizationIndex` | % |

   The Settings tab has a **Copy codes** button that copies this list to the clipboard so you can paste codes in as you go.
6. **Link Zepp to Intervals.icu.** In the Zepp app: Profile → third-party account linking, and connect Intervals.icu. This is what gets your Amazfit runs onto Intervals.icu in the first place.

The service is not started on boot; after a phone reboot, open the app once to start it again.

## Sessions

A session normally starts and stops automatically, driven by the watch extension through the phone's relay (`POST /session/start` and `POST /session/stop` on `127.0.0.1:41415`). If no stop signal arrives, the phone closes the session itself after the strap has been disconnected for 10 minutes, or after 8 hours, whichever comes first.

For a run without the watch, use the **Start session** / **Stop session** buttons on the Status tab.

Sessions shorter than 60 seconds are marked `skipped` and are not synced. Session data is kept for 90 days, then pruned.

## Intervals.icu sync

After a session ends, the app polls Intervals.icu every 2 minutes, for up to 6 hours, looking for an activity that starts within 5 minutes of the session. When it finds one, it pushes the seven breathing streams above, aligned to that activity's own time axis.

- **Success:** notification "Run synced to Intervals.icu" — tap it to open the activity.
- **Failure:** notification "Intervals.icu sync failed" with the reason (for example a missing custom stream).
- **No match after 6 hours:** notification that no Amazfit run was found; the session is marked `unmatched` and kept for manual matching.

Session states, shown on the Sessions tab: `pending`, `synced`, `unmatched`, `failed`, `skipped`.

On the Sessions tab you can:
- **Retry sync** — re-run the poll/match/push for a session.
- **Match by id** — enter an Intervals.icu activity id by hand to force the match.

## Strap pairing

On the Status tab, "Pair strap" registers the VitalPro with Android's Companion Device
Manager (requires Android 12/API 31+). Once paired, the foreground service — and its
persistent notification — only runs while the strap is nearby: Android's presence
detection wakes the app when the strap comes into range and tells it when the strap goes
away, instead of the service running all the time in the background. Detection is not
instant; it can take tens of seconds for the system to notice the strap has left, and the
service intentionally lags rather than guesses. A recording session in progress is never
interrupted by this — the service keeps running for the full session even if presence
detection reports the strap as away. Leaving the strap unpaired (or unpairing it again
from the Status tab) reverts to the previous always-on behaviour.

## Notifications

| Notification | Meaning |
|---|---|
| Run synced to Intervals.icu | Streams pushed successfully; tap opens the activity |
| Intervals.icu sync failed | Push failed; the text names the reason |
| No Amazfit run found for a breathing session | No matching activity after 6 hours; match it by hand on the Sessions tab |

## Developer notes

The relay is loopback-only (`127.0.0.1:41415`, no authentication) and can be exercised from a shell on the device:

```bash
adb shell curl http://127.0.0.1:41415/health
adb shell curl http://127.0.0.1:41415/live
adb shell curl -X POST http://127.0.0.1:41415/session/start
adb shell curl -X POST http://127.0.0.1:41415/session/stop
```

## First real run

This checklist exercises the app end to end against the live Intervals.icu API. It needs a phone with the app installed and set up as above, the VitalPro strap, and a working Intervals.icu API key. Any device that syncs to Intervals.icu works for the "record a run" step, as long as it's the only activity in the time window — it doesn't have to be the Amazfit watch.

1. **Create the custom streams in Intervals.icu**, if you haven't already under First-time setup: open any activity → Charts → Custom Streams → Add Stream, and create the seven codes and units listed above (or copy them from the Settings tab's Copy codes button).
2. **Record a manual session.** Wear the strap and, on the Status tab, tap Start session. At the same time, start recording an activity on any device that syncs to Intervals.icu (the Amazfit watch, a Karoo, or anything else). Wear the strap for at least two minutes, then stop both. Within a few minutes, the Sessions tab should show the session as `synced` with an activity id, and you should get a "Run synced to Intervals.icu" notification. If the activity comes from a non-Amazfit device, the matcher still picks it up as long as it's the only candidate in the window.
3. **Verify in Intervals.icu.** Open the matched activity, go to Charts, and add `TymeVentilation` to a custom chart. The VE trace should appear over the minutes you were recording, and be blank elsewhere.
