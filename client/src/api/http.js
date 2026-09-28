import axios from 'axios'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:9090',
  timeout: 30_000
})

export const errorMessage = (error, fallback = '请求失败') => {
  const data = error?.response?.data
  if (typeof data === 'string' && data.trim()) return data
  if (data?.detail) return data.detail
  if (data?.message) return data.message
  return error?.message || fallback
}

export default http
