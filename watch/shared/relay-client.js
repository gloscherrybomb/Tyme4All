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

  async _call(method, path) {
    const res = await this.fetch({ method, url: this.base + path, headers: { 'Content-Type': 'application/json' } })
    if (!res || res.status < 200 || res.status >= 300) throw new Error(`relay HTTP ${res ? res.status : 'none'}`)
    return readBody(res)
  }

  health() { return this._call('GET', '/health') }
  live() { return this._call('GET', '/live') }
  start() { return this._call('POST', '/session/start') }
  stop() { return this._call('POST', '/session/stop') }
}
