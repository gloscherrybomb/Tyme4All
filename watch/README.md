# Tyme4All watch extension (Zepp OS)

> **Status:** builds and passes its unit tests, but has not yet run on a real watch. The simulator cannot host workout extensions. Treat it as experimental; the phone app records and syncs without it.

A workout data-widget for the Amazfit Cheetah 2 Ultra that shows live
Tymewear VitalPro breathing data during a run, and drives session
start/stop on the [phone app](../phone/README.md). It
does no BLE of its own — it reads the phone app's local relay over the
watch's loopback network — and holds no state beyond the last received
payload and the latest heart rate reading.

See the design spec for the full picture:
[`docs/superpowers/specs/2026-09-03-tymewear-amazfit-design.md`](../docs/superpowers/specs/2026-09-03-tymewear-amazfit-design.md)
(sections 3.2, 4, 4.1, 5.1, 8, 9, 11).

## What the page shows

Round 480x480, one page, no scrolling:

- **VE** large, centre, with the zone colour as the background (teal, blue,
  amber, orange, red — the same Karoo zone palette as the phone app).
- **BR**, **TV** and **MI %** in a row below, smaller. MI shows `--` when
  heart rate is unavailable.
- **Zone name** small under the VE figure (Z1 to Z5, as Tymewear names
  them: Z1 below Endurance, Z2 Endurance to VT1, Z3 VT1 to VT2, Z4 VT2 to
  Top Z4, Z5 Top Z4 and above).
- **Status marker** at the top edge: green when connected, amber when
  stale, grey with a short label (`phone?`) when the strap is disconnected
  or the phone is unreachable — the whole background turns grey in that
  case so a glance is enough.

## How it talks to the phone

The extension never talks to the phone directly. The page
(`page/index.js`) talks to the watch-side service (`app-side/index.js`),
which polls the phone app's local relay at `http://127.0.0.1:41415`
(`shared/constants.js`, `RELAY_BASE`) once a second.

| Direction | `method` | `params` | Reply |
|---|---|---|---|
| watch -> side (request) | `session.start` | none | `{ sessionId }` or error string |
| watch -> side (request) | `session.stop` | none | `{ sessionId }` or error string |
| watch -> side (call) | `live.poll.start` | none | none; side starts pushing, and grants/renews a 15 s polling lease |
| watch -> side (call) | `live.poll.stop` | none | none; also satisfied automatically if the lease expires |
| side -> watch (call) | `live` | `{ payload, ok: true }` or `{ ok: false, error }` | none |

`session.start` fires from the page's `onInit`; `session.stop` is expected
from `onDestroy` (see the day-one checklist — this timing is unverified on
real hardware and has a documented fallback).

**Session-start retry latch.** A `session.start` request can be lost (Zepp
app or phone app not up yet). The side service (`app-side/index.js`)
latches `wantSession = true` the moment the request arrives, before calling
the relay, so it survives that call failing. On every successful `/live`
poll it checks the payload's `sessionId` (the source of truth, not the
original request's result) via the pure `shouldRequestStart` function in
`shared/session-latch.js`: if `wantSession` is true and `sessionId` is
still null/undefined, it calls `relay.start()` again. The phone's start is
idempotent, so this is safe to retry on every poll until a session opens.
`session.stop` clears the latch.

**Poll lease.** `live.poll.start` is a lease, not a one-shot toggle: the
side service records the time of every `live.poll.start` call
(`lastArmedMs`), and `pushLive` stops the poller if more than
`POLL_LEASE_MS` (15 s, `shared/constants.js`) has passed since the last
grant. The page's existing 1 s render timer re-sends `live.poll.start` on
every tick to keep the lease renewed while the page is visible, so a lost
`live.poll.stop` (e.g. on `onPause` failing to reach the side service)
still stops polling on its own within 15 s instead of running forever.
`live.poll.stop` continues to work immediately as before.

## Build and install

```bash
cd watch
npm test           # node --test test/ — 25 unit tests, no device needed
npm run build       # zeus build   -> watch/dist/<appId>-Tyme4All-<version>-<timestamp>.zab
npm run dev          # zeus dev    -> interactive Zepp OS simulator (see Simulator notes below)
```

To install on the physical watch, sideload with a preview QR code instead
of a full store build:

```bash
cd watch && npx zeus preview
```

This rebuilds the package, then prints a QR code (valid for 7 days) to scan
with the Zepp app. `zeus login` and `zeus preview` are both confirmed
working from this environment — see
`.superpowers/sdd/2026-09-03-watch-extension/build-verification-report.md`
for full command output. Sideloading works fine with the current
placeholder `appId` (`1000001` in `app.json`); a real `appId` issued by the
Zepp developer console is needed only for store submission, not for
day-one testing.

`zeus build` has been run successfully in this repo and produces, e.g.,
`watch/dist/1000001-Tyme4All-0.1.0-20260903193250.zab` (~57 KB). The build
emits two harmless warnings (the app icon is smaller than Zepp's
recommended 248x248, and an `import.meta` note about the test file, which
is not part of the bundled app) — neither requires a code change.

## Device and API facts (corrected — do not revert)

The Amazfit Cheetah 2 Ultra's device sources were wrong in earlier drafts
of `app.json` and have been corrected. If you're tempted to "fix" these
back, don't — the numbers below are the ones `zeus build` and `zeus
preview` accept and that produced a working install:

- `deviceSource` **9978112** (`cheetah2ultra` / `Munich3S`) and **9978113**
  (`cheetah2ultra_wn` / `MunichWN3S`).
- 480x480 round display (`designWidth: 480`).
- `runtime.apiVersion.target` is **`"4.4"`**; `minVersion` stays `"3.6"`.

`app.json` gotchas:

- `permissions` must be a plain string array
  (`["data:user.hd.heart_rate"]`), not `{code: ...}` objects — zeus 1.9.3
  rejects the object form.
- `runtime.ability[0].subType` `[1, 2, 3, 4, 5, 6]` is accepted by the
  bundler as-is; no reduction needed.
- `DataWidget(BasePage({...}))` in `page/index.js` builds and bundles
  cleanly (`[QJSC] 2 files, ... done!`, no warnings referencing it). The
  `MessageBuilder`-based fallback pattern noted in a comment at the top of
  `page/index.js` is **not** needed for the build to succeed — it exists
  only as a contingency if the page fails at runtime on the real watch
  (ZML expecting a `Page` rather than a `DataWidget`-wrapped `BasePage`).

## Testing

`npm test` runs the full unit suite (`node --test test/`, 25 tests): zone
colours, the loopback relay port, %HRR/%BRR/MI math against the phone's
formula, the poller (including the busy-lock reset on `stop()`), the relay
client's fetch timeout, the session-start retry latch, and the mock
relay's health/live/session-lifecycle endpoints, plus view-model rendering
for the connected/stale/disconnected states. No device or simulator is
required.

Beyond unit tests, two more things are worth doing before hardware, and a
full checklist of things that need the real watch:

- **Simulator run** — exercises the page rendering and relay wiring
  end-to-end without a physical watch. See "Simulator notes" below.
- **Day-one checklist** — see [`DAY-ONE.md`](DAY-ONE.md) for everything
  that still needs the physical Cheetah 2 Ultra: whether the extension
  shows up in the Run app's data-page picker, whether `onInit`/`onDestroy`
  fire at run start/end, whether the side service can reach the phone's
  loopback relay, whether the heart-rate sensor reports the paired TymeHR
  rather than the optical sensor, whether the Zepp app survives a full run
  backgrounded, and the end-to-end run with Intervals.icu receiving the
  streams. Each fallback from spec section 11 is noted against the
  relevant item.

## Simulator notes

The Zeus simulator run described in the task brief's Step 2 has **not**
been performed in this environment. `zeus dev` requires an interactive
Zepp account login and simulator UI, which are not available here (and are
excluded from this task's automated commands). The mock relay itself has
been verified end to end with Node (`curl` against `/health`, `/live`,
`/session/start`, `/session/stop`, and a 404 route, plus the real
`RelayClient` from `shared/relay-client.js` exercised against the running
mock) and is covered by `watch/test/mock-relay.test.js`.

To complete the simulator run, on a machine with a logged-in Zeus CLI:

```bash
# Terminal 1
cd watch && npm run mock-relay

# Terminal 2
cd watch && zeus dev
# choose the Cheetah 2 Ultra 480x480 round target (or the closest available
# round 480 target, such as the Cheetah Pro, if the simulator lacks the new
# device)
```

In the simulator, open the Workout app, add the Tyme4All data widget to a
run page, and start a run.

Expected observations:

- Within 2 s the page shows VE climbing, with the background colour moving
  through teal, blue, amber, orange, red.
- Every 90 s the page goes grey with dashes for 10 s and the status reads
  `stale`.
- The mock relay terminal logs `session start` when the run starts and
  `session stop` when it ends.
- Stopping the mock relay while a run is active: after 5 s the page reads
  `phone?`.
- Restarting the mock relay: values return.

Record any deviation from the above and the fix applied in this section.

## Next step

Once the watch is in hand, work through
[`DAY-ONE.md`](DAY-ONE.md) — that's the go/no-go checklist for everything
that can only be confirmed on real hardware.
