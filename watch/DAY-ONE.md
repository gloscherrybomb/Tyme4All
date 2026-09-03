# K-Breathe watch extension — day-one checklist

This is the checklist to run through once the Amazfit Cheetah 2 Ultra is in
hand (or, for item 0, before it arrives). Each item is a go/no-go for the
corresponding part of the design (spec section 11). Record the result next
to each item — pass/fail and any notes — as you go.

Fallbacks are copied from spec section 11 and noted inline where they apply.

## 0. Simulator run (pre-hardware, can be done now)

Build and unit tests are already verified (see
`.superpowers/sdd/2026-09-03-watch-extension/build-verification-report.md`).
What's left before the watch arrives is exercising the page in the Zepp OS
simulator against the mock relay.

- [ ] **Result:** _______________

Steps: see the "Simulator notes" section of `watch/README.md`
(`npm run mock-relay` in one terminal, `zeus dev` in another, target the
Cheetah 2 Ultra or closest available round 480 device). Pass looks like:
VE climbing within 2 s, background cycling through the zone colours, the
mock relay logging `session start`/`session stop`, and the page turning
grey with `phone?` within 5 s of stopping the mock relay.

## 1. Developer mode and sideload install

Zepp app: Profile, Settings, About, tap the version seven times to enable
developer mode; enable **Developer mode** and **Bridge mode** on the watch
page in the Zepp app.

- [ ] **Result:** _______________

`zeus login` and `zeus preview` are already confirmed to work from this
machine (see the build verification report) — `zeus preview` produces a QR
code valid for 7 days that installs with the placeholder `appId` `1000001`.
Scan it with the Zepp app. Pass looks like: K-Breathe installs and appears
in the watch's app list without error.

## 2. Extension appears in the Run app's data page configuration

Start a Run on the watch, open data page settings, confirm **K-Breathe**
appears as a data-widget option and can be added to a page.

- [ ] **Result:** _______________

**Unverified** — this is a runtime/OS behaviour that `zeus build` cannot
confirm. Pass looks like: K-Breathe is selectable and shows on the page.

## 3. Lifecycle: `onInit` at run start, `onDestroy` at run end

With the page added to a run screen, start a run and confirm (via the side
service's `session.start` request, logged from `onInit` in
`watch/page/index.js`) that it fires when the run starts, and that
`onDestroy` fires when the run ends.

- [ ] **Result:** _______________

**Unverified** — extension lifecycle timing is loosely documented by Zepp
(spec section 12 risk).

**Fallback if this fails:** move `session.start` into `onResume` (first
visibility) guarded by a `started` flag, and rely on the phone's own
fallback stop (idempotent start, no reliance on `onDestroy` firing).

## 4. Side service can reach the phone relay

While the phone app runs, confirm the side service's fetch to
`http://127.0.0.1:41415/health` (and `/live`) succeeds — check the watch
page shows live data rather than the `phone?` state.

- [ ] **Result:** _______________

**Unverified** — loopback fetch from a Zepp OS side service to the paired
phone has not been exercised outside the mock/simulator.

**Fallback if this fails:** point `RELAY_BASE` (in
`watch/shared/constants.js`) at the phone's Wi-Fi hotspot address instead of
loopback, or fetch directly from the extension if the platform allows it
(dropping the side service hop).

## 4a. Heart rate inside the workout extension, and TymeHR vs. optical

With the TymeHR strap paired to the watch, confirm the `HeartRate` sensor
API (`watch/page/index.js`, `startHr`/`stopHr`) returns live values while
the K-Breathe extension is active, and that those values track the strap's
readings — not the watch's own optical sensor. Compare the MI figure on the
page against the strap's implied HR for about a minute; they should track
together. Then remove the TymeHR: confirm the watch falls back to optical
and MI keeps updating (from the optical reading).

- [ ] **Result:** _______________

**Unverified** — whether a workout extension's `HeartRate` API reports the
paired external HR strap rather than the watch's own optical sensor is not
documented.

**Fallback if this fails:** drop MI from the watch page; it remains
available only in the phone-synced streams (which already derive MI from
the phone's HR source independently of the watch).

## 5. Full run: phone recording, session start/stop

Start a run with the K-Breathe widget on a page and the phone app running.
Confirm in the phone app's Sessions tab that a session opens at run start,
values refresh within 2 s after swiping away and back to the widget, and
the session closes with reason `watch` when the run ends.

- [ ] **Result:** _______________

**Unverified** — end-to-end integration, depends on items 2-4a above.

## 6. Zepp app survives backgrounding

With the phone in a pocket (screen off, Zepp app backgrounded) for a full
run — at least 5 minutes — confirm the `phone?` state never appears. If it
does, exclude the Zepp app (and K-Breathe Run) from Android battery
optimisation and repeat.

- [ ] **Result:** _______________

**Unverified** — Zepp app background survival on Android is a named risk
(spec section 12); recording itself never depends on this, only the live
watch display does.

## 7. Intervals.icu receives the run

After the run ends, wait for the phone notification "Run synced to
Intervals.icu". Open the matched activity's custom chart and confirm
`tyme_minute_volume` (and the other custom streams) render.

- [ ] **Result:** _______________

**Unverified** — this exercises the full chain: watch to phone to
Intervals.icu.
