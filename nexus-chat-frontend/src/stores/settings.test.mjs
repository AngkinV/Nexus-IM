import test from 'node:test'
import assert from 'node:assert/strict'
import { to12Hour } from './settings.js'

test('to12Hour converts 24-hour tokens', () => {
  assert.equal(to12Hour('HH:mm'), 'h:mm A')
  assert.equal(to12Hour('HH:mm:ss'), 'h:mm:ss A')
})

test('to12Hour leaves date tokens alone', () => {
  assert.equal(to12Hour('MM-DD HH:mm'), 'MM-DD h:mm A')
  assert.equal(to12Hour('YYYY-MM-DD'), 'YYYY-MM-DD')
})

test('to12Hour keeps the format unchanged when there is no time', () => {
  assert.equal(to12Hour('ddd'), 'ddd')
  assert.equal(to12Hour('MMM D'), 'MMM D')
})
