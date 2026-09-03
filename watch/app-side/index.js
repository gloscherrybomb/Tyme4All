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
