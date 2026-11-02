import axios from 'axios'
import { ref } from 'vue'

// Credentials stay in memory or HttpOnly cookies. This client never attaches a Bearer token.
export const authHttp = axios.create({ baseURL: import.meta.env?.VITE_API_BASE_URL || '/api', timeout: 30_000, withCredentials: true })
export const currentUser = ref(null)
export const sessionStatus = ref('restoring')
let accessToken = null
let expiresAt = 0
let activeRenewedAt = 0
let activeAttemptAt = 0
let csrfToken = null
let csrfPending = null
let refreshPending = null
let version = 0
const withLock = task => globalThis.navigator?.locks ? navigator.locks.request('vm.refresh', task) : task()
export const sessionVersion = () => version
const pendingKey = 'vm.refresh-request'
const storage = globalThis.localStorage
storage?.removeItem('user')
const channel = typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('vm.auth') : null

export const clearSession = () => {
  version++
  accessToken = null
  expiresAt = 0
  activeRenewedAt = 0
  activeAttemptAt = 0
  currentUser.value = null
  sessionStatus.value = 'guest'
}
export const acceptSession = (result, active = true) => {
  accessToken = result.accessToken
  expiresAt = result.expiresAt
  if (currentUser.value?.id === result.userInfo.id) Object.assign(currentUser.value, result.userInfo)
  else currentUser.value = result.userInfo
  sessionStatus.value = 'authenticated'
  if (active) activeRenewedAt = Date.now()
}
export const csrf = async () => {
  if (csrfToken) return csrfToken
  if (!csrfPending) csrfPending = authHttp.get('/user/csrf').then(({ data }) => { csrfToken = data.token; return csrfToken }).finally(() => { csrfPending = null })
  return csrfPending
}
export const renewCsrf = () => { csrfToken = null; return csrf() }
export const authPost = async (path, data, config = {}) => {
  try {
    return await authHttp.post(path, data, { ...config, headers: { ...config.headers, 'X-XSRF-TOKEN': await csrf() } })
  } catch (error) {
    // A CSRF rejection occurs before any controller side effects; retry exactly once.
    if (error.response?.status !== 403 || error.response.data?.code !== 'CSRF_INVALID') throw error
    await renewCsrf()
    return authHttp.post(path, data, { ...config, headers: { ...config.headers, 'X-XSRF-TOKEN': await csrf() } })
  }
}
const refreshId = () => {
  try {
    const saved = JSON.parse(storage?.getItem(pendingKey) || 'null')
    if (saved && Date.now() - saved.at < 25_000) return saved.id
  } catch { /* A corrupt local retry marker is disposable. */ }
  const id = globalThis.crypto.randomUUID()
  storage?.setItem(pendingKey, JSON.stringify({ id, at: Date.now() }))
  return id
}
export const refreshSession = (active = false) => {
  if (refreshPending) return refreshPending
  const expected = version
  const perform = async () => {
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const { data } = await authPost('/user/refresh', null, { headers: { 'X-Refresh-Request': refreshId(), 'X-Session-Activity': String(active) } })
        storage?.removeItem(pendingKey)
        if (version !== expected) throw new Error('登录状态已变更，请重试')
        acceptSession(data, active)
        return accessToken
      } catch (error) {
        if (version !== expected) throw error
        if (error.response?.status === 409 && attempt === 0) { storage?.removeItem(pendingKey); continue }
        if (error.response?.status === 401) { storage?.removeItem(pendingKey); clearSession() }
        else sessionStatus.value = 'unavailable'
        throw error
      }
    }
  }
  // Serialize tabs as well as requests. No token or password is shared through browser storage.
  refreshPending = withLock(perform).finally(() => { refreshPending = null })
  return refreshPending
}
export const ensureAccess = async (active = true) => {
  if (accessToken && expiresAt > Date.now() + 5_000) {
    const token = accessToken
    if (active && Date.now() - activeRenewedAt >= 5 * 60_000 && Date.now() - activeAttemptAt >= 15_000) {
      // Optional activity renewal must never delay a request with a usable credential.
      activeAttemptAt = Date.now()
      refreshSession(true).catch(() => {})
    }
    return token
  }
  return refreshSession(active)
}
export const restoreSession = async () => {
  try { await refreshSession(false) }
  catch (error) { if (error.response?.status !== 401) throw error }
}
export const login = async (credentials) => {
  const expected = ++version
  return withLock(async () => {
    const { data } = await authPost('/user/login', credentials)
    if (version !== expected) throw new Error('登录状态已变更，请重试')
    storage?.removeItem(pendingKey)
    csrfToken = null
    acceptSession(data)
    channel?.postMessage('changed')
    return data
  })
}
export const register = async (account) => (await authPost('/user/register', account)).data
export const logout = async () => {
  version++
  // Do not report success if persistence failed. Serialize with rotation in other tabs.
  const perform = async () => {
    await authPost('/user/logout')
    storage?.removeItem(pendingKey)
    csrfToken = null
    clearSession()
    channel?.postMessage('logout')
  }
  if (refreshPending) await refreshPending.catch(() => {})
  return withLock(perform)
}
if (channel) channel.onmessage = ({ data }) => {
  clearSession()
  if (data === 'changed') restoreSession().catch(() => {})
}
