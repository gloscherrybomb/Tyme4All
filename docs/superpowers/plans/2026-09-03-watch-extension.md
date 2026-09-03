# K-Breathe Watch Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Zepp OS mini program for the Amazfit Cheetah 2 Ultra whose workout extension page shows live VE, BR, TV, zone colour and mobilization index during a native Run, tells the phone app when the run starts and stops, and needs nothing configured on the watch.

**Architecture:** Two JavaScript halves joined by the ZML messaging library. The side service, running inside the Zepp phone app, polls the phone app's loopback relay once a second while asked to and pushes each payload to the watch; it also forwards session start and stop. The workout extension page renders whatever payload it last received, reads the watch's heart rate to compute MI, and never touches BLE. All logic that can be pure lives in `shared/` and is tested with Node's built-in test runner; a Node mock relay stands in for the phone during simulator work.

**Tech Stack:** Zepp OS API level 3.6+ (target 4.3), `@zeppos/zeus-cli` for build and simulator, `@zeppos/zml` 0.0.41 for device to side-service messaging, `@zos/ui`, `@zos/sensor` (HeartRate), Node 20 `node:test` for unit tests.

**Spec:** `docs/superpowers/specs/2026-09-03-tymewear-amazfit-design.md` (sections 3.2, 4, 4.1, 5.1, 8, 9, 10, 11)

## Global Constraints

- Relay base URL from the side service: `http://127.0.0.1:41415`. Endpoints `GET /health`, `GET /live`, `POST /session/start`, `POST /session/stop`. Payload shape is the phone plan's `LivePayload`: `{ ve, br, tv, ie, zone, batteryPct, status, sessionId, thresholds:{vt1,vt2,topZ4,vo2max}, reserve:{restingBr,maxBr,restingHr,maxHr}, updatedAtMs }` with `status` one of `connected | stale | disconnected | off`.
- Screen: round 480 x 480, `designWidth` 480. Device sources for the Cheetah 2 Ultra: `9961728`, `9961729`.
- Zone palette (index 0 is no zone): `0x424242, 0x4DB6AC, 0x0277BD, 0xF57F17, 0xEF6C00, 0xC62828`. Zone names: `--, Endurance, VT1, VT2, Top Z4, VO2Max`.
- MI formula exactly as the phone's `Reserve` object: `%HRR = (hr - restingHr)/(maxHr - restingHr)*100` floored at 0; `%BRR = (br - restingBr)/(maxBr - restingBr)*100` floored at 0; `MI = %BRR/%HRR*100`, `0` when `%HRR < 1`, `null` when any input is missing or a range is not positive.
- Poll period 1000 ms. A page that has not received a payload for 5000 ms while visible shows the phone-unreachable state.
- The extension never does BLE and holds no settings.
- Pure modules under `watch/shared/` import nothing from `@zos/*` so Node can test them.
- Commit after every task with the message given.

## File Structure

```
watch/
  package.json                 scripts: test (node --test), dev (zeus dev), build (zeus build); dependency @zeppos/zml
  app.json                     Zepp manifest: data-widget + app-side, permissions, target cheetah-2-ultra
  app.js                       App(BaseApp({...})) required by ZML
  page/index.js                DataWidget entry: lifecycle, widgets, HR sensor, message handling
  app-side/index.js            side service: relay polling, session forwarding
  shared/mi.js                 percentHrr, percentBrr, mobilizationIndex
  shared/view-model.js         payload + hr + now -> what to draw
  shared/relay-client.js       RelayClient(fetchImpl, baseUrl): live(), start(), stop(), health()
  shared/poller.js             Poller(tick, intervalMs, setIntervalImpl, clearIntervalImpl)
  shared/constants.js          colours, names, URLs, timings
  assets/cheetah-2-ultra/icon.png
  test/mi.test.js, view-model.test.js, relay-client.test.js, poller.test.js
  tools/mock-relay.js          Node HTTP server on 41415 emitting a synthetic run for the simulator
  DAY-ONE.md                   checklist for the first session with the real watch
  README.md
```

---

### Task 1: Project scaffold with a passing Node test and a Zepp manifest

**Files:**
- Create: `watch/package.json`, `watch/app.json`, `watch/app.js`, `watch/shared/constants.js`, `watch/test/constants.test.js`, `watch/assets/cheetah-2-ultra/icon.png`, `watch/.gitignore`

**Interfaces:**
- Produces: `shared/constants.js` exporting `RELAY_BASE`, `POLL_MS`, `STALE_MS`, `ZONE_COLORS`, `ZONE_NAMES`, `COLOR_TEXT`, `COLOR_MUTED`, `SCREEN`.

- [ ] **Step 1: Install the Zeus CLI and create the project skeleton**

```bash
cd "/Users/james.eastwood/Documents/CC Projects/TymewearAmazfit"
npm i -g @zeppos/zeus-cli
mkdir -p watch/page watch/app-side watch/shared watch/test watch/tools watch/assets/cheetah-2-ultra
cd watch && npm init -y >/dev/null && npm i @zeppos/zml@0.0.41
```

If `zeus create` is preferred, run `zeus create kbreathe-tmp`, choose `WORKOUT_EXTENSION`, then copy its generated `assets/` icon and any `.gitignore` into `watch/` and delete the temp folder. The files below replace everything else it generates.

- [ ] **Step 2: Write package.json scripts and gitignore**

`watch/package.json` (keep the generated name/version fields; set these keys):
```json
{
  "name": "kbreathe-watch",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "test": "node --test test/",
    "dev": "zeus dev",
    "build": "zeus build",
    "mock-relay": "node tools/mock-relay.js"
  },
  "dependencies": {
    "@zeppos/zml": "0.0.41"
  }
}
```

`watch/.gitignore`:
```
node_modules/
dist/
.zeus/
```

- [ ] **Step 3: Write app.json**

```json
{
  "configVersion": "v3",
  "app": {
    "appId": 1000001,
    "appName": "K-Breathe",
    "appType": "app",
    "version": { "code": 1, "name": "0.1.0" },
    "icon": "icon.png",
    "vender": "James Eastwood",
    "description": "Tymewear VitalPro breathing data during a run"
  },
  "permissions": [
    { "code": "data:user.hd.heart_rate" }
  ],
  "runtime": {
    "apiVersion": { "minVersion": "3.6", "target": "4.3", "compatible": "3.6" }
  },
  "targets": {
    "cheetah-2-ultra": {
      "module": {
        "data-widget": {
          "widgets": [
            {
              "path": "page/index",
              "name": "K-Breathe",
              "icon": "icon.png",
              "runtime": { "ability": [ { "type": 1, "subType": [1, 2, 3, 4, 5, 6] } ] }
            }
          ]
        },
        "app-side": { "path": "app-side/index" }
      },
      "platforms": [
        { "name": "cheetah2ultra", "deviceSource": 9961728 },
        { "name": "cheetah2ultra_b", "deviceSource": 9961729 }
      ],
      "designWidth": 480
    }
  },
  "i18n": { "en-US": { "appName": "K-Breathe" } },
  "defaultLanguage": "en-US"
}
```

`subType` lists the run-family sport sub types. If `zeus build` rejects a value, keep `[1]` (outdoor running) and add the others one at a time after checking the Workout Extension docs' sport type table.

The `appId` must be replaced with the id issued by the Zepp developer console before installing on a real watch (`zeus login` and create an app). Note this in `DAY-ONE.md` (Task 8).

- [ ] **Step 4: Write app.js and constants, with a test**

`watch/app.js`:
```javascript
import { BaseApp } from '@zeppos/zml/base-app'

App(
  BaseApp({
    globalData: {},
    onCreate() {},
    onDestroy() {},
  }),
)
```

`watch/shared/constants.js`:
```javascript
export const RELAY_BASE = 'http://127.0.0.1:41415'
export const POLL_MS = 1000
export const STALE_MS = 5000

export const ZONE_COLORS = [0x424242, 0x4db6ac, 0x0277bd, 0xf57f17, 0xef6c00, 0xc62828]
export const ZONE_NAMES = ['--', 'Endurance', 'VT1', 'VT2', 'Top Z4', 'VO2Max']
export const COLOR_TEXT = 0xffffff
export const COLOR_MUTED = 0xbdbdbd
export const COLOR_OK = 0x66bb6a
export const COLOR_WARN = 0xffb300
export const COLOR_GREY = 0x9e9e9e

export const SCREEN = { w: 480, h: 480 }
```

`watch/test/constants.test.js`:
```javascript
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ZONE_COLORS, ZONE_NAMES, RELAY_BASE } from '../shared/constants.js'

test('six zone colours and names, index 0 is no zone', () => {
  assert.equal(ZONE_COLORS.length, 6)
  assert.equal(ZONE_NAMES.length, 6)
  assert.equal(ZONE_NAMES[0], '--')
  assert.equal(ZONE_COLORS[5], 0xc62828)
})

test('relay is loopback on the agreed port', () => {
  assert.equal(RELAY_BASE, 'http://127.0.0.1:41415')
})
```

Add a placeholder `icon.png` (any 96x96 PNG; the Zeus template's icon is fine).

- [ ] **Step 5: Run the tests**

Run: `cd watch && npm test`
Expected: 2 passing.

- [ ] **Step 6: Commit**

```bash
cd "/Users/james.eastwood/Documents/CC Projects/TymewearAmazfit"
git add watch
git commit -m "watch: scaffold Zepp OS project with manifest and Node tests"
```

---

### Task 2: MI formula module

**Files:**
- Create: `watch/shared/mi.js`
- Test: `watch/test/mi.test.js`

**Interfaces:**
- Produces: `percentHrr(hr, reserve)`, `percentBrr(br, reserve)`, `mobilizationIndex(br, hr, reserve)`; `reserve` is `{restingBr, maxBr, restingHr, maxHr}`. Return numbers or `null`.

- [ ] **Step 1: Write the failing test**

```javascript
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { percentHrr, percentBrr, mobilizationIndex } from '../shared/mi.js'

const r = { restingBr: 12, maxBr: 55, restingHr: 60, maxHr: 190 }

test('percent hrr', () => {
  assert.equal(percentHrr(125, r), 50)
  assert.equal(percentHrr(50, r), 0)
  assert.equal(percentHrr(null, r), null)
  assert.equal(percentHrr(0, r), null)
})

test('percent brr', () => {
  assert.equal(percentBrr(33.5, r), 50)
  assert.equal(percentBrr(10, r), 0)
  assert.equal(percentBrr(undefined, r), null)
})

test('mobilization index matches the phone formula', () => {
  assert.equal(mobilizationIndex(33.5, 125, r), 100)
  assert.equal(mobilizationIndex(33.5, 60.5, r), 0)      // %HRR below 1
  assert.equal(mobilizationIndex(null, 125, r), null)
  assert.equal(mobilizationIndex(33.5, null, r), null)
  assert.equal(mobilizationIndex(30, 120, { ...r, maxBr: 12 }), null)
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd watch && npm test`
Expected: `mi.test.js` fails, cannot find module.

- [ ] **Step 3: Implement**

```javascript
// Same formula as the phone app's Reserve object. Keep them in step.
function positive(n) { return typeof n === 'number' && Number.isFinite(n) && n > 0 }

export function percentHrr(hr, r) {
  const range = r.maxHr - r.restingHr
  if (!positive(hr) || !(range > 0)) return null
  return Math.max(0, ((hr - r.restingHr) / range) * 100)
}

export function percentBrr(br, r) {
  const range = r.maxBr - r.restingBr
  if (!positive(br) || !(range > 0)) return null
  return Math.max(0, ((br - r.restingBr) / range) * 100)
}

export function mobilizationIndex(br, hr, r) {
  const brr = percentBrr(br, r)
  const hrr = percentHrr(hr, r)
  if (brr === null || hrr === null) return null
  return hrr >= 1 ? (brr / hrr) * 100 : 0
}
```

- [ ] **Step 4: Run the tests**

Run: `cd watch && npm test`
Expected: all passing.

- [ ] **Step 5: Commit**

```bash
git add watch/shared/mi.js watch/test/mi.test.js
git commit -m "watch: mobilization index formula"
```

---

### Task 3: View model

**Files:**
- Create: `watch/shared/view-model.js`
- Test: `watch/test/view-model.test.js`

**Interfaces:**
- Produces:
```javascript
// state: { payload: LivePayload|null, receivedAtMs: number|null, hr: number|null }
export function buildViewModel(state, nowMs) -> {
  bg: number,           // background colour
  ve: string,           // '62' or '--'
  zoneName: string,     // 'Endurance' or '--'
  br: string, tv: string, mi: string,   // '25', '2.5', '87' or '--'
  status: string,       // 'connected' | 'stale' | 'disconnected' | 'off' | 'phone?'
  statusColor: number,
  battery: string       // '77%' or ''
}
```
Rules: `phone?` when `receivedAtMs` is null or older than `STALE_MS`; then `bg` grey and every value `--`. Otherwise `status` from the payload; `bg` is the zone colour only when status is `connected`, grey otherwise. `ve` rounded to integer, `br` integer, `tv` one decimal, `mi` integer from `mobilizationIndex(payload.br, hr, payload.reserve)`. `statusColor`: green for connected, amber for stale, grey otherwise.

- [ ] **Step 1: Write the failing test**

```javascript
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildViewModel } from '../shared/view-model.js'
import { ZONE_COLORS, COLOR_GREY, COLOR_OK, COLOR_WARN } from '../shared/constants.js'

const payload = {
  ve: 62.4, br: 24.6, tv: 2.53, ie: 1.0, zone: 1, batteryPct: 77, status: 'connected', sessionId: 's',
  thresholds: { vt1: 73, vt2: 96, topZ4: 112, vo2max: 130 },
  reserve: { restingBr: 12, maxBr: 55, restingHr: 60, maxHr: 190 },
  updatedAtMs: 1000,
}

test('no payload yet means phone unreachable', () => {
  const vm = buildViewModel({ payload: null, receivedAtMs: null, hr: null }, 10_000)
  assert.equal(vm.status, 'phone?')
  assert.equal(vm.bg, COLOR_GREY)
  assert.equal(vm.ve, '--')
  assert.equal(vm.mi, '--')
})

test('old payload means phone unreachable', () => {
  const vm = buildViewModel({ payload, receivedAtMs: 1_000, hr: 125 }, 6_001)
  assert.equal(vm.status, 'phone?')
  assert.equal(vm.bg, COLOR_GREY)
})

test('connected payload renders values and zone colour', () => {
  const vm = buildViewModel({ payload, receivedAtMs: 5_000, hr: 125 }, 5_500)
  assert.equal(vm.status, 'connected')
  assert.equal(vm.statusColor, COLOR_OK)
  assert.equal(vm.bg, ZONE_COLORS[1])
  assert.equal(vm.ve, '62')
  assert.equal(vm.br, '25')
  assert.equal(vm.tv, '2.5')
  assert.equal(vm.zoneName, 'Endurance')
  assert.equal(vm.battery, '77%')
  // %BRR = (24.6-12)/43*100 = 29.30; %HRR = 50 -> MI 58.6 -> '59'
  assert.equal(vm.mi, '59')
})

test('no heart rate gives dashes for MI only', () => {
  const vm = buildViewModel({ payload, receivedAtMs: 5_000, hr: null }, 5_500)
  assert.equal(vm.mi, '--')
  assert.equal(vm.ve, '62')
})

test('stale and disconnected go grey with dashes', () => {
  const stale = buildViewModel({ payload: { ...payload, status: 'stale', ve: null, br: null, tv: null, zone: 0 }, receivedAtMs: 5_000, hr: 120 }, 5_500)
  assert.equal(stale.status, 'stale')
  assert.equal(stale.statusColor, COLOR_WARN)
  assert.equal(stale.bg, COLOR_GREY)
  assert.equal(stale.ve, '--')
  const off = buildViewModel({ payload: { ...payload, status: 'disconnected', ve: null, br: null, tv: null, zone: 0, batteryPct: null }, receivedAtMs: 5_000, hr: 120 }, 5_500)
  assert.equal(off.battery, '')
  assert.equal(off.statusColor, COLOR_GREY)
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd watch && npm test`
Expected: `view-model.test.js` fails.

- [ ] **Step 3: Implement**

```javascript
import { mobilizationIndex } from './mi.js'
import { ZONE_COLORS, ZONE_NAMES, STALE_MS, COLOR_GREY, COLOR_OK, COLOR_WARN } from './constants.js'

const DASH = '--'
function fmt(n, decimals) {
  return typeof n === 'number' && Number.isFinite(n) ? n.toFixed(decimals) : DASH
}

export function buildViewModel(state, nowMs) {
  const { payload, receivedAtMs, hr } = state
  const unreachable = payload == null || receivedAtMs == null || nowMs - receivedAtMs > STALE_MS
  if (unreachable) {
    return { bg: COLOR_GREY, ve: DASH, zoneName: DASH, br: DASH, tv: DASH, mi: DASH, status: 'phone?', statusColor: COLOR_GREY, battery: '' }
  }
  const connected = payload.status === 'connected'
  const zone = connected && payload.zone >= 0 && payload.zone < ZONE_COLORS.length ? payload.zone : 0
  const mi = connected ? mobilizationIndex(payload.br, hr, payload.reserve) : null
  return {
    bg: connected ? ZONE_COLORS[zone] : COLOR_GREY,
    ve: connected ? fmt(payload.ve, 0) : DASH,
    zoneName: connected ? ZONE_NAMES[zone] : DASH,
    br: connected ? fmt(payload.br, 0) : DASH,
    tv: connected ? fmt(payload.tv, 1) : DASH,
    mi: fmt(mi, 0),
    status: payload.status,
    statusColor: connected ? COLOR_OK : payload.status === 'stale' ? COLOR_WARN : COLOR_GREY,
    battery: typeof payload.batteryPct === 'number' ? `${payload.batteryPct}%` : '',
  }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd watch && npm test`
Expected: all passing.

- [ ] **Step 5: Commit**

```bash
git add watch/shared/view-model.js watch/test/view-model.test.js
git commit -m "watch: view model from relay payload and heart rate"
```

---

### Task 4: Relay client and poller (pure, injectable)

**Files:**
- Create: `watch/shared/relay-client.js`, `watch/shared/poller.js`
- Test: `watch/test/relay-client.test.js`, `watch/test/poller.test.js`

**Interfaces:**
- Produces:
```javascript
export class RelayClient {
  constructor(fetchImpl, baseUrl = RELAY_BASE)
  async health()  -> { ok, version }
  async live()    -> LivePayload
  async start()   -> { sessionId }
  async stop()    -> { sessionId }
}
export class Poller {
  constructor(tick /* async fn */, intervalMs, timers = { setInterval, clearInterval })
  start()  // idempotent; calls tick immediately, then every intervalMs; overlapping ticks are skipped
  stop()
  get running()
}
```
`RelayClient` treats a non-2xx as a thrown `Error('relay HTTP <code>')`. The Zepp side-service `fetch` resolves with `{ status, body }` where `body` may already be an object or a JSON string; the client accepts both (`typeof body === 'string' ? JSON.parse(body) : body`). Node's `fetch` returns a `Response`; the client handles that too by checking for a `json` function.

- [ ] **Step 1: Write the failing tests**

`relay-client.test.js`:
```javascript
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { RelayClient } from '../shared/relay-client.js'

function zeppFetch(log, status, body) {
  return async (opts) => { log.push(opts); return { status, body } }
}

test('live GET parses a JSON string body (Zepp style)', async () => {
  const log = []
  const c = new RelayClient(zeppFetch(log, 200, JSON.stringify({ ve: 40, status: 'connected' })), 'http://127.0.0.1:41415')
  const p = await c.live()
  assert.equal(p.ve, 40)
  assert.equal(log[0].url, 'http://127.0.0.1:41415/live')
  assert.equal(log[0].method, 'GET')
})

test('accepts an object body and a Response-like object', async () => {
  const c1 = new RelayClient(async () => ({ status: 200, body: { ok: true, version: 'x' } }))
  assert.deepEqual(await c1.health(), { ok: true, version: 'x' })
  const c2 = new RelayClient(async () => ({ status: 200, json: async () => ({ sessionId: 's1' }) }))
  assert.deepEqual(await c2.start(), { sessionId: 's1' })
})

test('start and stop are POSTs', async () => {
  const log = []
  const c = new RelayClient(zeppFetch(log, 200, '{"sessionId":null}'))
  await c.start(); await c.stop()
  assert.equal(log[0].method, 'POST'); assert.equal(log[0].url, 'http://127.0.0.1:41415/session/start')
  assert.equal(log[1].method, 'POST'); assert.equal(log[1].url, 'http://127.0.0.1:41415/session/stop')
})

test('non 2xx throws', async () => {
  const c = new RelayClient(zeppFetch([], 500, 'boom'))
  await assert.rejects(() => c.live(), /relay HTTP 500/)
})
```

`poller.test.js`:
```javascript
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Poller } from '../shared/poller.js'

function fakeTimers() {
  const t = { handlers: new Map(), next: 1 }
  t.setInterval = (fn, ms) => { const id = t.next++; t.handlers.set(id, { fn, ms }); return id }
  t.clearInterval = (id) => { t.handlers.delete(id) }
  t.fire = async () => { for (const h of t.handlers.values()) await h.fn() }
  return t
}

test('ticks immediately on start, then on each interval, once', async () => {
  const t = fakeTimers(); let n = 0
  const p = new Poller(async () => { n++ }, 1000, t)
  p.start(); p.start()
  await Promise.resolve()
  assert.equal(n, 1)
  assert.equal(t.handlers.size, 1)
  assert.equal([...t.handlers.values()][0].ms, 1000)
  await t.fire(); await t.fire()
  assert.equal(n, 3)
  p.stop()
  assert.equal(t.handlers.size, 0)
  assert.equal(p.running, false)
})

test('a slow tick is not overlapped', async () => {
  const t = fakeTimers(); let inFlight = 0, max = 0
  let release
  const p = new Poller(() => new Promise((r) => { inFlight++; max = Math.max(max, inFlight); release = () => { inFlight--; r() } }), 1000, t)
  p.start()
  await t.fire()            // skipped: first tick still running
  assert.equal(max, 1)
  release(); await Promise.resolve()
  await t.fire()
  assert.equal(max, 1)
  release()
})

test('tick errors do not stop the poller', async () => {
  const t = fakeTimers(); let calls = 0
  const p = new Poller(async () => { calls++; throw new Error('x') }, 1000, t)
  p.start(); await Promise.resolve()
  await t.fire()
  assert.equal(calls, 2)
  assert.equal(p.running, true)
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd watch && npm test`
Expected: both new files fail.

- [ ] **Step 3: Implement**

`relay-client.js`:
```javascript
import { RELAY_BASE } from './constants.js'

async function readBody(res) {
  if (typeof res.json === 'function') return res.json()
  const b = res.body
  return typeof b === 'string' ? JSON.parse(b) : b
}

export class RelayClient {
  constructor(fetchImpl, baseUrl = RELAY_BASE) {
    this.fetch = fetchImpl
    this.base = baseUrl
  }

  async #call(method, path) {
    const res = await this.fetch({ method, url: this.base + path, headers: { 'Content-Type': 'application/json' } })
    if (!res || res.status < 200 || res.status >= 300) throw new Error(`relay HTTP ${res ? res.status : 'none'}`)
    return readBody(res)
  }

  health() { return this.#call('GET', '/health') }
  live() { return this.#call('GET', '/live') }
  start() { return this.#call('POST', '/session/start') }
  stop() { return this.#call('POST', '/session/stop') }
}
```

`poller.js`:
```javascript
export class Poller {
  constructor(tick, intervalMs, timers = { setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval }) {
    this.tick = tick
    this.intervalMs = intervalMs
    this.timers = timers
    this.handle = null
    this.busy = false
  }

  get running() { return this.handle !== null }

  async #run() {
    if (this.busy) return
    this.busy = true
    try { await this.tick() } catch (_) { /* keep polling */ } finally { this.busy = false }
  }

  start() {
    if (this.handle !== null) return
    this.handle = this.timers.setInterval(() => { this.#run() }, this.intervalMs)
    this.#run()
  }

  stop() {
    if (this.handle === null) return
    this.timers.clearInterval(this.handle)
    this.handle = null
  }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd watch && npm test`
Expected: all passing.

- [ ] **Step 5: Commit**

```bash
git add watch/shared/relay-client.js watch/shared/poller.js watch/test
git commit -m "watch: relay client and non-overlapping poller"
```

---

### Task 5: Side service

**Files:**
- Create: `watch/app-side/index.js`

**Interfaces:**
- Consumes: `RelayClient`, `Poller`, ZML `BaseSideService` (`onRequest(req, res)`, `onCall(data)`, `this.call(data)`).
- Produces: message protocol between watch and side service:

| Direction | `method` | `params` | Reply |
|---|---|---|---|
| watch -> side (request) | `session.start` | none | `{ sessionId }` or error string |
| watch -> side (request) | `session.stop` | none | `{ sessionId }` or error string |
| watch -> side (call) | `live.poll.start` | none | none; side starts pushing |
| watch -> side (call) | `live.poll.stop` | none | none |
| side -> watch (call) | `live` | `{ payload, ok: true }` or `{ ok: false, error }` | none |

- [ ] **Step 1: Write the side service**

```javascript
import { BaseSideService } from '@zeppos/zml/base-side'
import { RelayClient } from '../shared/relay-client.js'
import { Poller } from '../shared/poller.js'
import { POLL_MS } from '../shared/constants.js'

// `fetch` here is the Zepp side-service fetch: fetch({ method, url, headers, body }) -> { status, body }.
const relay = new RelayClient((opts) => fetch(opts))

AppSideService(
  BaseSideService({
    state: { poller: null },

    onInit() {
      this.state.poller = new Poller(() => this.pushLive(), POLL_MS)
    },

    async pushLive() {
      try {
        const payload = await relay.live()
        this.call({ method: 'live', params: { ok: true, payload } })
      } catch (e) {
        this.call({ method: 'live', params: { ok: false, error: String(e && e.message ? e.message : e) } })
      }
    },

    onRequest(req, res) {
      const done = (p) => p.then((r) => res(null, r)).catch((e) => res(String(e && e.message ? e.message : e)))
      switch (req.method) {
        case 'session.start': return done(relay.start())
        case 'session.stop': return done(relay.stop())
        case 'relay.health': return done(relay.health())
        default: return res(`unknown method ${req.method}`)
      }
    },

    onCall(data) {
      if (!data || !data.method) return
      if (data.method === 'live.poll.start') this.state.poller.start()
      else if (data.method === 'live.poll.stop') this.state.poller.stop()
    },

    onDestroy() {
      if (this.state.poller) this.state.poller.stop()
    },
  }),
)
```

- [ ] **Step 2: Build check**

Run: `cd watch && zeus build` (or `zeus preview` if `build` needs a login).
Expected: build completes with no syntax errors for `app-side/index.js`. If Zeus complains about `#private` methods in `shared/` when bundling for the device, replace `#call` and `#run` with `_call` and `_run` in both files and re-run the Node tests.

- [ ] **Step 3: Commit**

```bash
git add watch/app-side
git commit -m "watch: side service polls the phone relay and forwards session events"
```

---

### Task 6: Workout extension page

**Files:**
- Create: `watch/page/index.js`

**Interfaces:**
- Consumes: `buildViewModel`, constants, ZML `BasePage` (`this.request`, `this.call`, `onCall`), `@zos/ui` (`createWidget`, `widget.FILL_RECT`, `widget.TEXT`, `prop`, `align`), `@zos/sensor` `HeartRate`.
- Produces: the page that the Workout app hosts. Lifecycle mapping (spec 5.1 and 8):
  - `onInit`: build state; `this.request({ method: 'session.start' })`, log the result, ignore failure.
  - `build`: create widgets from an initial view model.
  - `onResume`: start HR listening; `this.call({ method: 'live.poll.start' })`; start a 1 s local timer that re-renders (so the 5 s phone-unreachable rule applies even when no messages arrive).
  - `onPause`: `this.call({ method: 'live.poll.stop' })`; stop HR listening and the local timer.
  - `onDestroy`: as `onPause`, then `this.request({ method: 'session.stop' })`.
  - `onCall(data)`: when `data.method === 'live'` and `data.params.ok`, store `payload` and `receivedAtMs = Date.now()`; when not ok, leave `receivedAtMs` alone so the view goes to `phone?` after 5 s.

Layout for 480 x 480 round:

| Element | x | y | w | h | size |
|---|---|---|---|---|---|
| background FILL_RECT | 0 | 0 | 480 | 480 | |
| status text (`connected`, `phone?`) | 90 | 34 | 300 | 36 | 28 |
| battery text | 300 | 34 | 120 | 36 | 24, right aligned |
| VE value | 40 | 120 | 400 | 150 | 140 |
| VE unit `L/min` | 40 | 262 | 400 | 30 | 26 |
| zone name | 40 | 292 | 400 | 40 | 34 |
| BR value / label | 40 | 350 | 130 | 60 / 30 | 56 / 22 |
| TV value / label | 175 | 350 | 130 | 60 / 30 | 56 / 22 |
| MI value / label | 310 | 350 | 130 | 60 / 30 | 56 / 22 |

- [ ] **Step 1: Write the page**

```javascript
import { BasePage } from '@zeppos/zml/base-page'
import { createWidget, widget, prop, align } from '@zos/ui'
import { HeartRate } from '@zos/sensor'
import { buildViewModel } from '../shared/view-model.js'
import { COLOR_TEXT, COLOR_MUTED, SCREEN } from '../shared/constants.js'

function text(x, y, w, h, size, color, alignH, value) {
  return createWidget(widget.TEXT, { x, y, w, h, text_size: size, color, align_h: alignH, align_v: align.CENTER_V, text: value })
}

DataWidget(
  BasePage({
    state: {
      payload: null,
      receivedAtMs: null,
      hr: null,
      widgets: null,
      timer: null,
      heartRate: null,
      onHr: null,
    },

    onInit() {
      this.request({ method: 'session.start' })
        .then((r) => console.log('session.start ->', JSON.stringify(r)))
        .catch((e) => console.log('session.start failed', e))
    },

    build() {
      const vm = buildViewModel(this.state, Date.now())
      const w = {}
      w.bg = createWidget(widget.FILL_RECT, { x: 0, y: 0, w: SCREEN.w, h: SCREEN.h, color: vm.bg })
      w.status = text(90, 34, 300, 36, 28, vm.statusColor, align.CENTER_H, vm.status)
      w.battery = text(300, 34, 120, 36, 24, COLOR_MUTED, align.RIGHT, vm.battery)
      w.ve = text(40, 120, 400, 150, 140, COLOR_TEXT, align.CENTER_H, vm.ve)
      w.unit = text(40, 262, 400, 30, 26, COLOR_MUTED, align.CENTER_H, 'L/min')
      w.zone = text(40, 292, 400, 40, 34, COLOR_TEXT, align.CENTER_H, vm.zoneName)
      w.br = text(40, 350, 130, 60, 56, COLOR_TEXT, align.CENTER_H, vm.br)
      w.tv = text(175, 350, 130, 60, 56, COLOR_TEXT, align.CENTER_H, vm.tv)
      w.mi = text(310, 350, 130, 60, 56, COLOR_TEXT, align.CENTER_H, vm.mi)
      text(40, 410, 130, 30, 22, COLOR_MUTED, align.CENTER_H, 'BR')
      text(175, 410, 130, 30, 22, COLOR_MUTED, align.CENTER_H, 'TV')
      text(310, 410, 130, 30, 22, COLOR_MUTED, align.CENTER_H, 'MI %')
      this.state.widgets = w
    },

    render() {
      const w = this.state.widgets
      if (!w) return
      const vm = buildViewModel(this.state, Date.now())
      w.bg.setProperty(prop.MORE, { color: vm.bg })
      w.status.setProperty(prop.MORE, { text: vm.status, color: vm.statusColor })
      w.battery.setProperty(prop.TEXT, vm.battery)
      w.ve.setProperty(prop.TEXT, vm.ve)
      w.zone.setProperty(prop.TEXT, vm.zoneName)
      w.br.setProperty(prop.TEXT, vm.br)
      w.tv.setProperty(prop.TEXT, vm.tv)
      w.mi.setProperty(prop.TEXT, vm.mi)
    },

    startHr() {
      if (this.state.heartRate) return
      const hr = new HeartRate()
      const onHr = () => { const v = hr.getCurrent(); this.state.hr = typeof v === 'number' && v > 0 ? v : null }
      hr.onCurrentChange(onHr)
      this.state.heartRate = hr
      this.state.onHr = onHr
    },

    stopHr() {
      if (!this.state.heartRate) return
      this.state.heartRate.offCurrentChange(this.state.onHr)
      this.state.heartRate = null
      this.state.onHr = null
    },

    onResume() {
      this.startHr()
      this.call({ method: 'live.poll.start' })
      if (this.state.timer === null) this.state.timer = setInterval(() => this.render(), 1000)
      this.render()
    },

    onPause() {
      this.call({ method: 'live.poll.stop' })
      this.stopHr()
      if (this.state.timer !== null) { clearInterval(this.state.timer); this.state.timer = null }
    },

    onCall(data) {
      if (!data || data.method !== 'live') return
      if (data.params && data.params.ok) {
        this.state.payload = data.params.payload
        this.state.receivedAtMs = Date.now()
      }
      this.render()
    },

    onDestroy() {
      this.onPause()
      this.request({ method: 'session.stop' })
        .then((r) => console.log('session.stop ->', JSON.stringify(r)))
        .catch((e) => console.log('session.stop failed', e))
    },
  }),
)
```

If `DataWidget(BasePage(...))` fails at runtime because ZML expects `Page`, fall back to the `MessageBuilder` pattern from the Zepp "MessageBuilder Bluetooth Communication" guide: copy `shared/message.js` and `shared/device-polyfill.js` from `zepp-health/zeppos-samples`, create the builder in `app.js`, and replace `this.request`/`this.call`/`onCall` with `messageBuilder.request`, `messageBuilder.call` and `messageBuilder.on('call', ...)`. The side service then uses `messageBuilder.on('request', ctx => ...)` and `messageBuilder.call(...)`. The message names and payloads above stay the same.

- [ ] **Step 2: Build**

Run: `cd watch && zeus build`
Expected: bundle produced without errors.

- [ ] **Step 3: Commit**

```bash
git add watch/page
git commit -m "watch: workout extension page with live values, zone colour and MI"
```

---

### Task 7: Mock relay and simulator run

**Files:**
- Create: `watch/tools/mock-relay.js`

**Interfaces:**
- Produces: a Node HTTP server on `127.0.0.1:41415` serving the same four endpoints as the phone, with a synthetic run: VE ramps 30 to 140 L/min over 3 minutes and back, BR and TV follow, `status` flips to `stale` for 10 s every 90 s, battery 80. `POST /session/start` returns a fixed id and logs; `/session/stop` logs.

- [ ] **Step 1: Write the mock relay**

```javascript
import http from 'node:http'

const started = Date.now()
let sessionId = null
const thresholds = { vt1: 73, vt2: 96, topZ4: 112, vo2max: 130 }
const reserve = { restingBr: 12, maxBr: 55, restingHr: 60, maxHr: 190 }

function zoneFor(ve) {
  if (ve <= 0) return 0
  if (ve < thresholds.vt1) return 1
  if (ve < thresholds.vt2) return 2
  if (ve < thresholds.topZ4) return 3
  if (ve < thresholds.vo2max) return 4
  return 5
}

function live() {
  const t = (Date.now() - started) / 1000
  const phase = (t % 360) / 360                      // 6 minute cycle
  const ramp = phase < 0.5 ? phase * 2 : (1 - phase) * 2
  const ve = 30 + ramp * 110
  const br = 14 + ramp * 36
  const tv = ve / br
  const stale = t % 90 > 80
  return {
    ve: stale ? null : ve, br: stale ? null : br, tv: stale ? null : tv, ie: stale ? null : 0.9,
    zone: stale ? 0 : zoneFor(ve), batteryPct: 80, status: stale ? 'stale' : 'connected',
    sessionId, thresholds, reserve, updatedAtMs: Date.now(),
  }
}

const server = http.createServer((req, res) => {
  const send = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)) }
  if (req.method === 'GET' && req.url === '/health') return send(200, { ok: true, version: 'mock' })
  if (req.method === 'GET' && req.url === '/live') return send(200, live())
  if (req.method === 'POST' && req.url === '/session/start') { sessionId = sessionId || 'mock-session'; console.log('session start'); return send(200, { sessionId }) }
  if (req.method === 'POST' && req.url === '/session/stop') { console.log('session stop', sessionId); const id = sessionId; sessionId = null; return send(200, { sessionId: id }) }
  send(404, { error: 'not found' })
})

server.listen(41415, '127.0.0.1', () => console.log('mock relay on http://127.0.0.1:41415'))
```

- [ ] **Step 2: Run the simulator against it**

Terminal 1: `cd watch && npm run mock-relay`
Terminal 2: `cd watch && zeus dev`, choose the Cheetah 2 Ultra 480x480 round target (or the closest available round 480 target, such as the Cheetah Pro, if the simulator lacks the new device). In the simulator open the Workout app, add the K-Breathe data widget to a run page, start a run.

Expected: within 2 s the page shows VE climbing with the background colour moving through teal, blue, amber, orange, red; every 90 s the page goes grey with dashes for 10 s and the status reads `stale`; the mock relay terminal logs `session start` when the run starts and `session stop` when it ends. Stop the mock relay while the run is on: after 5 s the page reads `phone?`. Restart it: values return.

Record any deviation and the fix applied in `watch/README.md` under "Simulator notes".

- [ ] **Step 3: Commit**

```bash
git add watch/tools watch/README.md
git commit -m "watch: mock relay for the simulator"
```

---

### Task 8: Day-one checklist and README

**Files:**
- Create: `watch/DAY-ONE.md`, `watch/README.md` (extend if Task 7 created it)

- [ ] **Step 1: Write DAY-ONE.md**

Content, as a checklist with a place to record the result of each item:

1. Zepp app: Profile, Settings, About, tap the version seven times to enable developer mode; enable **Developer mode** and **Bridge mode** on the watch page in the Zepp app.
2. `zeus login`, create the app in the Zepp developer console, put the issued `appId` in `app.json`, `zeus preview` and scan the QR code with the Zepp app to install.
3. Start a Run on the watch, open data page settings, confirm **K-Breathe** appears as a widget. (Spec item 2.)
4. With the phone app running and the strap on: start a run with the widget on a page. Confirm in the phone app's Sessions tab that a session opened. Swipe away and back: values refresh within 2 s. End the run: the session closes with reason `watch`. (Spec items 3, 4, 5.)
5. Check `phone?` never appears while the phone is in a pocket for a 5-minute walk. If it does, exclude the Zepp app from battery optimisation and repeat. (Spec item 6.)
6. Heart rate: with the TymeHR paired to the watch, compare the MI page's implied HR against the watch's own HR data field for a minute; they must track each other. Then take the TymeHR off: the watch falls back to optical and MI keeps showing. (Spec item 4a.)
7. After the run, wait for Intervals.icu to receive it and for the phone notification "Run synced to Intervals.icu". Open the activity's custom chart with `tyme_minute_volume`. (Spec item 7.)

Fallbacks, copied from spec section 11: if item 3 fails (no `onInit`/`onDestroy`), move `session.start` into `onResume` guarded by a `started` flag and rely on the fallback stop; if item 4's fetch fails, try the phone's hotspot address in `RELAY_BASE`; if item 6 fails, drop MI from the page.

- [ ] **Step 2: Write README.md**

Cover: what the extension shows, how it talks to the phone, build and install commands, the message protocol table from Task 5, simulator notes, and a pointer to `DAY-ONE.md`.

- [ ] **Step 3: Commit**

```bash
git add watch/DAY-ONE.md watch/README.md
git commit -m "watch: day-one checklist and README"
```

---

## Self-review notes

- Spec 3.2 components: Tasks 5 and 6. Spec 4 poll on resume, stop on pause, 1 s cadence: Tasks 4, 5, 6. Spec 4.1 MI on the watch from the watch's HR: Tasks 2, 3, 6. Spec 5.1 start on `onInit`, stop on `onDestroy`: Task 6, with the fallback in Task 8. Spec 8 layout and states: Tasks 3 and 6. Spec 9 rows "Zepp app killed" and "relay unreachable": the 5 s `phone?` rule in Task 3. Spec 10 simulator with mock: Task 7. Spec 11 checklist: Task 8.
- Unverified platform assumptions are isolated to two places with written fallbacks: `DataWidget(BasePage(...))` in Task 6 and the lifecycle timing checked in Task 8.
