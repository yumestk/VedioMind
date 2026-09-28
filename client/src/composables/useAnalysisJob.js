import { computed, onUnmounted, ref } from 'vue'
import { getActiveAnalysisJob, getAnalysisJob, submitAnalysis } from '../api/analysis'

const terminalStatuses = new Set(['SUCCEEDED', 'FAILED'])

export const useAnalysisJob = (mediaId, onSucceeded) => {
  const job = ref(null)
  const submitting = ref(false)
  const error = ref('')
  let timer = null
  let polling = false

  const running = computed(() => job.value && !terminalStatuses.has(job.value.status))

  const stopPolling = () => {
    if (timer) clearInterval(timer)
    timer = null
  }

  const applyJob = async (nextJob) => {
    job.value = nextJob
    if (nextJob.status === 'SUCCEEDED') {
      stopPolling()
      await onSucceeded?.()
    } else if (nextJob.status === 'FAILED') {
      stopPolling()
      error.value = nextJob.errorMessage || '分析任务失败'
    }
  }

  const poll = async () => {
    if (!job.value?.id || polling) return
    polling = true
    try {
      await applyJob(await getAnalysisJob(job.value.id))
    } catch (requestError) {
      console.error(requestError)
    } finally {
      polling = false
    }
  }

  const startPolling = () => {
    stopPolling()
    poll()
    timer = setInterval(poll, 2000)
  }

  const restore = async () => {
    try {
      const activeJob = await getActiveAnalysisJob(mediaId)
      if (!activeJob) return
      job.value = activeJob
      startPolling()
    } catch (requestError) {
      error.value = requestError?.response?.data?.detail || requestError.message || '任务状态恢复失败'
    }
  }

  const submit = async () => {
    submitting.value = true
    error.value = ''
    try {
      job.value = await submitAnalysis(mediaId)
      if (terminalStatuses.has(job.value.status)) {
        await applyJob(job.value)
      } else {
        startPolling()
      }
    } catch (requestError) {
      error.value = requestError?.response?.data?.detail || requestError.message || '任务提交失败'
      throw requestError
    } finally {
      submitting.value = false
    }
  }

  onUnmounted(stopPolling)

  return { job, running, submitting, error, restore, submit }
}
