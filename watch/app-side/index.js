import { BaseSideService } from '@zeppos/zml/base-side'
import { RelayClient } from '../shared/relay-client.js'
import { Poller } from '../shared/poller.js'
import { POLL_MS, POLL_LEASE_MS } from '../shared/constants.js'
import { shouldRequestStart } from '../shared/session-latch.js'

// `fetch` here is the Zepp side-service fetch: fetch({ method, url, headers, body }) -> { status, body }.
const relay = new RelayClient((opts) => fetch(opts))

AppSideService(
  BaseSideService({
    state: {
      poller: null,
      // Latches "the page wants a session open". Set true as soon as a session.start request
      // arrives (before calling the relay, so it survives that call failing), false on
      // session.stop. Checked against the live payload's sessionId on every poll so a lost
      // session.start eventually gets retried instead of silently recording nothing.
      wantSession: false,
      // Timestamp of the last live.poll.start "lease" grant. pushLive stops the poller if this
      // goes stale (see POLL_LEASE_MS) so a lost live.poll.stop can't poll forever.
      lastArmedMs: null,
    },

    onInit() {
      this.state.poller = new Poller(() => this.pushLive(), POLL_MS)
    },

    async pushLive() {
      if (this.state.lastArmedMs !== null && Date.now() - this.state.lastArmedMs > POLL_LEASE_MS) {
        this.state.poller.stop()
        return
      }
      try {
        const payload = await relay.live()
        this.call({ method: 'live', params: { ok: true, payload } }).catch(() => {})
        if (shouldRequestStart(this.state.wantSession, payload)) {
          // Fire-and-forget retry; the phone's start is idempotent and we'll see the result on
          // the next poll's sessionId either way.
          relay.start().catch(() => {})
        }
      } catch (e) {
        this.call({ method: 'live', params: { ok: false, error: String(e && e.message ? e.message : e) } }).catch(() => {})
      }
    },

    onRequest(req, res) {
      const done = (p) => p.then((r) => res(null, r)).catch((e) => res(String(e && e.message ? e.message : e)))
      switch (req.method) {
        case 'session.start':
          this.state.wantSession = true
          return done(relay.start())
        case 'session.stop':
          this.state.wantSession = false
          return done(relay.stop())
        case 'relay.health': return done(relay.health())
        default: return res(`unknown method ${req.method}`)
      }
    },

    onCall(data) {
      if (!data || !data.method) return
      if (data.method === 'live.poll.start') {
        this.state.lastArmedMs = Date.now()
        this.state.poller.start()
      } else if (data.method === 'live.poll.stop') {
        this.state.poller.stop()
      }
    },

    onDestroy() {
      if (this.state.poller) this.state.poller.stop()
    },
  }),
)
