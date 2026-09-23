# Tyme4All

Android phone app that records breathing data from a Tymewear VitalPro strap during any activity and merges it into the matching Intervals.icu activity. It is the core of [Tyme4All](../README.md): the watch extension in `../watch/` and the Windows overlay in `../pc/` both read from it. The design notes are in `../docs/superpowers/specs/`.

## What it does

- Keeps the Tymewear VitalPro strap connected in the background and records every breath packet to a per-session raw log.
- Serves the latest breathing values over a loopback-only HTTP relay (`127.0.0.1:41415`) so a watch extension (or anything else on-device) can display them live.
- Opens a session when the strap starts streaming and closes it after 3 minutes without breathing data (configurable). The watch relay endpoints and the manual buttons still work but are not needed.
- After a session ends, waits for the Intervals.icu activity that overlaps the session by at least 5 minutes (an Amazfit run, a TrainingPeaks Virtual ride, a Karoo ride, anything except Strava imports), derives seven breathing streams from the raw log, and pushes them onto that activity.
- Notifies on sync success, sync failure, or no match found, and lets you retry or match by hand.
- If you signed in to Tymewear: after each Intervals.icu push, downloads the activity's original file from Intervals.icu, adds the breathing as Tymewear's own FIT fields, and replaces Tymewear's copy of the activity with it. Intervals.icu is not changed, and Karoo rides are skipped.
- If you signed in to Tymewear: reads your thresholds (Endurance, VT1, VT2, Top Z4, VO2max, run and bike) and resting and maximum breathing rate and heart rate from your Tymewear Fitness Profile when the app opens, right after sign-in, and when you tap **Refresh from Tymewear**.
- Serves the same live values, token-protected, on the phone's Wi-Fi address for the PC overlay.

It does not connect to a heart rate sensor. Tymewear's API is undocumented; if it changes, the Tymewear part stops and the rest of the app keeps working.

## Requirements

- Android device, minSdk 26.
- Tymewear VitalPro strap.
- An Intervals.icu account with an active **Supporter** subscription (required for custom stream upload).
- Something recording your activity to Intervals.icu — normally the Amazfit watch via the Zepp app, but any device whose activities land on Intervals.icu works.

## Build and install

```bash
cd phone
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/tyme4all.apk
```

### Release builds

`./gradlew assembleRelease` signs with the key named by these properties in your own `~/.gradle/gradle.properties`, never in the repo:

```properties
TYME4ALL_STORE_FILE=/path/to/tyme4all-release.jks
TYME4ALL_STORE_PASSWORD=...
TYME4ALL_KEY_ALIAS=tyme4all
TYME4ALL_KEY_PASSWORD=...
```

Without them the release build falls back to the debug key, which is fine for your own phone. APKs on the GitHub releases page are signed with the project key, SHA-256 certificate fingerprint `CF:19:9E:25:5F:70:55:29:E6:59:23:91:04:D4:7A:BC:BC:8E:A9:60:57:AB:89:1D:27:D3:66:BF:42:05:6F:12`. Android only upgrades an app in place when the key matches, so switching between a self-built debug APK and a release APK means uninstalling first.

## First-time setup

Do this once, on the Status and Settings tabs.

1. **Permissions.** On the Status tab, tap **Request permissions** and grant everything asked (Bluetooth, notifications, and any others the dialog lists).
2. **Battery optimisation.** Still on the Status tab, tap **Battery optimisation** and exclude Tyme4All. Then, in Android's own system settings, also exclude the **Zepp** app. If either app is left under battery optimisation, Android can kill it mid-run and the watch display or the recording can drop out.
3. **Intervals.icu Supporter.** Confirm your Intervals.icu account has an active Supporter subscription — stream upload fails without one.
4. **API key.** In Intervals.icu, go to Settings → Developer and copy your API key. Paste it into the **API key** field on the Settings tab and tap **Test** to confirm it's accepted.
5. **Sign in to Tymewear (optional).** In the **Tymewear** card on the Settings tab, enter your Tymewear email and password and tap **Sign in**. The password is kept encrypted on the phone, sent only to Tymewear, and left out of backups. Once signed in, the card shows the thresholds read from your Fitness Profile, when they were last read ("Thresholds read 07:42") or why the last read failed ("Couldn't read thresholds: HTTP 503"), a **Refresh from Tymewear** button that reads them again, and two switches: **Also send breathing to Tymewear** and **Use thresholds from Tymewear**. With the second off, or where Tymewear has no value, the thresholds on the Settings tab apply. Runs, trail runs, virtual runs, walks and hikes use your run thresholds; everything else and the live view use your bike thresholds.
6. **Custom streams.** In Intervals.icu, open any activity → Charts → Custom Streams → Add Stream, and create these seven codes with these exact units:

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
7. **Link Zepp to Intervals.icu.** In the Zepp app: Profile → third-party account linking, and connect Intervals.icu. This is what gets your Amazfit runs onto Intervals.icu in the first place.

The service is not started on boot; after a phone reboot, open the app once. Unpaired, that starts it again; with the strap paired, the app starts it when the strap comes into range.

## Sessions

Put the strap on: the first breath packet opens a session, and the app's persistent notification
changes to **Recording since HH:MM · VE …**. Take it off (or stop breathing into it): after 3 minutes without breathing
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

**Karoo rides.** The strap accepts one Bluetooth connection, and the Karoo's own [K-Breathe](https://github.com/gloscherrybomb/k-breathe) extension
records the same breathing fields into its FIT file. Before a Karoo ride, turn **Service enabled**
off on the Settings tab so the phone leaves the strap to the Karoo; turn it back on afterwards.
If the phone does take the strap during a Karoo ride, the sync never pushes to a Karoo activity,
so the Karoo's own recording is never overwritten.

## Intervals.icu sync

After a session ends, the app polls Intervals.icu every 2 minutes, for up to 6 hours, looking for the activity whose time span overlaps the session the most, with at least 5 minutes of overlap. Strava imports are skipped because Intervals.icu does not allow editing them, and Karoo activities are skipped because the Karoo records breathing itself. When it finds one, it pushes the seven breathing streams above, aligned to that activity's own time axis.

- **Success:** notification "Breathing data synced to Intervals.icu" — tap it to open the activity.
- **Failure:** notification "Intervals.icu sync failed" with the reason (for example a missing custom stream).
- **No match after 6 hours:** notification that no Intervals.icu activity was found (only for sessions of 15 minutes or more); the session is marked `unmatched` and kept for manual matching.

If two activities overlap the same session (for example a watch recording and a TPV recording of the same ride), the larger overlap gets the data and the other is named in the session's message; use **Match by id** to push to it as well. If the strap dropped out for more than 3 minutes mid-activity you get two sessions; both match the same activity and the second push carries the first session's data too, nothing is blanked.

Session states, shown on the Sessions tab: `pending`, `synced`, `unmatched`, `failed`, `skipped`, `discarded`.

On the Sessions tab you can:
- **Retry sync** — re-run the poll/match/push for a session.
- **Match by id** — enter an Intervals.icu activity id by hand to force the match.
- **Discard** — stop waiting for an activity that will never come (a strap test, a false start). The data is kept and **Retry sync** still works later.

## Tymewear upload

When you are signed in to Tymewear and **Also send breathing to Tymewear** is on, each session synced to Intervals.icu goes on to a Tymewear step. It waits for the activity to appear in Tymewear (up to 6 hours), then replaces Tymewear's copy with the Intervals.icu original plus the breathing. Tymewear copies an activity only once, when it arrives, and never picks up breathing added later, which is why the app sends it. A Tymewear failure never changes the Intervals.icu result.

**Troubleshooting.** Each session on the Sessions tab shows a **Tymewear:** line with its state (`pending`, `synced`, `skipped`, `failed`) and a message underneath, for example "not in Tymewear yet" or "Karoo rides carry breathing already". For a session already synced to Intervals.icu whose Tymewear step is `pending` or `failed`, **Retry sync** retries only the Tymewear step. While Tymewear is off (signed out, switched off, or the sign-in refused), a `pending` line says why, and **Retry sync** repeats only the Intervals.icu push. If Tymewear stops accepting your sign-in, the app stops contacting Tymewear, notifies you once, and the Tymewear card asks you to sign in again. Uploads that were waiting then go ahead once you sign in again, if that is within 6 hours of the session; after that, use **Retry sync**. A step still `pending` after 6 hours becomes `failed`, whether or not Tymewear is on.

## Strap pairing

On the Status tab, "Pair strap" registers the VitalPro with Android's Companion Device
Manager (requires Android 12/API 31+). Once paired, the foreground service — and its
persistent notification — only runs while the strap is nearby: Android's presence
detection wakes the app when the strap comes into range and tells it when the strap goes
away, instead of the service running all the time in the background. Detection is not
instant; it can take tens of seconds for the system to notice the strap has left, and the
service intentionally lags rather than guesses. A recording session in progress is never
interrupted by this — the service keeps running for the full session even if presence
detection reports the strap as away. It also keeps running after a session ends until that
session's Intervals.icu sync has finished or given up (synced, failed, or unmatched after
6 hours), so taking the strap off before the watch or TPV has uploaded the activity does
not stop the service before it can push. Leaving the strap unpaired (or unpairing it again
from the Status tab) reverts to the previous always-on behaviour.

With the strap paired, opening the app does not start the service while the strap is out of
range and nothing is waiting to sync. Instead the app looks for the strap itself for up to 20
seconds while it is open, with no notification, and starts the service if it finds it.
Otherwise the Status tab says it is waiting for the strap to come into range, and Android
starts the service when it does. Android reports presence only
when it changes, so if the service restarts while the strap is away it stops by itself once
the strap has not been connected for 2 minutes. If the Status tab says presence detection is
unavailable, the service runs all the time instead.

## Notifications

| Notification | Meaning |
|---|---|
| Tyme4All: Recording since 13:48 · VE 72 L/min | The persistent service notification while a session is open; the VE refreshes every 30 s |
| Tyme4All: Waiting for a matching Intervals.icu activity | Strap off, a finished session is still waiting for its activity to appear (up to 6 hours); the service stays running for that. Its **Discard, no activity coming** action gives up on those sessions at once, for example after just trying the strap on |
| Tyme4All: Strap connected / Waiting for strap | The same notification when nothing is recording or pending. It shows the current state from the moment the service starts |
| Breathing data synced to Intervals.icu | Streams pushed; tap opens the activity |
| Breathing added to Intervals.icu and Tymewear | Streams pushed and Tymewear's copy replaced in the same pass |
| Breathing added to Tymewear | A later Tymewear attempt succeeded for a session already on Intervals.icu; the text names the activity |
| Sign in to Tymewear again | Tymewear stopped accepting the saved sign-in; the app no longer contacts Tymewear until you sign in again in Settings |
| Intervals.icu sync failed | Push failed; the text names the reason |
| No Intervals.icu activity found for a breathing session | No overlapping activity after 6 hours (only for sessions of 15 minutes or more); match by hand on the Sessions tab |

## PC overlay (TrainingPeaks Virtual)

Settings tab: turn on **LAN overlay** and save. The Status tab then shows an overlay URL and QR
code while the phone is on Wi-Fi and the strap service is running (strap connected, or the app
open with the strap in range). That URL serves the live values (token-protected, read-only,
no session control) to anything on the same network: the Windows script in `../pc/`, or a
browser on a tablet. **Regenerate token** on the Settings tab invalidates the old URL.

## Developer notes

The relay is loopback-only (`127.0.0.1:41415`, no authentication) and can be exercised from a shell on the device. With the LAN overlay setting on, the same endpoints minus session control are also served on the Wi-Fi address with a token; see PC overlay.

```bash
adb shell curl http://127.0.0.1:41415/health
adb shell curl http://127.0.0.1:41415/live
adb shell curl -X POST http://127.0.0.1:41415/session/start
adb shell curl -X POST http://127.0.0.1:41415/session/stop
```

## First real session

This checklist exercises the app end to end against the live Intervals.icu API. It needs a phone with the app installed and set up as above, the VitalPro strap, and a working Intervals.icu API key. Any device that syncs to Intervals.icu works for the "record a session" step — it doesn't have to be the Amazfit watch.

1. **Create the custom streams in Intervals.icu**, if you haven't already under First-time setup: open any activity → Charts → Custom Streams → Add Stream, and create the seven codes and units listed above (or copy them from the Settings tab's Copy codes button).
2. **Record a session.** Pair the strap on the Status tab, then swipe the app away. Put the strap
   on: within about two minutes the "Recording since …" notification should appear without
   opening the app. Start an activity on any device that syncs to Intervals.icu (the Amazfit
   watch, TrainingPeaks Virtual, a Karoo). Ride or run for at least 6 minutes, stop the activity,
   take the strap off. About 3 minutes later the notification disappears. Within a few minutes
   more, the Sessions tab shows the session as `synced` with an activity id and you get the synced
   notification.
2a. **Reboot check.** Restart the phone, do not open the app, put the strap on: the recording
   notification should still appear. If either wake-up test fails, exclude the app from battery
   optimisation on the Status tab and repeat; note whether that fixed it.
3. **Verify in Intervals.icu.** Open the matched activity, go to Charts, and add `TymeVentilation` to a custom chart. The VE trace should appear over the minutes you were recording, and be blank elsewhere.
