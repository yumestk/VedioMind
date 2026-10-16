<template>
  <section class="assistant-panel">
    <ConversationPanel
      v-show="surface === 'QUICK'"
      ref="conversationPanel"
      :media-id="mediaId"
      :user-id="userId"
      :has-transcript="hasTranscript"
      :analysis-mode="analysisMode"
      :busy="submitting"
      @seek="$emit('seek', $event)"
    />
    <StudyAgentPanel
      v-if="isStudyMode"
      v-show="surface === 'DEEP'"
      ref="agentPanel"
      :media-id="mediaId"
      :user-id="userId"
      :has-transcript="hasTranscript"
      :busy="submitting"
      @seek="$emit('seek', $event)"
      @follow-up="continueFromRun"
      @run-change="activeRun = $event"
    />

    <form v-if="hasTranscript" class="assistant-composer" @submit.prevent="submit">
      <div v-if="routeInfo" class="route-info">
        <span>{{ routeInfo.executionMode === 'DEEP' ? '✦ 深度任务' : '? 快速回答' }}</span>
        {{ routeInfo.reason }}
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
import { computed, ref, watch } from 'vue'
import { dispatchAssistantMessage } from '../api/media'
import { errorMessage } from '../api/http'
import ConversationPanel from './ConversationPanel.vue'
import StudyAgentPanel from './StudyAgentPanel.vue'

const props = defineProps({
  mediaId: { type: Number, required: true },
  userId: { type: Number, required: true },
  hasTranscript: { type: Boolean, default: false },
  analysisMode: { type: String, default: '' }
})

defineEmits(['seek'])

const conversationPanel = ref(null)
const agentPanel = ref(null)
const activeRun = ref(null)
const content = ref('')
const requestedMode = ref('AUTO')
const surface = ref('QUICK')
const submitting = ref(false)
const routeInfo = ref(null)
const error = ref('')
const isStudyMode = computed(() => props.analysisMode === 'STUDY')
const placeholder = computed(() => isStudyMode.value
  ? '向视频提问，或让我整理整节课并生成自测题'
  : '例如：视频提出了哪些核心观点？')
const modeHint = computed(() => ({
  AUTO: '自动判断快速回答或深度任务',
  QUICK: '低延迟 RAG 问答与时间引用',
  DEEP: '规划、检索、校验并生成学习产物'
})[requestedMode.value])

const submit = async () => {
  const value = content.value.trim()
  if (!value || submitting.value) return
  const conversationContext = conversationPanel.value?.dispatchContext() || {}
  const agentContext = surface.value === 'DEEP' && activeRun.value?.status === 'SUCCEEDED'
    ? activeRun.value.id
    : null
  submitting.value = true
  error.value = ''
  try {
    const response = await dispatchAssistantMessage(props.mediaId, props.userId, {
      content: value,
      mode: requestedMode.value,
      conversationId: surface.value === 'QUICK' ? conversationContext.conversationId : null,
      sourceAgentRunId: agentContext || conversationContext.sourceAgentRunId || null
    })
    routeInfo.value = response
    if (response.executionMode === 'DEEP') {
      agentPanel.value?.acceptRun(response.agentRun)
      surface.value = 'DEEP'
    } else {
      conversationPanel.value?.acceptTurn(response.conversationTurn)
      surface.value = 'QUICK'
    }
    content.value = ''
  } catch (requestError) {
    error.value = errorMessage(requestError, 'AI 助手请求失败')
  } finally {
    submitting.value = false
  }
}

const continueFromRun = run => {
  conversationPanel.value?.continueFromRun(run)
  surface.value = 'QUICK'
  requestedMode.value = 'QUICK'
  routeInfo.value = {
    executionMode: 'QUICK',
    reason: '下一次提问将基于深度学习结果进行快速 RAG 检索'
  }
}

watch(requestedMode, mode => {
  if (mode === 'QUICK') {
    if (surface.value === 'DEEP' && activeRun.value?.status === 'SUCCEEDED') {
      conversationPanel.value?.continueFromRun(activeRun.value)
    }
    surface.value = 'QUICK'
  }
  if (mode === 'DEEP') surface.value = 'DEEP'
})
watch(() => props.mediaId, () => {
  activeRun.value = null
  content.value = ''
  requestedMode.value = 'AUTO'
  surface.value = 'QUICK'
  routeInfo.value = null
  error.value = ''
})
watch(isStudyMode, studyMode => {
  if (!studyMode) {
    requestedMode.value = 'AUTO'
    surface.value = 'QUICK'
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
