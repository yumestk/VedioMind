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

export const uploadMedia = async (file, userId, onProgress) => {
  const body = new FormData()
  body.append('file', file)
  body.append('userId', userId)
  const response = await http.post('/media/upload', body, {
    timeout: 30 * 60_000,
    onUploadProgress: ({ loaded, total }) => onProgress?.(total ? Math.round((loaded / total) * 100) : 0)
  })
  return response.data
}

export const importMediaUrl = async (url, userId) => {
  const body = new FormData()
  body.append('url', url)
  body.append('userId', userId)
  const response = await http.post('/media/upload-url', body, { timeout: 20 * 60_000 })
  return response.data
}

export const deleteMedia = async (mediaId, userId) => {
  await http.delete(`/media/${mediaId}`, { params: { userId } })
}
