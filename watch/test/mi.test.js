import { test } from 'node:test'
import assert from 'node:assert/strict'
import { percentHrr, percentBrr, mobilizationIndex } from '../shared/mi.js'

const r = { restingBr: 12, maxBr: 55, restingHr: 60, maxHr: 190 }

test('percent hrr', () => {
  assert.equal(percentHrr(125, r), 50)
  assert.equal(percentHrr(50, r), 0)
  assert.equal(percentHrr(null, r), null)
  assert.equal(percentHrr(0, r), null)
})

test('percent brr', () => {
  assert.equal(percentBrr(33.5, r), 50)
  assert.equal(percentBrr(10, r), 0)
  assert.equal(percentBrr(undefined, r), null)
})

test('mobilization index matches the phone formula', () => {
  assert.equal(mobilizationIndex(33.5, 125, r), 100)
  assert.equal(mobilizationIndex(33.5, 60.5, r), 0)      // %HRR below 1
  assert.equal(mobilizationIndex(null, 125, r), null)
  assert.equal(mobilizationIndex(33.5, null, r), null)
  assert.equal(mobilizationIndex(30, 120, { ...r, maxBr: 12 }), null)
})
