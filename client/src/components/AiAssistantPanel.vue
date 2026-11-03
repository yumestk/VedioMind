<template>
  <section class="assistant-panel">
    <ConversationPanel
      ref="conversationPanel"
      :media-id="mediaId"
      :user-id="userId"
      :has-transcript="hasTranscript"
      :analysis-mode="analysisMode"
      :busy="submitting"
      @seek="$emit('seek', $event)"
      @followup="submitFollowup"
    />

    <form v-if="hasTranscript" class="assistant-composer" @submit.prevent="submit">
      <div v-if="routeInfo" class="route-info">
        <span>{{ routeInfo.executionMode === 'DEEP' ? '✦ 深度任务' : '? 快速回答' }}</span>
        {{ routeInfo.reason }}
      </div>
      <div v-if="indexJob" class="route-info" role="status">
        {{ indexStatusText(indexJob) }}
        <button v-if="indexJob.status === 'FAILED'" type="button" @click="retryIndex" :disabled="submitting">重试准备</button>
      </div>
      <textarea
        v-model="content"
        maxlength="500"
        :disabled="submitting"
        :placeholder="placeholder"
      ></textarea>
      <div class="composer-actions">
        <select v-model="requestedMode" :disabled="submitting" aria-label="选择助手执行模式">
          <option value="AUTO">自动选择</option>
          <option value="QUICK">快速回答</option>
          <option v-if="isStudyMode" value="DEEP">深度分析</option>
        </select>
        <span v-if="error" class="composer-error">{{ error }}</span>
        <span v-else class="composer-hint">{{ modeHint }}</span>
        <button class="primary-button compact" :disabled="submitting || !content.trim()">
          {{ submitting ? '处理中…' : '发送' }}
        </button>
      </div>
    </form>
  </section>
</template>

<script setup>
import { computed, ref, watch, onUnmounted } from 'vue'
import { prepareKnowledgeIndex, getKnowledgeIndexJob, getLatestKnowledgeIndexJob } from '../api/analysis'
import { isIndexRunning, indexStatusText } from '../utils/knowledgeIndexStatus'
import { dispatchAssistantMessage } from '../api/media'
import { errorMessage } from '../api/http'
import ConversationPanel from './ConversationPanel.vue'

const props = defineProps({
  mediaId: { type: Number, required: true },
  userId: { type: Number, required: true },
  hasTranscript: { type: Boolean, default: false },
  analysisMode: { type: String, default: '' }
})

defineEmits(['seek'])

const conversationPanel = ref(null)
const content = ref('')
const requestedMode = ref('AUTO')
const submitting = ref(false)
const routeInfo = ref(null)
const error = ref('')
const indexJob = ref(null)
let indexTimer = null
let indexPolling = false
const stopIndexPolling = () => { clearInterval(indexTimer); indexTimer = null }
const followIndexJob = job => {
  stopIndexPolling()
  indexJob.value = job
  const mediaId = props.mediaId
  const userId = props.userId
  if (!isIndexRunning(job)) return
  indexTimer = setInterval(async () => {
    if (indexPolling) return
    indexPolling = true
    try {
      const next = await getKnowledgeIndexJob(mediaId, job.id)
      if (mediaId !== props.mediaId || indexJob.value?.id !== job.id) return
      indexJob.value = next
      if (!isIndexRunning(next)) stopIndexPolling()
    } catch (requestError) {
      if (mediaId === props.mediaId) {
        error.value = errorMessage(requestError, '问答资料状态查询失败')
        if ([403, 404].includes(requestError?.response?.status)) stopIndexPolling()
      }
    } finally { indexPolling = false }
  }, 2000)
}
const retryIndex = async () => {
  submitting.value = true
  error.value = ''
  const mediaId = props.mediaId
  try {
    const job = await prepareKnowledgeIndex(mediaId)
    if (mediaId === props.mediaId) {
      followIndexJob(job || { status: 'SUCCEEDED' })
    }
  } catch (requestError) {
    error.value = errorMessage(requestError, '问答资料准备失败')
  } finally { submitting.value = false }
}
onUnmounted(stopIndexPolling)
const isStudyMode = computed(() => props.analysisMode === 'STUDY')
const placeholder = computed(() => isStudyMode.value
  ? '输入学习主题，我会整理简明笔记；自测题可按需生成'
  : '例如：视频如何解释这个问题？有哪些具体建议？')
const modeHint = computed(() => ({
  AUTO: '自动判断快速回答或深度任务',
  QUICK: '基于视频字幕回答，并提供时间引用',
  DEEP: '依据视频原文整理简明笔记，按缺口补充'
})[requestedMode.value])

const submitFollowup = goal => {
  if (submitting.value) return
  content.value = goal
  requestedMode.value = 'DEEP'
  return submit()
}

const submit = async () => {
  const value = content.value.trim()
  if (!value || submitting.value) return
  const mediaId = props.mediaId
  const conversationContext = conversationPanel.value?.dispatchContext() || {}
  submitting.value = true
  error.value = ''
  try {
    const response = await dispatchAssistantMessage(props.mediaId, {
      content: value,
      mode: requestedMode.value,
      conversationId: conversationContext.conversationId
    })
    if (mediaId !== props.mediaId) return
    followIndexJob(null)
    routeInfo.value = response
    conversationPanel.value?.acceptTimeline(response.conversation, response.timeline)
    content.value = ''
  } catch (requestError) {
    if (mediaId !== props.mediaId) return
    const data = requestError?.response?.data
    if (['KNOWLEDGE_INDEX_PREPARING', 'KNOWLEDGE_INDEX_FAILED'].includes(data?.code)) {
      followIndexJob(data.job)
    } else {
      error.value = errorMessage(requestError, 'AI 助手请求失败')
    }
  } finally {
    submitting.value = false
  }
}

watch(() => props.mediaId, async mediaId => {
  stopIndexPolling()
  indexJob.value = null
  content.value = ''
  requestedMode.value = 'AUTO'
  routeInfo.value = null
  error.value = ''
  try {
    const job = await getLatestKnowledgeIndexJob(mediaId)
    if (props.mediaId === mediaId && job && job.status !== 'SUCCEEDED') followIndexJob(job)
  } catch (requestError) {
    // The composer remains usable; a request requiring retrieval reports preparation explicitly.
  }
}, { immediate: true })
watch(isStudyMode, studyMode => {
  if (!studyMode) {
    requestedMode.value = 'AUTO'
  }
})
</script>

<style scoped>
.assistant-panel { display: flex; min-height: 526px; flex-direction: column; }
.assistant-composer { position: sticky; bottom: -26px; z-index: 2; display: grid; gap: 8px; margin-top: 20px; padding: 18px 0 0; background: linear-gradient(transparent, var(--panel) 14%); }
.assistant-composer textarea { width: 100%; min-height: 88px; resize: vertical; padding: 12px 13px; border: 1px solid var(--border); border-radius: 11px; outline: none; background: #0e1014; color: var(--text); font: inherit; line-height: 1.5; }
.assistant-composer textarea:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(197, 249, 70, .08); }
.composer-actions { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 10px; align-items: center; }
.composer-actions select { padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; outline: none; background: #0e1014; color: var(--text); }
.composer-hint, .composer-error, .route-info { font-size: 11px; }
.composer-hint { color: var(--muted); }
.composer-error { color: var(--danger); }
.route-info { padding: 8px 10px; border: 1px solid rgba(197, 249, 70, .22); border-radius: 8px; background: rgba(197, 249, 70, .05); color: var(--muted); }
.route-info span { margin-right: 8px; color: var(--accent); font-weight: 700; }
@media (max-width: 620px) {
  .composer-actions { grid-template-columns: 1fr auto; }
  .composer-hint, .composer-error { grid-column: 1 / -1; grid-row: 2; }
}
</style>
