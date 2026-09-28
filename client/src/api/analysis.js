import http from './http'

export const submitAnalysis = async (mediaId) => {
  const response = await http.post(`/analysis/media/${mediaId}`)
  return response.data
}

export const getAnalysisJob = async (jobId) => {
  const response = await http.get(`/analysis/jobs/${jobId}`)
  return response.data
}

export const getActiveAnalysisJob = async (mediaId) => {
  const response = await http.get(`/analysis/media/${mediaId}/active-job`)
  return response.status === 204 ? null : response.data
}
