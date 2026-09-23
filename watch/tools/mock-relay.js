import http from 'node:http'

const PORT = Number(process.env.MOCK_RELAY_PORT) || 41415

const started = Date.now()
let sessionId = null
const thresholds = { endurance: 73, vt1: 96, vt2: 112, topZ4: 130, vo2max: 180 }
const reserve = { restingBr: 12, maxBr: 55, restingHr: 60, maxHr: 190 }

function zoneFor(ve) {
  if (ve <= 0) return 0
  if (ve < thresholds.endurance) return 1
  if (ve < thresholds.vt1) return 2
  if (ve < thresholds.vt2) return 3
  if (ve < thresholds.topZ4) return 4
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

server.on('error', (e) => { console.error('mock relay failed to listen:', e.message); process.exit(1) })
server.listen(PORT, '127.0.0.1', () => console.log(`mock relay on http://127.0.0.1:${PORT}`))
