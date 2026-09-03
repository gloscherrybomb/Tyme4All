import { test } from 'node:test'
import assert from 'node:assert/strict'
import { shouldRequestStart } from '../shared/session-latch.js'

test('wantSession false is always false, regardless of payload', () => {
  assert.equal(shouldRequestStart(false, { sessionId: null }), false)
  assert.equal(shouldRequestStart(false, { sessionId: 's1' }), false)
  assert.equal(shouldRequestStart(false, null), false)
})

test('wantSession true and sessionId null means retry', () => {
  assert.equal(shouldRequestStart(true, { sessionId: null }), true)
})

test('wantSession true and sessionId missing from the payload means retry', () => {
  assert.equal(shouldRequestStart(true, {}), true)
})

test('wantSession true and sessionId present means no retry', () => {
  assert.equal(shouldRequestStart(true, { sessionId: 's1' }), false)
})

test('missing or not-ok payload never triggers a retry', () => {
  assert.equal(shouldRequestStart(true, null), false)
  assert.equal(shouldRequestStart(true, undefined), false)
  assert.equal(shouldRequestStart(true, { ok: false }), false)
})
