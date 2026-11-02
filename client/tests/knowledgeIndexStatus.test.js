import test from 'node:test'
import assert from 'node:assert/strict'
import { isIndexRunning, indexStatusText } from '../src/utils/knowledgeIndexStatus.js'

test('quota wait keeps preparation pending rather than reporting missing evidence', () => {
  const job = { status: 'WAITING_FOR_QUOTA', retryCount: 0 }
  assert.equal(isIndexRunning(job), true)
  assert.match(indexStatusText(job), /获得额度/)
})
test('success requires an explicit resend and failure exposes its cause', () => {
  assert.equal(isIndexRunning({ status: 'SUCCEEDED' }), false)
  assert.match(indexStatusText({ status: 'SUCCEEDED' }), /重新发送/)
  assert.equal(isIndexRunning({ status: 'FAILED' }), false)
  assert.equal(indexStatusText({ status: 'FAILED', errorMessage: '供应商不可用' }), '供应商不可用')
  assert.equal(isIndexRunning(null), false)
})
