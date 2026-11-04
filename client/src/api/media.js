import axios from 'axios'
import http from './http'

export const listMedia = async () => {
  const response = await http.get('/media')
  return response.data
}

export const getMediaDetail = async (mediaId) => {
  const response = await http.get(`/media/${mediaId}`)
  return response.data
}

export const getMediaTranscript = async (mediaId) => {
  const response = await http.get(`/media/${mediaId}/transcript`)
  return response.data
}

export const getMediaChapters = async (mediaId) => {
  const response = await http.get(`/media/${mediaId}/chapters`)
  return response.data
}

export const listVideoConversations = async (mediaId, courseId) => {
  const response = await http.get(`/media/${mediaId}/conversations`, { params: { courseId } })
  return response.data
}

export const getConversationMessages = async (conversationId) => {
  const response = await http.get(`/conversations/${conversationId}/messages`)
  return response.data
}

export const dispatchAssistantMessage = async (mediaId, request) => {
  const response = await http.post(
    `/media/${mediaId}/assistant/dispatch`,
    request,
    { timeout: 5 * 60_000 }
  )
  return response.data
}

export const getVideoAgentRun = async (runId) => {
  const response = await http.get(`/agent-runs/${runId}`, { sessionActivity: false })
  return response.data
}

export const initiateMediaUpload = async (file) => {
  const response = await http.post('/media/uploads', {
    filename: file.name,
    mimeType: file.type || 'application/octet-stream',
    fileSize: file.size
  })
  return response.data
}

export const getMediaUpload = async (sessionId) => {
  const response = await http.get(`/media/uploads/${sessionId}`)
  return response.data
}

export const getMediaUploadPartUrl = async (sessionId, partNumber) => {
  const response = await http.post(
    `/media/uploads/${sessionId}/parts/${partNumber}/url`,
    null,
    {}
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

export const completeMediaUpload = async (sessionId, parts) => {
  const response = await http.post(
    `/media/uploads/${sessionId}/complete`,
    { parts },
    { timeout: 2 * 60_000 }
  )
  return response.data
}

export const abortMediaUpload = async (sessionId) => {
  await http.delete(`/media/uploads/${sessionId}`)
}

export const importMediaUrl = async (url) => {
  const response = await http.post('/media/import-url', { url }, {
    timeout: 2 * 60_000
  })
  return response.data
}

export const deleteMedia = async (mediaId) => {
  await http.delete(`/media/${mediaId}`)
}
