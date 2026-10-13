import assert from 'node:assert/strict'
import test from 'node:test'

import {
  isRetriableUploadError,
  shouldDiscardUploadSession,
  uploadRetryDelay
} from '../src/utils/uploadRetry.js'

test('retries only transient upload failures', () => {
  assert.equal(isRetriableUploadError(new Error('network down')), true)
  assert.equal(isRetriableUploadError({ response: { status: 408 } }), true)
  assert.equal(isRetriableUploadError({ response: { status: 429 } }), true)
  assert.equal(isRetriableUploadError({ response: { status: 503 } }), true)
  assert.equal(isRetriableUploadError({ response: { status: 403 } }), false)
  assert.equal(isRetriableUploadError({ code: 'ERR_CANCELED' }), false)
})

test('uses capped exponential backoff with jitter', () => {
  assert.equal(uploadRetryDelay(1, () => 0), 600)
  assert.equal(uploadRetryDelay(1, () => 1), 1_000)
  assert.equal(uploadRetryDelay(5, () => 0.5), 8_000)
})

test('keeps resumable sessions for transient failures', () => {
  assert.equal(shouldDiscardUploadSession({ response: { status: 503 } }), false)
  assert.equal(shouldDiscardUploadSession(new Error('network down')), false)
  assert.equal(shouldDiscardUploadSession({
    response: { status: 400, data: { detail: 'Upload session does not exist' } }
  }), true)
})
