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
      <p>{{ stageDescription(currentRun.status) }} · {{ currentRun.progress || 0 }}%</p>
    </div>

    <div v-else-if="currentRun.status === 'FAILED'" class="run-error">
      <strong>深度任务未完成</strong>
      <p>{{ currentRun.errorMessage || '请稍后重新提交任务。' }}</p>
    </div>

    <template v-else-if="currentRun.result">
      <section class="result-block result-core">
        <p class="run-kicker">CORE CONCLUSION</p>
        <h2>{{ currentRun.result.title }}</h2>
        <p>{{ currentRun.result.coreConclusion }}</p>
        <div class="evidence-list">
          <button
            v-for="citation in citationsFor(currentRun.result.coreEvidenceRefs)"
            :key="citation.ref"
            @click="$emit('seek', citation.startMs)"
          >
            <span>{{ formatTime(citation.startMs) }}</span>
            {{ citation.type === 'OCR' ? '画面文字' : '字幕' }} · {{ citation.text }}
          </button>
        </div>
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
      模型调用 {{ currentRun.generationCalls || 0 }} 次 · 检索 {{ currentRun.retrievalCalls || 0 }} 次
      <template v-if="currentRun.tokenUsageKnown">
        · Token {{ (currentRun.inputTokens || 0) + (currentRun.outputTokens || 0) }}
      </template>
      <template v-else> · Token 未返回</template>
    </footer>
  </article>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getVideoAgentRun } from '../api/media'

const props = defineProps({
  run: { type: Object, required: true },
  userId: { type: Number, required: true }
})

const emit = defineEmits(['seek', 'run-change'])
const terminal = new Set(['SUCCEEDED', 'FAILED'])
const currentRun = ref(props.run)
let pollTimer

const isRunning = computed(() => currentRun.value && !terminal.has(currentRun.value.status))

const schedulePoll = () => {
  clearTimeout(pollTimer)
  if (isRunning.value) pollTimer = setTimeout(refresh, 1500)
}

const refresh = async () => {
  if (!currentRun.value) return
  try {
    currentRun.value = await getVideoAgentRun(currentRun.value.id, props.userId)
    emit('run-change', currentRun.value)
  } catch (error) {
    console.warn('Agent run polling failed', error)
  } finally {
    schedulePoll()
  }
}

const citationsFor = (refs = []) => refs
  .map(ref => currentRun.value.citations?.find(citation => citation.ref === ref))
  .filter(Boolean)

const statusLabel = status => ({
  QUEUED: '等待执行', PLANNING: '正在规划', RETRIEVING: '查找依据', DRAFTING: '整理笔记',
  CRITIQUING: '检查结果', REVISING: '修订结果', RETRYING: '准备重试',
  SUCCEEDED: '已完成', FAILED: '失败'
})[status] || status

const stageDescription = status => ({
  QUEUED: '任务已进入队列', PLANNING: '正在拆解学习目标', RETRIEVING: '正在检索相关证据',
  DRAFTING: '正在整理知识点', CRITIQUING: '正在核对目标、事实与引用',
  REVISING: '正在按检查意见修订', RETRYING: '任务将从最近检查点恢复'
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
  currentRun.value = run
  schedulePoll()
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
.evidence-list { display: grid; gap: 6px; margin-top: 10px; }
.evidence-list button { overflow: hidden; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; background: #0e1014; color: var(--muted); text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.evidence-list button:hover { border-color: var(--border-strong); color: var(--text); }
.evidence-list span { margin-right: 7px; color: var(--accent); font: 10px var(--mono); }
.gaps-block ul { margin-bottom: 0; padding-left: 20px; color: var(--muted); line-height: 1.7; }
.run-metrics { color: var(--muted); font: 10px var(--mono); text-align: right; }
</style>
