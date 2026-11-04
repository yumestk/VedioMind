<template>
  <article v-if="currentRun" class="agent-run-card">
    <header class="run-heading">
      <div>
        <p class="run-kicker">DEEP TASK</p>
        <h3>{{ currentRun.goal }}</h3>
      </div>
      <span class="run-status" :class="currentRun.status.toLowerCase()">
        {{ statusLabel(currentRun.status) }}
      </span>
    </header>

    <div v-if="isRunning" class="run-progress">
      <div class="progress-track"><span :style="{ width: `${currentRun.progress || 0}%` }"></span></div>
      <p>{{ stageDescription(currentRun.status === 'RUNNING' ? currentRun.phase : currentRun.status) }} · {{ currentRun.progress || 0 }}%</p>
    </div>

    <button v-if="isRunning" :disabled="cancelPending" @click="cancelRun">{{ cancelPending ? '正在取消…' : '取消任务' }}</button>
    <p v-if="controlError" role="alert" class="run-error">{{ controlError }}</p>
    <div v-if="['FAILED', 'CANCELLED', 'NEEDS_INPUT'].includes(currentRun.status)" class="run-error">
      <strong>{{ statusLabel(currentRun.status) }}</strong>
      <p>{{ currentRun.errorMessage || '请稍后重新提交任务。' }}</p>
    </div>

    <p v-if="currentRun.status === 'PARTIAL'" class="run-error" role="status">
      已交付有依据的部分笔记：{{ currentRun.errorMessage }}
    </p>
    <p v-if="!currentRun.sourceAvailable" role="status">引用来源已失效，原文和播放不可用；已保留学习成果。</p>
    <p class="run-metrics">等待 {{ Math.floor((currentRun.waitingMs || 0) / 1000) }} 秒 · 活动 {{ Math.floor((currentRun.activeElapsedMs || 0) / 1000) }} 秒 / {{ Math.floor((currentRun.activeLimitMs || 120000) / 1000) }} 秒</p>
    <p v-if="currentRun.stopCode" class="run-metrics">停止原因：{{ currentRun.stopCode }}</p>
    <details v-if="events.length" class="evidence-details">
      <summary>执行记录 · {{ events.length }} 条</summary>
      <ol><li v-for="event in events" :key="event.sequence">#{{ event.sequence }} {{ eventLabel(event.type) }}<span v-if="event.data.elapsedMs != null"> · {{ event.data.elapsedMs }} ms</span><span v-if="event.data.code"> · {{ event.data.code }}</span></li></ol>
    </details>

    <template v-if="currentRun.result">
      <section class="result-block result-core">
        <p class="run-kicker">CORE CONCLUSION</p>
        <h2>{{ currentRun.result.title }}</h2>
        <p>{{ currentRun.result.coreConclusion }}</p>
        <details
          v-if="citationsFor(currentRun.result.coreEvidenceRefs).length"
          class="evidence-details"
        >
          <summary>参考证据 · {{ citationsFor(currentRun.result.coreEvidenceRefs).length }} 条</summary>
          <div class="evidence-list">
            <button
              v-for="citation in citationsFor(currentRun.result.coreEvidenceRefs)"
              :key="citation.ref"
              @click="$emit('seek', { materialId: citation.materialId || currentRun.mediaId, startMs: citation.startMs })"
            >
              <span>{{ formatTime(citation.startMs) }}</span>
              {{ citation.type === 'OCR' ? '画面文字' : '字幕' }} · {{ citation.text }}
            </button>
          </div>
        </details>
      </section>

      <section class="result-block">
        <p class="run-kicker">KNOWLEDGE POINTS</p>
        <article
          v-for="(point, index) in currentRun.result.knowledgePoints"
          :key="index"
          class="knowledge-point"
        >
          <h3>{{ index + 1 }}. {{ point.title }}</h3>
          <p>{{ point.explanation }}</p>
          <button :disabled="isRunning || busy || !currentRun.sourceAvailable" @click="followup(`展开知识点：${point.title}。只依据视频原文，详细说明条件与例子。`)">展开此知识点</button>
          <details v-if="citationsFor(point.evidenceRefs).length" class="evidence-details">
            <summary>参考证据 · {{ citationsFor(point.evidenceRefs).length }} 条</summary>
            <div class="evidence-list">
              <button
                v-for="citation in citationsFor(point.evidenceRefs)"
                :key="citation.ref"
                @click="$emit('seek', { materialId: citation.materialId || currentRun.mediaId, startMs: citation.startMs })"
              >
                <span>{{ formatTime(citation.startMs) }}</span>
                {{ citation.type === 'OCR' ? '画面文字' : '字幕' }} · {{ citation.text }}
              </button>
            </div>
          </details>
        </article>
      </section>

      <button v-if="!currentRun.result.quiz?.length" :disabled="isRunning || busy || !currentRun.sourceAvailable"
        @click="followup(`针对主题「${currentRun.goal}」生成3道自测题，答案附视频原文依据。`)">按需生成自测题</button>
      <section v-if="currentRun.result.quiz?.length" class="result-block">
        <p class="run-kicker">SELF CHECK</p>
        <details
          v-for="(item, index) in currentRun.result.quiz"
          :key="index"
          class="quiz-item"
        >
          <summary>{{ index + 1 }}. {{ item.question }}</summary>
          <p>{{ item.answer }}</p>
        </details>
      </section>

      <section v-if="currentRun.result.gaps?.length" class="result-block gaps-block">
        <p class="run-kicker">EVIDENCE GAPS</p>
        <ul><li v-for="gap in currentRun.result.gaps" :key="gap">{{ gap }}</li></ul>
      </section>
    </template>

    <footer class="run-metrics">
      生成请求 {{ currentRun.generationCalls || 0 }} 次 · 检索 {{ currentRun.retrievalCalls || 0 }} 次 · 治理 Token {{ currentRun.accountedTokens || 0 }}
      <template v-if="currentRun.tokenUsageKnown">
        · Token {{ (currentRun.inputTokens || 0) + (currentRun.outputTokens || 0) }}
      </template>
      <template v-else> · 部分请求Token未知（不等于免费）</template>
    </footer>
  </article>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getVideoAgentRun, cancelVideoAgentRun, getVideoAgentRunEvents } from '../api/media'
import { errorMessage } from '../api/http'

const props = defineProps({
  run: { type: Object, required: true },
  userId: { type: Number, required: true },
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['seek', 'run-change', 'followup'])
const events = ref([])
const controlError = ref('')
const cancelPending = ref(false)
let eventCursor = 0
const currentRun = ref(props.run)
let pollTimer
let observedId

const isRunning = computed(() => currentRun.value && !currentRun.value.terminal)

const followup = goal => emit('followup', goal.slice(0, 500))

const schedulePoll = () => {
  clearTimeout(pollTimer)
  if (isRunning.value) pollTimer = setTimeout(refresh, 1500)
}

const refresh = async () => {
  if (!currentRun.value) return
  try {
    currentRun.value = await getVideoAgentRun(currentRun.value.id)
    const batch = await getVideoAgentRunEvents(currentRun.value.id, eventCursor)
    if (batch.length) { events.value = [...events.value, ...batch].slice(-100); eventCursor = batch.at(-1).sequence }
    emit('run-change', currentRun.value)
  } catch (error) {
    console.warn('Agent run polling failed', error)
  } finally {
    schedulePoll()
  }
}

const cancelRun = async () => {
  cancelPending.value = true
  controlError.value = ''
  try { currentRun.value = await cancelVideoAgentRun(currentRun.value.id); emit('run-change', currentRun.value); await refresh() }
  catch (error) { controlError.value = errorMessage(error, '取消失败，请稍后重试') }
  finally { cancelPending.value = false }
}
const eventLabel = type => ({ TASK_QUEUED: '任务入队', PREPARATION_ACCOUNTED: '准备阶段用量已计入', RESULT_SAVED: '已保存可用笔记', EXECUTION_STARTED: '开始执行', PHASE_CHANGED: '进入新阶段', CALL_RESERVED: '预留调用预算', CALL_SETTLED: '供应商调用已记录', CALL_OUTCOME_UNKNOWN: '供应商结果未知', CHECKPOINT_SAVED: '检查点已保存', QUOTA_WAIT: '等待额度', RETRY_SCHEDULED: '安排恢复', TASK_COMPLETED: '任务完成', TASK_STOPPED: '任务停止' })[type] || type

const citationsFor = (refs = []) => refs
  .map(ref => currentRun.value.citations?.find(citation => citation.ref === ref))
  .filter(Boolean)

const statusLabel = status => ({
  QUEUED: '等待执行', RUNNING: '正在执行', RETRY_WAIT: '准备恢复', WAITING_OPERATION: '等待操作',
  WAITING_FOR_QUOTA: '等待 AI 服务额度',
  SUCCEEDED: '已完成', PARTIAL: '部分完成', FAILED: '失败', CANCELLED: '已取消', NEEDS_INPUT: '需要补充条件'
})[status] || status

const stageDescription = status => ({
  WAITING_FOR_QUOTA: '额度恢复后会自动继续，无需重新提交',
  QUEUED: '任务已进入队列', RETRIEVING: '正在检索相关证据',
  DRAFTING: '正在整理知识点',
  REVISING: '正在按检查意见修订', RETRY_WAIT: '任务将从已保存响应和检查点恢复'
})[status] || '正在处理'

const formatTime = milliseconds => {
  const seconds = Math.floor(milliseconds / 1000)
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  return hours
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
    : `${minutes}:${String(rest).padStart(2, '0')}`
}

watch(() => props.run, run => {
  const changed = observedId !== run.id
  if (changed) { observedId = run.id; events.value = []; eventCursor = 0 }
  currentRun.value = run
  if (changed) refresh()
  else schedulePoll()
}, { immediate: true })
onBeforeUnmount(() => clearTimeout(pollTimer))
</script>

<style scoped>
.agent-run-card { display: grid; gap: 14px; padding: 16px; border: 1px solid var(--border); border-radius: 12px; background: #0e1014; }
.run-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.run-heading h3 { margin: 4px 0 0; line-height: 1.5; }
.run-kicker { margin: 0; color: var(--accent); font: 10px var(--mono); letter-spacing: .1em; }
.run-status { flex: none; padding: 6px 9px; border: 1px solid var(--border); border-radius: 99px; color: var(--muted); font: 10px var(--mono); }
.run-status.succeeded { color: var(--accent); }
.run-status.partial { color: #e6b96d; }
.run-status.failed { color: var(--danger); }
.run-progress, .run-error, .result-block { padding: 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--panel); }
.progress-track { overflow: hidden; height: 5px; border-radius: 99px; background: var(--surface); }
.progress-track span { display: block; height: 100%; background: var(--accent); transition: width .25s; }
.run-progress p, .run-error p { margin: 10px 0 0; color: var(--muted); }
.result-core h2 { margin: 8px 0 10px; font-size: 20px; }
.result-block > p:not(.run-kicker), .knowledge-point p, .quiz-item p { color: #d8dadd; line-height: 1.75; white-space: pre-wrap; }
.knowledge-point, .quiz-item { padding: 12px 0; border-bottom: 1px solid var(--border); }
.knowledge-point:last-child, .quiz-item:last-child { padding-bottom: 0; border-bottom: 0; }
.knowledge-point h3 { margin: 0; font-size: 15px; }
.quiz-item summary { cursor: pointer; color: var(--text); font-weight: 700; }
.evidence-details { margin-top: 10px; }
.evidence-details summary { display: flex; cursor: pointer; align-items: center; justify-content: space-between; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; color: var(--muted); font: 10px var(--mono); list-style: none; }
.evidence-details summary::-webkit-details-marker { display: none; }
.evidence-details summary::after { content: '＋'; color: var(--accent); font-size: 14px; }
.evidence-details[open] summary::after { content: '−'; }
.evidence-list { display: grid; gap: 6px; margin-top: 6px; }
.evidence-list button { overflow: hidden; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; background: #0e1014; color: var(--muted); text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.evidence-list button:hover { border-color: var(--border-strong); color: var(--text); }
.evidence-list span { margin-right: 7px; color: var(--accent); font: 10px var(--mono); }
.gaps-block ul { margin-bottom: 0; padding-left: 20px; color: var(--muted); line-height: 1.7; }
.run-metrics { color: var(--muted); font: 10px var(--mono); text-align: right; }
</style>
