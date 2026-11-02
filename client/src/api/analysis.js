import http from './http'

export const submitAnalysis = async (mediaId) => {
  const response = await http.post(`/analysis/media/${mediaId}`)
  return response.data
}

export const getAnalysisJob = async (jobId) => {
  const response = await http.get(`/analysis/jobs/${jobId}`, { sessionActivity: false })
  return response.data
}

export const getActiveAnalysisJob = async (mediaId) => {
  const response = await http.get(`/analysis/media/${mediaId}/active-job`, { sessionActivity: false })
  return response.status === 204 ? null : response.data
}

export const prepareKnowledgeIndex = async (mediaId) => {
  const response = await http.post(`/media/${mediaId}/knowledge-index`, null)
  return response.status === 204 ? null : response.data
}

export const getKnowledgeIndexJob = async (mediaId, jobId) => {
  const response = await http.get(`/media/${mediaId}/knowledge-index/jobs/${jobId}`, { sessionActivity: false })
  return response.data
}

export const getLatestKnowledgeIndexJob = async (mediaId) => {
  const response = await http.get(`/media/${mediaId}/knowledge-index/latest-job`, { sessionActivity: false })
  return response.status === 204 ? null : response.data
}
