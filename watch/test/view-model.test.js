import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildViewModel } from '../shared/view-model.js'
import { ZONE_COLORS, COLOR_GREY, COLOR_OK, COLOR_WARN } from '../shared/constants.js'

const payload = {
  ve: 62.4, br: 24.6, tv: 2.53, ie: 1.0, zone: 1, batteryPct: 77, status: 'connected', sessionId: 's',
  thresholds: { endurance: 73, vt1: 96, vt2: 112, topZ4: 130, vo2max: 180 },
  reserve: { restingBr: 12, maxBr: 55, restingHr: 60, maxHr: 190 },
  updatedAtMs: 1000,
}

test('no payload yet means phone unreachable', () => {
  const vm = buildViewModel({ payload: null, receivedAtMs: null, hr: null }, 10_000)
  assert.equal(vm.status, 'phone?')
  assert.equal(vm.bg, COLOR_GREY)
  assert.equal(vm.ve, '--')
  assert.equal(vm.mi, '--')
})

test('old payload means phone unreachable', () => {
  const vm = buildViewModel({ payload, receivedAtMs: 1_000, hr: 125 }, 6_001)
  assert.equal(vm.status, 'phone?')
  assert.equal(vm.bg, COLOR_GREY)
})

test('connected payload renders values and zone colour', () => {
  const vm = buildViewModel({ payload, receivedAtMs: 5_000, hr: 125 }, 5_500)
  assert.equal(vm.status, 'connected')
  assert.equal(vm.statusColor, COLOR_OK)
  assert.equal(vm.bg, ZONE_COLORS[1])
  assert.equal(vm.ve, '62')
  assert.equal(vm.br, '25')
  assert.equal(vm.tv, '2.5')
  assert.equal(vm.zoneName, 'Z1')
  assert.equal(vm.battery, '77%')
  // %BRR = (24.6-12)/43*100 = 29.30; %HRR = 50 -> MI 58.6 -> '59'
  assert.equal(vm.mi, '59')
})

test('no heart rate gives dashes for MI only', () => {
  const vm = buildViewModel({ payload, receivedAtMs: 5_000, hr: null }, 5_500)
  assert.equal(vm.mi, '--')
  assert.equal(vm.ve, '62')
})

test('stale and disconnected go grey with dashes', () => {
  const stale = buildViewModel({ payload: { ...payload, status: 'stale', ve: null, br: null, tv: null, zone: 0 }, receivedAtMs: 5_000, hr: 120 }, 5_500)
  assert.equal(stale.status, 'stale')
  assert.equal(stale.statusColor, COLOR_WARN)
  assert.equal(stale.bg, COLOR_GREY)
  assert.equal(stale.ve, '--')
  const off = buildViewModel({ payload: { ...payload, status: 'disconnected', ve: null, br: null, tv: null, zone: 0, batteryPct: null }, receivedAtMs: 5_000, hr: 120 }, 5_500)
  assert.equal(off.battery, '')
  assert.equal(off.statusColor, COLOR_GREY)
})
