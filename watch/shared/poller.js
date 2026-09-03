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
