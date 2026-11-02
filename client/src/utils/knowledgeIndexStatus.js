export const isIndexRunning = job => !!job && !['SUCCEEDED', 'FAILED'].includes(job.status)
export const indexStatusText = job => ({
  QUEUED: '问答资料已排队，正在等待准备',
  PREPARING: '正在准备视频问答资料',
  BUILDING_KNOWLEDGE_INDEX: '正在准备视频问答资料',
  WAITING_FOR_QUOTA: 'AI 服务繁忙，问答资料将在获得额度后继续准备',
  RETRYING: '问答资料准备遇到临时问题，正在重试',
  SUCCEEDED: '问答资料已就绪，请重新发送问题',
  FAILED: job?.errorMessage || '问答资料准备失败，请点击重试'
})[job?.status] || ''
