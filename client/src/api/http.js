import axios from 'axios'
import { csrf, renewCsrf, ensureAccess, refreshSession, sessionVersion } from './session.js'

const http = axios.create({ baseURL: import.meta.env?.VITE_API_BASE_URL || '/api', timeout: 30_000, withCredentials: true })
http.interceptors.request.use(async (config) => {
  const version = sessionVersion()
  const token = await ensureAccess(config.sessionActivity !== false)
  if (version !== sessionVersion()) throw new Error('登录状态已变更，请重新操作')
  config.identityVersion = version
  config.headers.Authorization = `Bearer ${token}`
  if (!['get', 'head', 'options'].includes(config.method)) config.headers['X-XSRF-TOKEN'] = await csrf()
  if (version !== sessionVersion()) throw new Error('登录状态已变更，请重新操作')
  return config
})
http.interceptors.response.use(response => {
  if (response.config.identityVersion !== sessionVersion()) throw new Error('登录状态已变更，请重新操作')
  return response
}, async (error) => {
  const config = error.config
  if (config?.identityVersion !== undefined && config.identityVersion !== sessionVersion()) throw new Error('登录状态已变更，请重新操作')
  if (config && !config.csrfRetried && error.response?.status === 403 && error.response.data?.code === 'CSRF_INVALID') {
    config.csrfRetried = true
    await renewCsrf()
    return http(config)
  }
  // Only an authentication rejection before dispatch can safely replay a paid operation.
  if (!config || config.authRetried || error.response?.status !== 401 || error.response.data?.code !== 'ACCESS_TOKEN_EXPIRED') throw error
  config.authRetried = true
  await refreshSession(config.sessionActivity !== false)
  return http(config)
})
export const errorMessage = (error, fallback = '请求失败') => {
  const data = error?.response?.data
  if (typeof data === 'string' && data.trim()) return data
  if (data?.detail) return data.detail
  if (data?.message) return data.message
  return error?.message || fallback
}
export default http
