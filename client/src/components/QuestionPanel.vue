<template>
  <div v-if="!hasTranscript" class="empty-content">
    <span>?</span>
    <h3>还不能向视频提问</h3>
    <p>请先完成 AI 分析，问答会严格基于带时间戳的字幕。</p>
  </div>
  <section v-else class="question-panel">
    <div v-if="messages.length" class="question-history">
      <article v-for="message in messages" :key="message.id" class="question-turn">
        <p class="user-question">{{ message.question }}</p>
        <div class="assistant-answer">
          <span class="answer-mark">AI</span>
          <p>{{ message.answer }}</p>
        </div>
        <div v-if="message.citations.length" class="citation-list">
          <button
            v-for="citation in message.citations"
            :key="citation.segmentId"
            class="citation-button"
            @click="$emit('seek', citation.startMs)"
          >
            <span>{{ formatTime(citation.startMs) }}</span>
            {{ citation.text }}
          </button>
        </div>
      </article>
    </div>
    <div v-else class="question-intro">
      <span>?</span>
      <h3>向这个视频提问</h3>
      <p>回答只使用当前视频字幕，并附带可点击的原文时间引用。</p>
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
        <span v-else class="question-hint">单轮问答 · 仅基于字幕</span>
        <button class="primary-button compact" :disabled="submitting || !question.trim()">
          {{ submitting ? '回答中…' : '提问' }}
        </button>
      </div>
    </form>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { askVideoQuestion } from '../api/media'
import { errorMessage } from '../api/http'

const props = defineProps({
  mediaId: { type: Number, required: true },
  userId: { type: Number, required: true },
  hasTranscript: { type: Boolean, default: false }
})

defineEmits(['seek'])

const question = ref('')
const submitting = ref(false)
const error = ref('')
const messages = ref([])

const submitQuestion = async () => {
  const value = question.value.trim()
  if (!value || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const response = await askVideoQuestion(props.mediaId, props.userId, value)
    messages.value.push({
      id: `${Date.now()}-${messages.value.length}`,
      question: value,
      answer: response.answer,
      citations: response.citations || []
    })
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
</script>

<style scoped>
.question-panel { display: flex; min-height: 526px; flex-direction: column; gap: 20px; }
.question-history { display: grid; gap: 22px; }
.question-turn { display: grid; gap: 12px; }
.user-question { justify-self: end; max-width: 84%; margin: 0; padding: 10px 13px; border-radius: 12px 12px 3px 12px; background: var(--surface); color: var(--text); line-height: 1.6; }
.assistant-answer { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 10px; align-items: start; }
.assistant-answer p { margin: 2px 0 0; color: #d8dadd; line-height: 1.75; white-space: pre-wrap; }
.answer-mark { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--accent); color: #0b0e07; font: 700 9px var(--mono); }
.citation-list { display: grid; gap: 7px; padding-left: 38px; }
.citation-button { overflow: hidden; padding: 9px 11px; border: 1px solid var(--border); border-radius: 9px; background: #0e1014; color: var(--muted); text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.citation-button:hover { border-color: var(--border-strong); color: var(--text); }
.citation-button span { margin-right: 7px; color: var(--accent); font: 10px var(--mono); }
.question-intro { display: grid; flex: 1; place-content: center; justify-items: center; gap: 9px; color: var(--muted); text-align: center; }
.question-intro > span { color: var(--accent); font-size: 30px; }
.question-intro h3, .question-intro p { margin: 0; }
.question-intro p { max-width: 380px; line-height: 1.7; }
.question-form { position: sticky; bottom: -26px; display: grid; gap: 8px; margin-top: auto; padding: 12px 0 0; background: linear-gradient(transparent, var(--panel) 18%); }
.question-form textarea { width: 100%; min-height: 88px; resize: vertical; padding: 12px 13px; border: 1px solid var(--border); border-radius: 11px; outline: none; background: #0e1014; color: var(--text); font: inherit; line-height: 1.5; }
.question-form textarea:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(197, 249, 70, .08); }
.question-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.question-hint, .question-error { font-size: 11px; }
.question-hint { color: var(--muted); }
.question-error { color: var(--danger); }
</style>
