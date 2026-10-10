<template>
  <div v-if="!hasTranscript" class="empty-content">
    <span>?</span>
    <h3>还不能向视频提问</h3>
    <p>请先完成 AI 分析，问答会严格基于带时间戳的字幕。</p>
  </div>
  <section v-else class="conversation-panel">
    <div class="conversation-toolbar">
      <select
        :value="activeConversationId || ''"
        :disabled="loading || submitting"
        aria-label="选择对话"
        @change="switchConversation(Number($event.target.value))"
      >
        <option value="">新对话</option>
        <option v-for="conversation in conversations" :key="conversation.id" :value="conversation.id">
          {{ conversation.title }}
        </option>
      </select>
      <button class="secondary-button compact" :disabled="submitting" @click="startNewConversation">
        ＋ 新对话
      </button>
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
      <h3>向这个视频提问</h3>
      <p>可以连续追问。回答只使用当前视频字幕，并附带可点击的原文时间引用。</p>
    </div>

    <form class="question-form" @submit.prevent="submitQuestion">
      <textarea
        v-model="question"
        maxlength="500"
        placeholder="例如：视频提出了哪些核心观点？"
        :disabled="submitting"
      ></textarea>
      <div class="question-actions">
        <span v-if="error" class="question-error">{{ error }}</span>
        <span v-else class="question-hint">多轮对话 · 仅基于字幕</span>
        <button class="primary-button compact" :disabled="submitting || !question.trim()">
          {{ submitting ? '回答中…' : '提问' }}
        </button>
      </div>
    </form>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import {
  createVideoConversation,
  getConversationMessages,
  listVideoConversations,
  sendConversationMessage
} from '../api/media'
import { errorMessage } from '../api/http'

const props = defineProps({
  mediaId: { type: Number, required: true },
  userId: { type: Number, required: true },
  hasTranscript: { type: Boolean, default: false }
})

defineEmits(['seek'])

const conversations = ref([])
const activeConversationId = ref(null)
const messages = ref([])
const question = ref('')
const loading = ref(false)
const submitting = ref(false)
const error = ref('')

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
  question.value = ''
  error.value = ''
}

const submitQuestion = async () => {
  const value = question.value.trim()
  if (!value || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    if (!activeConversationId.value) {
      const conversation = await createVideoConversation(props.mediaId, props.userId)
      conversations.value.unshift(conversation)
      activeConversationId.value = conversation.id
    }
    const turn = await sendConversationMessage(activeConversationId.value, props.userId, value)
    messages.value.push(turn.userMessage, turn.assistantMessage)
    const index = conversations.value.findIndex(item => item.id === turn.conversation.id)
    if (index >= 0) conversations.value.splice(index, 1)
    conversations.value.unshift(turn.conversation)
    question.value = ''
  } catch (requestError) {
    error.value = errorMessage(requestError, '视频问答失败')
  } finally {
    submitting.value = false
  }
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
.question-form { position: sticky; bottom: -26px; display: grid; gap: 8px; margin-top: auto; padding: 12px 0 0; background: linear-gradient(transparent, var(--panel) 18%); }
.question-form textarea { width: 100%; min-height: 88px; resize: vertical; padding: 12px 13px; border: 1px solid var(--border); border-radius: 11px; outline: none; background: #0e1014; color: var(--text); font: inherit; line-height: 1.5; }
.question-form textarea:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(197, 249, 70, .08); }
.question-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.question-hint, .question-error { font-size: 11px; }
.question-hint { color: var(--muted); }
.question-error { color: var(--danger); }
</style>
