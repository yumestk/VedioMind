const RETRY_BASE_MS = 800
const RETRY_CEILING_MS = 8_000

const errorStatus = error => error?.response?.status ?? error?.status

export const isRetriableUploadError = error => {
  if (error?.code === 'ERR_CANCELED') return false
  const status = errorStatus(error)
  return status == null || status === 408 || status === 429 || status >= 500
}

export const uploadRetryDelay = (attempt, random = Math.random) => {
  const base = Math.min(RETRY_CEILING_MS, RETRY_BASE_MS * 2 ** Math.max(0, attempt - 1))
  return Math.round(base * (0.75 + random() * 0.5))
}

export const shouldDiscardUploadSession = error => {
  const status = errorStatus(error)
  if (![400, 404, 410].includes(status)) return false
  const data = error?.response?.data
  const detail = typeof data === 'string'
    ? data
    : data?.detail || data?.message || error?.message || ''
  return /does not exist|expired|invalid.+session|不存在|已过期/i.test(detail)
}
