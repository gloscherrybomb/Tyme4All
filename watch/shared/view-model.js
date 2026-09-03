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
