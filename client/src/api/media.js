import axios from 'axios'
import http from './http'

export const listMedia = async (userId) => {
  const response = await http.get('/media', { params: { userId } })
  return response.data
}

export const getMediaDetail = async (mediaId, userId) => {
  const response = await http.get(`/media/${mediaId}`, { params: { userId } })
  return response.data
}

export const getMediaTranscript = async (mediaId, userId) => {
  const response = await http.get(`/media/${mediaId}/transcript`, { params: { userId } })
  return response.data
}

export const getMediaChapters = async (mediaId, userId) => {
  const response = await http.get(`/media/${mediaId}/chapters`, { params: { userId } })
  return response.data
}

export const listVideoConversations = async (mediaId, userId) => {
  const response = await http.get(`/media/${mediaId}/conversations`, { params: { userId } })
  return response.data
}

export const createVideoConversation = async (mediaId, userId) => {
  const response = await http.post(`/media/${mediaId}/conversations`, null, { params: { userId } })
  return response.data
}

export const getConversationMessages = async (conversationId, userId) => {
  const response = await http.get(`/conversations/${conversationId}/messages`, { params: { userId } })
  return response.data
}

export const sendConversationMessage = async (conversationId, userId, question) => {
  const response = await http.post(
    `/conversations/${conversationId}/messages`,
    { question },
    { params: { userId }, timeout: 5 * 60_000 }
  )
  return response.data
}

export const listVideoAgentRuns = async (mediaId, userId) => {
  const response = await http.get(`/media/${mediaId}/agent-runs`, { params: { userId } })
  return response.data
}

export const createVideoAgentRun = async (mediaId, userId, goal) => {
  const response = await http.post(
    `/media/${mediaId}/agent-runs`,
    { goal },
    { params: { userId } }
  )
  return response.data
}

export const getVideoAgentRun = async (runId, userId) => {
  const response = await http.get(`/agent-runs/${runId}`, { params: { userId } })
  return response.data
}

export const initiateMediaUpload = async (file, userId) => {
  const response = await http.post('/media/uploads', {
    filename: file.name,
    mimeType: file.type || 'application/octet-stream',
    fileSize: file.size
  }, { params: { userId } })
  return response.data
}

export const getMediaUpload = async (sessionId, userId) => {
  const response = await http.get(`/media/uploads/${sessionId}`, { params: { userId } })
  return response.data
}

export const getMediaUploadPartUrl = async (sessionId, partNumber, userId) => {
  const response = await http.post(
    `/media/uploads/${sessionId}/parts/${partNumber}/url`,
    null,
    { params: { userId } }
  )
  return response.data
}

export const putMediaUploadPart = async (url, part, signal, onProgress) => {
  const response = await axios.put(url, part, {
    signal,
    headers: { 'Content-Type': 'application/octet-stream' },
    onUploadProgress: ({ loaded }) => onProgress?.(loaded)
  })
  const etag = response.headers.etag
  if (!etag) throw new Error('对象存储未返回 ETag，请检查 MinIO CORS 配置')
  return etag
}

export const completeMediaUpload = async (sessionId, userId, parts) => {
  const response = await http.post(
    `/media/uploads/${sessionId}/complete`,
    { parts },
    { params: { userId }, timeout: 2 * 60_000 }
  )
  return response.data
}

export const abortMediaUpload = async (sessionId, userId) => {
  await http.delete(`/media/uploads/${sessionId}`, { params: { userId } })
}

export const importMediaUrl = async (url, userId) => {
  const response = await http.post('/media/import-url', { url }, {
    params: { userId },
    timeout: 2 * 60_000
  })
  return response.data
}

export const deleteMedia = async (mediaId, userId) => {
  await http.delete(`/media/${mediaId}`, { params: { userId } })
}
