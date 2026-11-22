import assert from 'node:assert/strict'
import { once } from 'node:events'
import { readFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'

const [baseURL, samplePath, scenario, userId] = process.argv.slice(2)
const user = Number(userId)
const token = `${process.env.UPLOAD_EVAL_TOKEN}:${user}`
const client = resolve('../client')
const load = path => import(pathToFileURL(resolve(client, path)))
const storage = new Map()
globalThis.localStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
}
const { default: axios } = await load('node_modules/axios/index.js')
const { default: http } = await load('src/api/http.js')
const { acceptSession, authHttp } = await load('src/api/session.js')
const { getMediaUpload } = await load('src/api/media.js')
const { MultipartUploader } = await load('src/utils/multipartUpload.js')
http.defaults.baseURL = baseURL
authHttp.defaults.baseURL = baseURL
authHttp.defaults.headers.common.Authorization = `Bearer ${token}`
acceptSession({ userInfo: { id: user }, accessToken: token, expiresAt: Date.now() + 300_000 })

const file = new Blob([await readFile(samplePath)], { type: 'video/mp4' })
Object.assign(file, { name: 'upload-eval.mp4', lastModified: 0 })
const partBytes = 32 * 1024 * 1024
const timeoutMs = 5_000
const bytesPerSecond = 12 * 1024 * 1024
const attempts = []
const retries = []
const errors = []
const begin = performance.now()
let hangClosedMs = null
let hangStarted = null
let stage = 'upload'
let finalizeMs = null
const faults = createServer(request => {
  if (request.url === '/hang') {
    hangStarted = performance.now()
    request.socket.once('close', () => { hangClosedMs = performance.now() - hangStarted })
    // Drain the request body but never send a response.
    request.resume()
    return
  }
  request.socket.resetAndDestroy()
})
faults.listen(0, '127.0.0.1')
await once(faults, 'listening')
axios.interceptors.request.use(config => {
  const url = new URL(config.url)
  const part = Number(url.searchParams.get('partNumber'))
  const attempt = attempts.filter(item => item.part === part).length + 1
  attempts.push({ part, attempt, stage, elapsedMs: Math.round(performance.now() - begin) })
  if (scenario === 'A') {
    config.timeout = timeoutMs
    config.maxRate = bytesPerSecond
    if (attempt === 1 && (part === 2 || part === 4)) {
      config.url = `http://127.0.0.1:${faults.address().port}/${part === 2 ? 'hang' : 'reset'}`
    }
  }
  return config
})
axios.interceptors.response.use(response => response, error => {
  errors.push(error.code)
  throw error
})
const uploader = () => new MultipartUploader(file, user, progress => {
  if (progress.phase === 'retrying') retries.push(progress.retryAttempt)
  if (progress.phase === 'finalizing') finalizeMs = Math.round(performance.now() - begin)
})

try {
  let parts = []
  let interruptedMs = null
  let listPartsMs = null
  let sessionId
  let media
  const original = uploader()
  if (scenario === 'B') {
    await original.restoreOrInitiate()
    assert.equal(original.partSize, partBytes)
    sessionId = original.sessionId
    for (const part of [1, 2, 3]) await original.uploadPart(part)
    original.pause()
    interruptedMs = Math.round(performance.now() - begin)
    const resumed = uploader()
    const lookup = performance.now()
    const session = await getMediaUpload(sessionId)
    listPartsMs = Math.round(performance.now() - lookup)
    parts = session.uploadedParts
    assert.deepEqual(parts, [...original.parts.values()])
    assert.equal(parts.length, 3)
    stage = 'resume'
    media = await resumed.start()
    assert.equal(resumed.sessionId, sessionId)
    assert.deepEqual(attempts.filter(p => p.stage === 'resume').map(p => p.part).sort(), [4, 5])
  } else {
    media = await original.start()
    sessionId = original.sessionId
    assert.equal(original.partSize, partBytes)
    assert.deepEqual(attempts.map(p => p.part).sort(), scenario === 'A'
      ? [1, 2, 2, 3, 4, 4, 5] : [1, 2, 3, 4, 5])
  }
  if (scenario === 'A') {
    assert.deepEqual(retries, [1, 1])
    assert.equal(errors.length, 2)
    assert.ok(errors.includes('ETIMEDOUT'), `observed errors: ${errors.join(',')}`)
    // A reset while the body is being written can surface as either a reset or a broken pipe.
    assert.ok(errors.some(code => ['ECONNRESET', 'EPIPE'].includes(code)))
    assert.ok(hangClosedMs >= timeoutMs - 100, 'client must disconnect the unanswered PUT')
    const first = attempts.find(item => item.part === 2 && item.attempt === 1)
    const second = attempts.find(item => item.part === 2 && item.attempt === 2)
    assert.ok(second.elapsedMs - first.elapsedMs >= timeoutMs + 500)
  }
  console.log(JSON.stringify({
    mediaId: media.id, sessionId, partBytes, attempts, retries, errors,
    hangClosedMs, timeoutMs, bytesPerSecond, interruptedMs, listPartsMs, finalizeMs, parts,
    elapsedMs: Math.round(performance.now() - begin)
  }))
} finally {
  faults.close()
  faults.closeAllConnections()
}
