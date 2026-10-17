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

    <div v-if="loading" class="conversation-loading"><div class="spinner"></div><span>加载对话…</span></div>
    <div v-else-if="messages.length" class="conversation-history">
      <article
        v-for="message in messages"
        :key="message.id"
        class="conversation-message"
        :class="message.messageType.toLowerCase()"
      >
        <p v-if="message.messageType === 'USER'" class="user-message">{{ message.content }}</p>
        <template v-else-if="message.messageType === 'ASSISTANT'">
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
        <AgentRunMessageCard
          v-else-if="message.messageType === 'AGENT_RUN' && message.agentRun"
          :run="message.agentRun"
          :user-id="userId"
          @seek="$emit('seek', $event)"
          @run-change="updateAgentRun(message.id, $event)"
        />
      </article>
    </div>
    <div v-else class="conversation-intro">
      <span>?</span>
      <h3>向这个视频提问</h3>
      <p v-if="isStudyMode">快速问答和深度学习任务会按顺序保存在这段对话中。</p>
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
import AgentRunMessageCard from './AgentRunMessageCard.vue'

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
const isStudyMode = computed(() => props.analysisMode === 'STUDY')

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
  activeConversationId.value = null
  messages.value = []
  error.value = ''
}

const dispatchContext = () => ({
  conversationId: activeConversationId.value
})

const acceptTimeline = (conversation, timeline) => {
  messages.value = timeline?.messages || []
  const index = conversations.value.findIndex(item => item.id === conversation.id)
  if (index >= 0) conversations.value.splice(index, 1)
  conversations.value.unshift(conversation)
  activeConversationId.value = conversation.id
  error.value = ''
}

const updateAgentRun = (messageId, run) => {
  const message = messages.value.find(item => item.id === messageId)
  if (message) message.agentRun = run
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

defineExpose({ acceptTimeline, dispatchContext })
</script>

<style scoped>
.conversation-panel { display: flex; min-height: 526px; flex-direction: column; gap: 20px; }
.conversation-toolbar { display: flex; gap: 10px; align-items: center; }
.conversation-toolbar select { min-width: 0; flex: 1; padding: 9px 10px; border: 1px solid var(--border); border-radius: 9px; outline: none; background: #0e1014; color: var(--text); }
.conversation-toolbar select:focus { border-color: var(--accent); }
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
