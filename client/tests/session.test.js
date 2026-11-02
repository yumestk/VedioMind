import test, { beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { AxiosError } from 'axios'

const saved = new Map([['user', '{"id":999}']])
globalThis.localStorage = { getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) }
const auth = await import('../src/api/session.js')
const { default: http } = await import('../src/api/http.js')
const response = (config, data, status = 200) => ({ config, data, status, statusText: '', headers: {} })
const fail = (config, status, code) => Promise.reject(new AxiosError('rejected', undefined, config, null, response(config, { code }, status)))
const result = (id = 1) => ({ accessToken: `jwt-${id}`, expiresAt: Date.now() + 600_000, userInfo: { id, username: `user${id}` } })
let refreshes
let activities
beforeEach(() => {
  auth.clearSession()
  saved.clear()
  refreshes = 0
  activities = []
  auth.authHttp.defaults.adapter = async config => {
    if (config.url === '/user/csrf') return response(config, { token: 'csrf-token' })
    if (config.url === '/user/refresh') {
      refreshes++
      activities.push(config.headers['X-Session-Activity'])
      await new Promise(resolve => setTimeout(resolve, 5))
      return response(config, result())
    }
    return response(config, null, 204)
  }
  http.defaults.adapter = async config => response(config, { ok: true })
})

test('coalesces simultaneous requests into one refresh and stores no credentials', async () => {
  const [a, b] = await Promise.all([auth.ensureAccess(), auth.ensureAccess()])
  assert.equal(a, 'jwt-1')
  assert.equal(b, a)
  assert.equal(refreshes, 1)
  assert.equal(saved.size, 0)
})
test('startup and background refresh do not extend inactivity', async () => {
  await auth.restoreSession()
  assert.deepEqual(activities, ['false'])
  await auth.ensureAccess(true)
  await auth.refreshSession(true)
  assert.deepEqual(activities, ['false', 'true'])
})
test('temporary storage failure preserves usable access and user', async () => {
  auth.acceptSession(result(), false)
  auth.authHttp.defaults.adapter = async config => config.url === '/user/csrf' ? response(config, { token: 'csrf' }) : fail(config, 503, 'AUTH_STORE_UNAVAILABLE')
  assert.equal(await auth.ensureAccess(true), 'jwt-1')
  await assert.rejects(auth.refreshSession(true))
  assert.equal(auth.currentUser.value.id, 1)
  assert.equal(auth.sessionStatus.value, 'unavailable')
})
test('failed logout does not claim revocation', async () => {
  auth.acceptSession(result())
  auth.authHttp.defaults.adapter = async config => fail(config, 503, 'AUTH_STORE_UNAVAILABLE')
  await assert.rejects(auth.logout())
  assert.equal(auth.currentUser.value.id, 1)
})
test('late refresh cannot restore identity after logout starts', async () => {
  const refreshing = auth.refreshSession()
  const rejection = assert.rejects(refreshing, /登录状态已变更/)
  const logout = auth.logout()
  await Promise.all([rejection, logout])
  assert.equal(auth.currentUser.value, null)
  assert.equal(auth.sessionStatus.value, 'guest')
})
test('a lost refresh response retries with the same request ID', async () => {
  const ids = []
  auth.authHttp.defaults.adapter = async config => {
    ids.push(config.headers['X-Refresh-Request'])
    if (ids.length === 1) throw new AxiosError('network interruption')
    return response(config, result())
  }
  await assert.rejects(auth.refreshSession())
  await auth.refreshSession()
  assert.equal(ids[0], ids[1])
  assert.equal(saved.size, 0)
})
test('expired long session clears identity while 503 does not', async () => {
  auth.acceptSession(result())
  auth.authHttp.defaults.adapter = async config => fail(config, 401, 'SESSION_EXPIRED')
  await assert.rejects(auth.refreshSession())
  assert.equal(auth.currentUser.value, null)
})
test('paid operations replay only after an explicit access expiry rejection', async () => {
  auth.acceptSession(result())
  let attempts = 0
  http.defaults.adapter = async config => {
    attempts++
    assert.ok(config.headers.Authorization.startsWith('Bearer '))
    assert.ok(config.headers['X-XSRF-TOKEN'])
    return attempts === 1 ? fail(config, 401, 'ACCESS_TOKEN_EXPIRED') : response(config, { ok: true })
  }
  await http.post('/media/1/assistant/dispatch', { content: 'question' })
  assert.equal(attempts, 2)
  assert.equal(refreshes, 1)
  for (const status of [500, 503, 429]) {
    attempts = 0
    http.defaults.adapter = async config => { attempts++; return fail(config, status, 'OTHER_ERROR') }
    await assert.rejects(http.post('/media/1/assistant/dispatch', {}))
    assert.equal(attempts, 1)
  }
})

test('late business responses cannot repopulate another account interface', async () => {
  auth.acceptSession(result())
  let release
  http.defaults.adapter = config => new Promise(resolve => { release = () => resolve(response(config, { private: true })) })
  const pending = http.get('/media')
  await new Promise(resolve => setImmediate(resolve))
  auth.clearSession()
  auth.acceptSession(result(2))
  const rejected = assert.rejects(pending, /登录状态已变更/)
  release()
  await rejected
})

test('refreshing the same profile preserves identity reference and does not retrigger view loading', async () => {
  auth.acceptSession(result())
  const original = auth.currentUser.value
  await auth.refreshSession(false)
  assert.equal(auth.currentUser.value, original)
})
test('repairs a rotated CSRF cookie without replaying past controller dispatch', async () => {
  auth.acceptSession(result())
  let attempts = 0
  http.defaults.adapter = async config => {
    attempts++
    return attempts === 1 ? fail(config, 403, 'CSRF_INVALID') : response(config, { ok: true })
  }
  await http.post('/media/uploads', {})
  assert.equal(attempts, 2)
  assert.equal(refreshes, 0)
})
