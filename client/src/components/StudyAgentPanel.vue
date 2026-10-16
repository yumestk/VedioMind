<template>
  <div v-if="!hasTranscript" class="empty-content">
    <span>✦</span>
    <h3>学习助手尚未就绪</h3>
    <p>请先完成 AI 分析，学习助手会基于知识索引和多模态证据生成笔记。</p>
  </div>
  <section v-else class="agent-panel">
    <div class="agent-toolbar">
      <select
        :value="activeRun?.id || ''"
        :disabled="loading || busy"
        aria-label="选择学习任务"
        @change="selectRun($event.target.value)"
      >
        <option value="">新学习任务</option>
        <option v-for="run in runs" :key="run.id" :value="run.id">
          {{ run.goal }} · {{ statusLabel(run.status) }}
        </option>
      </select>
      <button class="secondary-button compact" :disabled="busy" @click="newRun">＋ 新任务</button>
    </div>

    <div v-if="loading" class="agent-loading"><div class="spinner"></div><span>加载学习任务…</span></div>

    <div v-else-if="activeRun" class="agent-result">
      <header class="run-heading">
        <div>
          <p class="run-kicker">LEARNING GOAL</p>
          <h3>{{ activeRun.goal }}</h3>
        </div>
        <span class="run-status" :class="activeRun.status.toLowerCase()">
          {{ statusLabel(activeRun.status) }}
        </span>
      </header>

      <div v-if="isRunning" class="run-progress">
        <div class="progress-track"><span :style="{ width: `${activeRun.progress || 0}%` }"></span></div>
        <p>{{ stageDescription(activeRun.status) }} · {{ activeRun.progress || 0 }}%</p>
      </div>

      <div v-else-if="activeRun.status === 'FAILED'" class="run-error">
        <strong>学习任务未完成</strong>
        <p>{{ activeRun.errorMessage || '请稍后新建任务重试。' }}</p>
      </div>

      <template v-else-if="activeRun.result">
        <article class="result-block result-core">
          <p class="run-kicker">CORE CONCLUSION</p>
          <h2>{{ activeRun.result.title }}</h2>
          <p>{{ activeRun.result.coreConclusion }}</p>
          <div class="evidence-list">
            <button
              v-for="citation in citationsFor(activeRun.result.coreEvidenceRefs)"
              :key="citation.ref"
              @click="$emit('seek', citation.startMs)"
            >
              <span>{{ formatTime(citation.startMs) }}</span>
              {{ citation.type === 'OCR' ? '画面文字' : '字幕' }} · {{ citation.text }}
            </button>
          </div>
        </article>

        <section class="result-block">
          <p class="run-kicker">KNOWLEDGE POINTS</p>
          <article v-for="(point, index) in activeRun.result.knowledgePoints" :key="index" class="knowledge-point">
            <h3>{{ index + 1 }}. {{ point.title }}</h3>
            <p>{{ point.explanation }}</p>
            <div class="evidence-list">
              <button
                v-for="citation in citationsFor(point.evidenceRefs)"
                :key="citation.ref"
                @click="$emit('seek', citation.startMs)"
              >
                <span>{{ formatTime(citation.startMs) }}</span>
                {{ citation.type === 'OCR' ? '画面文字' : '字幕' }} · {{ citation.text }}
              </button>
            </div>
          </article>
        </section>

        <section class="result-block">
          <p class="run-kicker">SELF CHECK</p>
          <details v-for="(item, index) in activeRun.result.quiz" :key="index" class="quiz-item">
            <summary>{{ index + 1 }}. {{ item.question }}</summary>
            <p>{{ item.answer }}</p>
            <div class="evidence-list">
              <button
                v-for="citation in citationsFor(item.evidenceRefs)"
                :key="citation.ref"
                @click="$emit('seek', citation.startMs)"
              >
                <span>{{ formatTime(citation.startMs) }}</span>
                {{ citation.type === 'OCR' ? '画面文字' : '字幕' }} · {{ citation.text }}
              </button>
            </div>
          </details>
        </section>

        <section v-if="activeRun.result.gaps?.length" class="result-block gaps-block">
          <p class="run-kicker">EVIDENCE GAPS</p>
          <ul><li v-for="gap in activeRun.result.gaps" :key="gap">{{ gap }}</li></ul>
        </section>

        <button class="primary-button follow-up-button" type="button" @click="$emit('follow-up', activeRun)">
          基于结果继续追问
        </button>
      </template>

      <footer class="run-metrics">
        模型调用 {{ activeRun.generationCalls || 0 }} 次 · 检索 {{ activeRun.retrievalCalls || 0 }} 次
        <template v-if="activeRun.tokenUsageKnown">
          · Token {{ (activeRun.inputTokens || 0) + (activeRun.outputTokens || 0) }}
        </template>
        <template v-else> · Token 未返回</template>
      </footer>
    </div>

    <div v-else class="agent-intro">
      <span>✦</span>
      <h3>生成一份可追溯的学习笔记</h3>
      <p>在下方输入目标，助手会自动判断是否需要规划、检索和结果校验。</p>
      <p v-if="error" class="agent-error">{{ error }}</p>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getVideoAgentRun, listVideoAgentRuns } from '../api/media'
import { errorMessage } from '../api/http'

const props = defineProps({
  mediaId: { type: Number, required: true },
  userId: { type: Number, required: true },
  hasTranscript: { type: Boolean, default: false },
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['seek', 'follow-up', 'run-change'])

const terminal = new Set(['SUCCEEDED', 'FAILED'])
const runs = ref([])
const activeRun = ref(null)
const loading = ref(false)
const error = ref('')
let pollTimer

const isRunning = computed(() => activeRun.value && !terminal.has(activeRun.value.status))

const schedulePoll = () => {
  clearTimeout(pollTimer)
  if (!isRunning.value) return
  pollTimer = setTimeout(refreshActive, 1500)
}

const refreshActive = async () => {
  if (!activeRun.value) return
  try {
    const updated = await getVideoAgentRun(activeRun.value.id, props.userId)
    activeRun.value = updated
    emit('run-change', updated)
    const index = runs.value.findIndex(run => run.id === updated.id)
    if (index >= 0) runs.value[index] = updated
  } catch (requestError) {
    error.value = errorMessage(requestError, '学习任务状态加载失败')
  } finally {
    schedulePoll()
  }
}

const initialize = async () => {
  clearTimeout(pollTimer)
  if (!props.hasTranscript) return
  loading.value = true
  error.value = ''
  try {
    runs.value = await listVideoAgentRuns(props.mediaId, props.userId)
    activeRun.value = runs.value[0] || null
    emit('run-change', activeRun.value)
    schedulePoll()
  } catch (requestError) {
    error.value = errorMessage(requestError, '学习任务加载失败')
  } finally {
    loading.value = false
  }
}

const selectRun = (runId) => {
  clearTimeout(pollTimer)
  activeRun.value = runs.value.find(run => run.id === runId) || null
  emit('run-change', activeRun.value)
  error.value = ''
  schedulePoll()
}

const newRun = () => {
  clearTimeout(pollTimer)
  activeRun.value = null
  emit('run-change', null)
  error.value = ''
}

const acceptRun = run => {
  const index = runs.value.findIndex(item => item.id === run.id)
  if (index >= 0) runs.value.splice(index, 1)
  runs.value.unshift(run)
  activeRun.value = run
  emit('run-change', run)
  error.value = ''
  schedulePoll()
}

const citationsFor = (refs = []) => refs
  .map(ref => activeRun.value?.citations?.find(citation => citation.ref === ref))
  .filter(Boolean)

const statusLabel = (status) => ({
  QUEUED: '等待执行', PLANNING: '正在规划', RETRIEVING: '查找依据', DRAFTING: '整理笔记',
  CRITIQUING: '检查结果', REVISING: '修订结果', RETRYING: '准备重试', SUCCEEDED: '已完成', FAILED: '失败'
})[status] || status

const stageDescription = (status) => ({
  QUEUED: '任务已进入队列', PLANNING: '正在拆解学习目标', RETRIEVING: '正在检索相关证据',
  DRAFTING: '正在整理知识点', CRITIQUING: '正在核对目标、事实与引用', REVISING: '正在按检查意见修订',
  RETRYING: '任务将从最近检查点恢复'
})[status] || '正在处理'

const formatTime = (milliseconds) => {
  const seconds = Math.floor(milliseconds / 1000)
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}` : `${minutes}:${String(rest).padStart(2, '0')}`
}

watch(() => [props.mediaId, props.userId, props.hasTranscript], initialize, { immediate: true })
onBeforeUnmount(() => clearTimeout(pollTimer))

defineExpose({ acceptRun })
</script>

<style scoped>
.agent-panel { display: flex; min-height: 526px; flex-direction: column; gap: 20px; }
.agent-toolbar { display: flex; align-items: center; gap: 10px; }
.agent-toolbar select { min-width: 0; flex: 1; padding: 9px 10px; border: 1px solid var(--border); border-radius: 9px; outline: none; background: #0e1014; color: var(--text); }
.agent-loading { display: flex; flex: 1; align-items: center; justify-content: center; gap: 10px; color: var(--muted); }
.agent-intro { display: grid; flex: 1; place-content: center; justify-items: center; gap: 10px; text-align: center; }
.agent-intro > span { color: var(--accent); font-size: 32px; }
.agent-intro h3, .agent-intro p { margin: 0; }
.agent-intro p { max-width: 430px; color: var(--muted); line-height: 1.7; }
.agent-error { color: var(--danger); }
.agent-result { display: grid; gap: 16px; }
.run-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.run-heading h3 { margin: 4px 0 0; line-height: 1.5; }
.run-kicker { margin: 0; color: var(--accent); font: 10px var(--mono); letter-spacing: .1em; }
.run-status { flex: none; padding: 6px 9px; border: 1px solid var(--border); border-radius: 99px; color: var(--muted); font: 10px var(--mono); }
.run-status.succeeded { color: var(--accent); }
.run-status.failed { color: var(--danger); }
.run-progress, .run-error, .result-block { padding: 16px; border: 1px solid var(--border); border-radius: 12px; background: #0e1014; }
.progress-track { overflow: hidden; height: 5px; border-radius: 99px; background: var(--surface); }
.progress-track span { display: block; height: 100%; background: var(--accent); transition: width .25s; }
.run-progress p, .run-error p { margin: 10px 0 0; color: var(--muted); }
.result-core h2 { margin: 8px 0 10px; font-size: 22px; }
.result-block > p:not(.run-kicker), .knowledge-point p, .quiz-item p { color: #d8dadd; line-height: 1.75; white-space: pre-wrap; }
.knowledge-point { padding: 14px 0; border-bottom: 1px solid var(--border); }
.knowledge-point:last-child { padding-bottom: 0; border-bottom: 0; }
.knowledge-point h3 { margin: 0; font-size: 15px; }
.quiz-item { padding: 12px 0; border-bottom: 1px solid var(--border); }
.quiz-item:last-child { border-bottom: 0; }
.quiz-item summary { cursor: pointer; color: var(--text); font-weight: 700; }
.evidence-list { display: grid; gap: 6px; margin-top: 10px; }
.evidence-list button { overflow: hidden; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--panel); color: var(--muted); text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.evidence-list button:hover { border-color: var(--border-strong); color: var(--text); }
.evidence-list span { margin-right: 7px; color: var(--accent); font: 10px var(--mono); }
.gaps-block ul { margin-bottom: 0; padding-left: 20px; color: var(--muted); line-height: 1.7; }
.follow-up-button { justify-self: start; }
.run-metrics { color: var(--muted); font: 10px var(--mono); text-align: right; }
</style>
