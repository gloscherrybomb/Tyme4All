# Tyme4All watch extension — day-one checklist

This is the checklist to run through once the Amazfit Cheetah 2 Ultra is in
hand (or, for item 0, before it arrives). Each item is a go/no-go for the
corresponding part of the design (spec section 11). Record the result next
to each item — pass/fail and any notes — as you go.

Fallbacks are copied from spec section 11 and noted inline where they apply.

## 0. Simulator run — attempted 2026-09-03, BLOCKED

Do not spend time on this. It was set up and tried in full, and the
simulator cannot host this extension.

What was verified and passed:

- `zeus build` and `zeus preview` succeed; the build produces a `.zab`
  and an installable QR code.
- `zeus dev` connects to the simulator, builds for the Cheetah 2 Ultra
  device sources and installs the package, which then binds to the
  running emulator.
- The bundler accepts `DataWidget(BasePage(...))` and the full
  `runtime.ability.subType` list. The MessageBuilder fallback is not
  needed.

Why the page could not be rendered: this extension declares only
`module.data-widget` and `module.app-side`, so it has no launcher entry
by design and can only be opened inside the native Workout app. The
available emulator images (Cheetah Pro and T-Rex 3 Pro, both 480x480
round) contain only Settings in their app list — there is no Workout app
to host it. Temporarily exposing the same page via `module.page`, and
matching `apiVersion.target` to the emulator's API level, did not make it
appear either.

Setup notes if a future image ships the Workout app: the simulator needs
Rosetta 2 on Apple Silicon, because the bundled `qemu-system-arm` is an
x86_64 binary (`softwareupdate --install-rosetta`). Tunnelblick and the
tun/tap extension are NOT required; the launch script uses QEMU user-mode
networking. Emulator images cache to `~/.zepp/emulator_cache`.

The two checks below therefore move to the first run on the real watch.
Do them during item 5.

Also confirm while the simulator is up:

- **Nothing is clipped at the bezel.** Every text box (status, battery,
  VE, unit, zone name, and the BR/TV/MI value and label row) should sit
  fully inside the round display with visible margin on all sides —
  nothing cut off by the circular edge, especially the battery readout at
  top and the BR/MI % labels at the bottom of the row.
- **The background actually recolours when the zone changes.** Drive (or
  simulate) VE through a couple of zone thresholds and confirm the full-
  screen background genuinely changes colour each time (teal to blue to
  amber, etc.), not just the text. `w.bg.setProperty(prop.MORE, { color })`
  on a `FILL_RECT` is not universally supported across Zepp OS firmware —
  if the background stays fixed while the zone name/text still updates,
  that's the fallback trigger: recreate the `FILL_RECT` widget on colour
  change instead of calling `setProperty` on it.

## 1. Developer mode and sideload install

Zepp app: Profile, Settings, About, tap the version seven times to enable
developer mode; enable **Developer mode** and **Bridge mode** on the watch
page in the Zepp app.

- [ ] **Result:** _______________

`zeus login` and `zeus preview` are already confirmed to work from this
machine (see the build verification report) — `zeus preview` produces a QR
code valid for 7 days that installs with the placeholder `appId` `1000001`.
Scan it with the Zepp app. Pass looks like: Tyme4All installs and appears
in the watch's app list without error.

## 2. Extension appears in the Run app's data page configuration

Start a Run on the watch, open data page settings, confirm **Tyme4All**
appears as a data-widget option and can be added to a page.

- [ ] **Result:** _______________

**Unverified** — this is a runtime/OS behaviour that `zeus build` cannot
confirm. Pass looks like: Tyme4All is selectable and shows on the page.

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
the Tyme4All extension is active, and that those values track the strap's
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

Start a run with the Tyme4All widget on a page and the phone app running.
Confirm in the phone app's Sessions tab that a session opens at run start,
values refresh within 2 s after swiping away and back to the widget, and
the session closes with reason `watch` when the run ends.

- [ ] **Result:** _______________

**Unverified** — end-to-end integration, depends on items 2-4a above.

## 6. Zepp app survives backgrounding

With the phone in a pocket (screen off, Zepp app backgrounded) for a full
run — at least 5 minutes — confirm the `phone?` state never appears. If it
does, exclude the Zepp app (and Tyme4All) from Android battery
optimisation and repeat.

- [ ] **Result:** _______________

**Unverified** — Zepp app background survival on Android is a named risk
(spec section 12); recording itself never depends on this, only the live
watch display does.

## 7. Intervals.icu receives the run

After the run ends, wait for the phone notification "Run synced to
Intervals.icu". Open the matched activity's custom chart and confirm
`TymeVentilation` (and the other custom streams) render.

- [ ] **Result:** _______________

**Unverified** — this exercises the full chain: watch to phone to
Intervals.icu.

## 8. Strap presence detection

On the phone app's Status tab, pair the VitalPro strap ("Pair strap"). Confirm the
service's notification appears when the strap is nearby and disappears (and no longer
sits in the shade) within roughly a minute of walking away from the strap with no
session running. Start a session, then walk away with the strap on — confirm the
service and notification stay up for the whole session regardless of presence.

- [ ] **Result:** _______________

**Fallback:** if presence detection proves unreliable (false "away" reports, slow
recovery, etc.), unpair the strap from the Status tab — the service reverts to running
all the time, as it did before this feature.
