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
