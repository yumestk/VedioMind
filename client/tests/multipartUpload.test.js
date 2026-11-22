import assert from 'node:assert/strict'
import { once } from 'node:events'
import { createServer } from 'node:http'
import test from 'node:test'
import axios from 'axios'
import http from '../src/api/http.js'
import { acceptSession, authHttp } from '../src/api/session.js'
import { MultipartUploader } from '../src/utils/multipartUpload.js'

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
  assert.equal(isRetriableUploadError({ code: 'ECONNABORTED' }), true)
  assert.equal(isRetriableUploadError({ code: 'ETIMEDOUT' }), true)
  assert.equal(isRetriableUploadError(new DOMException('cancelled', 'AbortError')), false)
  assert.equal(isRetriableUploadError({ name: 'AbortError', cause: { name: 'TimeoutError' } }), true)
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

async function withPartUploader(t, handlePut) {
  const server = createServer(handlePut).listen(0, '127.0.0.1')
  await once(server, 'listening')
  const url = `http://127.0.0.1:${server.address().port}/part`
  const originalAdapter = http.defaults.adapter
  const originalAuthAdapter = authHttp.defaults.adapter
  http.defaults.adapter = async config => ({ config, data: { url }, status: 200, headers: {} })
  authHttp.defaults.adapter = async config => ({ config, data: { token: 'csrf' }, status: 200, headers: {} })
  const interceptor = axios.interceptors.request.use(config => {
    assert.equal(config.timeout, 300_000)
    config.timeout = 100
    return config
  })
  acceptSession({ userInfo: { id: 101 }, accessToken: 'local-test-token', expiresAt: Date.now() + 60_000 })
  t.after(() => {
    server.close()
    server.closeAllConnections()
    http.defaults.adapter = originalAdapter
    authHttp.defaults.adapter = originalAuthAdapter
    axios.interceptors.request.eject(interceptor)
  })
  const progress = []
  const uploader = new MultipartUploader(new Blob(['part']), 101, update => progress.push(update))
  uploader.sessionId = 'local-test-session'
  uploader.partSize = 32
  return { uploader, progress }
}

test('a hanging PUT times out, backs off, and succeeds on retry', async t => {
  const puts = []
  const { uploader, progress } = await withPartUploader(t, (req, res) => {
    req.resume()
    puts.push(performance.now())
    if (puts.length === 2) res.writeHead(200, { ETag: 'part-etag' }).end()
  })
  await uploader.uploadPart(1)
  assert.equal(puts.length, 2)
  assert.ok(puts[1] - puts[0] >= 550, 'expected exponential backoff before retry')
  assert.equal(uploader.parts.get(1).etag, 'part-etag')
  assert.deepEqual(progress.filter(p => p.phase === 'retrying').map(p => p.retryAttempt), [1])
})

test('a hanging PUT rejects after four attempts', async t => {
  let puts = 0
  const { uploader, progress } = await withPartUploader(t, req => { req.resume(); puts++ })
  await assert.rejects(uploader.uploadPart(1), { code: 'ETIMEDOUT' })
  assert.equal(puts, 4)
  assert.deepEqual(progress.filter(p => p.phase === 'retrying').map(p => p.retryAttempt), [1, 2, 3])
})

test('user cancellation aborts the active PUT without retrying', async t => {
  let started
  const firstPut = new Promise(resolve => { started = resolve })
  let puts = 0
  const { uploader, progress } = await withPartUploader(t, req => {
    req.resume()
    puts++
    started()
  })
  const upload = uploader.uploadPart(1)
  await firstPut
  globalThis.localStorage = { removeItem() {} }
  t.after(() => { delete globalThis.localStorage })
  await uploader.cancel()
  await upload
  assert.equal(puts, 1)
  assert.equal(progress.some(p => p.phase === 'retrying'), false)
})
