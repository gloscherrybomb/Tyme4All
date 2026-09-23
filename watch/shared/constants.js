export const RELAY_BASE = 'http://127.0.0.1:41415'
export const POLL_MS = 1000
export const STALE_MS = 5000
// How long a `live.poll.start` lease is valid for before the side service stops polling on
// its own. The page re-arms it from its 1s render timer, so a lost `live.poll.stop` still
// self-heals within this window instead of polling forever.
export const POLL_LEASE_MS = 15000

export const ZONE_COLORS = [0x424242, 0x4db6ac, 0x0277bd, 0xf57f17, 0xef6c00, 0xc62828]
export const ZONE_NAMES = ['--', 'Z1', 'Z2', 'Z3', 'Z4', 'Z5']
export const COLOR_TEXT = 0xffffff
export const COLOR_MUTED = 0xbdbdbd
export const COLOR_OK = 0x66bb6a
export const COLOR_WARN = 0xffb300
export const COLOR_GREY = 0x9e9e9e

export const SCREEN = { w: 480, h: 480 }
