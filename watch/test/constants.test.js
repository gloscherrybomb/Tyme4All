import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ZONE_COLORS, ZONE_NAMES, RELAY_BASE } from '../shared/constants.js'

test('six zone colours and names, index 0 is no zone', () => {
  assert.equal(ZONE_COLORS.length, 6)
  assert.equal(ZONE_NAMES.length, 6)
  assert.equal(ZONE_NAMES[0], '--')
  assert.equal(ZONE_COLORS[5], 0xc62828)
})

test('relay is loopback on the agreed port', () => {
  assert.equal(RELAY_BASE, 'http://127.0.0.1:41415')
})
