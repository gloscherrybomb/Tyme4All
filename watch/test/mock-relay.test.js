import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const scriptPath = path.join(__dirname, '..', 'tools', 'mock-relay.js')
const PORT = 41416
const BASE = `http://127.0.0.1:${PORT}`

function waitForReady(child) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('mock relay did not start in time')), 2000)
    child.stdout.on('data', (chunk) => {
      if (chunk.toString().includes('mock relay on')) {
        clearTimeout(timer)
        resolve()
      }
    })
    child.on('error', (e) => { clearTimeout(timer); reject(e) })
  })
}

test('mock relay serves health, live and session lifecycle', async () => {
  const child = spawn(process.execPath, [scriptPath], {
    env: { ...process.env, MOCK_RELAY_PORT: String(PORT) },
  })
  try {
    await waitForReady(child)
    const health = await fetch(`${BASE}/health`).then((r) => r.json())
    assert.deepEqual(health, { ok: true, version: 'mock' })

    const live = await fetch(`${BASE}/live`).then((r) => r.json())
    assert.deepEqual(
      Object.keys(live).sort(),
      ['ve', 'br', 'tv', 'ie', 'zone', 'batteryPct', 'status', 'sessionId', 'thresholds', 'reserve', 'updatedAtMs'].sort()
    )
    assert.ok(live.zone >= 0 && live.zone <= 5)
    assert.ok(live.status === 'connected' || live.status === 'stale')

    const start1 = await fetch(`${BASE}/session/start`, { method: 'POST' }).then((r) => r.json())
    assert.deepEqual(start1, { sessionId: 'mock-session' })
    const start2 = await fetch(`${BASE}/session/start`, { method: 'POST' }).then((r) => r.json())
    assert.deepEqual(start2, { sessionId: 'mock-session' })

    const stop1 = await fetch(`${BASE}/session/stop`, { method: 'POST' }).then((r) => r.json())
    assert.deepEqual(stop1, { sessionId: 'mock-session' })
    const stop2 = await fetch(`${BASE}/session/stop`, { method: 'POST' }).then((r) => r.json())
    assert.deepEqual(stop2, { sessionId: null })

    const notFound = await fetch(`${BASE}/nope`)
    assert.equal(notFound.status, 404)
  } finally {
    child.kill()
  }
})
