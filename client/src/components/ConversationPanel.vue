<template>
  <div v-if="!hasTranscript" class="empty-content">
    <span>?</span>
    <h3>还不能向视频提问</h3>
    <p>请先完成 AI 分析，问答会严格基于带时间戳的原始字幕证据。</p>
  </div>
  <section v-else class="conversation-panel">
    <div class="conversation-toolbar">
      <select
        :value="activeConversationId || ''"
        :disabled="loading || busy"
        aria-label="选择对话"
        @change="switchConversation(Number($event.target.value))"
      >
        <option value="">新对话</option>
        <option v-for="conversation in conversations" :key="conversation.id" :value="conversation.id">
          {{ conversation.title }}
        </option>
      </select>
      <button class="secondary-button compact" :disabled="busy" @click="startNewConversation">
        ＋ 新对话
      </button>
    </div>

    <div v-if="linkedAgentRunId" class="agent-context-banner">
      <span>✦</span>
      <div>
        <strong>正在基于深度学习结果追问</strong>
        <p>{{ linkedAgentTitle }}</p>
      </div>
    </div>

    <div v-if="loading" class="conversation-loading"><div class="spinner"></div><span>加载对话…</span></div>
    <div v-else-if="messages.length" class="conversation-history">
      <article
        v-for="message in messages"
        :key="message.id"
        class="conversation-message"
        :class="message.role.toLowerCase()"
      >
        <p v-if="message.role === 'USER'" class="user-message">{{ message.content }}</p>
        <template v-else>
          <div class="assistant-message">
            <span class="answer-mark">AI</span>
            <p>{{ message.content }}</p>
          </div>
          <div v-if="message.citations?.length" class="citation-list">
            <button
              v-for="citation in message.citations"
              :key="`${message.id}-${citation.segmentId}`"
              class="citation-button"
              @click="$emit('seek', citation.startMs)"
            >
              <span>{{ formatTime(citation.startMs) }}</span>
              {{ citation.text }}
            </button>
          </div>
        </template>
      </article>
    </div>
    <div v-else class="conversation-intro">
      <span>?</span>
      <h3>{{ linkedAgentRunId ? '继续追问学习结果' : '向这个视频提问' }}</h3>
      <p v-if="linkedAgentRunId">学习结果帮助理解指代，回答仍会检索原始字幕并提供时间引用。</p>
      <p v-else-if="isStudyMode">可以连续追问。回答会先检索相关内容，并附带可点击的原文时间引用。</p>
      <p v-else>可以连续追问。回答基于平台字幕，并附带可点击的原文时间引用。</p>
    </div>

    <p v-if="error" class="question-error">{{ error }}</p>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import {
  getConversationMessages,
  listVideoConversations
} from '../api/media'
import { errorMessage } from '../api/http'

const props = defineProps({
  mediaId: { type: Number, required: true },
  userId: { type: Number, required: true },
  hasTranscript: { type: Boolean, default: false },
  analysisMode: { type: String, default: '' },
  busy: { type: Boolean, default: false }
})

defineEmits(['seek'])

const conversations = ref([])
const activeConversationId = ref(null)
const messages = ref([])
const loading = ref(false)
const error = ref('')
const pendingFollowUp = ref(null)
const isStudyMode = computed(() => props.analysisMode === 'STUDY')
const activeConversation = computed(() => conversations.value.find(
  conversation => conversation.id === activeConversationId.value
))
const linkedAgentRunId = computed(() =>
  pendingFollowUp.value?.runId || activeConversation.value?.sourceAgentRunId || null
)
const linkedAgentTitle = computed(() => pendingFollowUp.value?.title || '已关联的深度学习任务')

const loadMessages = async (conversationId) => {
  if (!conversationId) {
    messages.value = []
    return
  }
  const result = await getConversationMessages(conversationId, props.userId)
  messages.value = result.messages || []
}

const initialize = async () => {
  if (!props.hasTranscript) return
  pendingFollowUp.value = null
  loading.value = true
  error.value = ''
  try {
    conversations.value = await listVideoConversations(props.mediaId, props.userId)
    activeConversationId.value = conversations.value[0]?.id || null
    await loadMessages(activeConversationId.value)
  } catch (requestError) {
    error.value = errorMessage(requestError, '视频对话加载失败')
  } finally {
    loading.value = false
  }
}

const switchConversation = async (conversationId) => {
  pendingFollowUp.value = null
  activeConversationId.value = conversationId || null
  loading.value = true
  error.value = ''
  try {
    await loadMessages(activeConversationId.value)
  } catch (requestError) {
    error.value = errorMessage(requestError, '视频对话加载失败')
  } finally {
    loading.value = false
  }
}

const startNewConversation = () => {
  pendingFollowUp.value = null
  activeConversationId.value = null
  messages.value = []
  error.value = ''
}

const dispatchContext = () => ({
  conversationId: activeConversationId.value,
  sourceAgentRunId: linkedAgentRunId.value
})

const acceptTurn = turn => {
  messages.value.push(turn.userMessage, turn.assistantMessage)
  const index = conversations.value.findIndex(item => item.id === turn.conversation.id)
  if (index >= 0) conversations.value.splice(index, 1)
  conversations.value.unshift(turn.conversation)
  activeConversationId.value = turn.conversation.id
  pendingFollowUp.value = null
  error.value = ''
}

const continueFromRun = run => {
  activeConversationId.value = null
  messages.value = []
  pendingFollowUp.value = { runId: run.id, title: run.result?.title || run.goal }
  error.value = ''
}

const formatTime = (milliseconds) => {
  const totalSeconds = Math.floor(milliseconds / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const minuteText = String(minutes).padStart(hours ? 2 : 1, '0')
  const secondText = String(seconds).padStart(2, '0')
  return hours ? `${hours}:${minuteText}:${secondText}` : `${minuteText}:${secondText}`
}

watch(() => [props.mediaId, props.userId, props.hasTranscript], initialize, { immediate: true })

defineExpose({ acceptTurn, continueFromRun, dispatchContext })
</script>

<style scoped>
.conversation-panel { display: flex; min-height: 526px; flex-direction: column; gap: 20px; }
.conversation-toolbar { display: flex; gap: 10px; align-items: center; }
.conversation-toolbar select { min-width: 0; flex: 1; padding: 9px 10px; border: 1px solid var(--border); border-radius: 9px; outline: none; background: #0e1014; color: var(--text); }
.conversation-toolbar select:focus { border-color: var(--accent); }
.agent-context-banner { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 10px; align-items: center; padding: 10px 12px; border: 1px solid rgba(197, 249, 70, .28); border-radius: 10px; background: rgba(197, 249, 70, .06); }
.agent-context-banner > span { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--accent); color: #0b0e07; }
.agent-context-banner strong { color: var(--text); font-size: 12px; }
.agent-context-banner p { margin: 3px 0 0; color: var(--muted); font-size: 11px; }
.conversation-loading { display: flex; flex: 1; align-items: center; justify-content: center; gap: 10px; color: var(--muted); }
.conversation-history { display: grid; gap: 18px; }
.conversation-message { display: grid; gap: 10px; }
.user-message { justify-self: end; max-width: 84%; margin: 0; padding: 10px 13px; border-radius: 12px 12px 3px 12px; background: var(--surface); color: var(--text); line-height: 1.6; }
.assistant-message { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 10px; align-items: start; }
.assistant-message p { margin: 2px 0 0; color: #d8dadd; line-height: 1.75; white-space: pre-wrap; }
.answer-mark { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--accent); color: #0b0e07; font: 700 9px var(--mono); }
.citation-list { display: grid; gap: 7px; padding-left: 38px; }
.citation-button { overflow: hidden; padding: 9px 11px; border: 1px solid var(--border); border-radius: 9px; background: #0e1014; color: var(--muted); text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.citation-button:hover { border-color: var(--border-strong); color: var(--text); }
.citation-button span { margin-right: 7px; color: var(--accent); font: 10px var(--mono); }
.conversation-intro { display: grid; flex: 1; place-content: center; justify-items: center; gap: 9px; color: var(--muted); text-align: center; }
.conversation-intro > span { color: var(--accent); font-size: 30px; }
.conversation-intro h3, .conversation-intro p { margin: 0; }
.conversation-intro p { max-width: 390px; line-height: 1.7; }
.question-error { color: var(--danger); }
</style>
