export class Poller {
  constructor(tick, intervalMs, timers = { setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval }) {
    this.tick = tick
    this.intervalMs = intervalMs
    this.timers = timers
    this.handle = null
    this.busy = false
  }

  get running() { return this.handle !== null }

  async _run() {
    if (this.busy) return
    this.busy = true
    try { await this.tick() } catch (_) { /* keep polling */ } finally { this.busy = false }
  }

  start() {
    if (this.handle !== null) return
    this.handle = this.timers.setInterval(() => { this._run() }, this.intervalMs)
    this._run()
  }

  stop() {
    this.busy = false
    if (this.handle === null) return
    this.timers.clearInterval(this.handle)
    this.handle = null
  }
}
