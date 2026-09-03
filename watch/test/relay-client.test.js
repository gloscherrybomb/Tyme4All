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
