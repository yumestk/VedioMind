<template>
  <section class="analysis-panel">
    <div class="analysis-heading">
      <div>
        <p class="eyebrow">ANALYSIS JOB</p>
        <h3>{{ statusLabel }}</h3>
      </div>
      <strong v-if="job" class="progress-value">{{ job.progress }}%</strong>
    </div>

    <template v-if="job">
      <div class="progress-track"><div class="progress-fill" :style="{ width: `${job.progress}%` }"></div></div>
      <div class="stage-list">
        <div v-for="(stage, index) in stages" :key="stage.status" class="stage" :class="stageClass(index)">
          <span></span>{{ stage.label }}
        </div>
      </div>
      <p v-if="job.status === 'RETRYING'" class="status-note">正在等待第 {{ job.retryCount }} 次重试</p>
    </template>

    <p v-if="error" class="analysis-error">{{ error }}</p>
    <button class="primary-button" :disabled="running || submitting || hasResult" @click="$emit('submit')">
      {{ running ? '分析进行中' : submitting ? '正在提交…' : hasResult ? '分析已完成' : '开始 AI 分析' }}
    </button>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  job: { type: Object, default: null },
  running: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false },
  error: { type: String, default: '' },
  hasResult: { type: Boolean, default: false }
})

defineEmits(['submit'])

const stages = [
  { status: 'QUEUED', progress: 0, label: '排队' },
  { status: 'EXTRACTING_AUDIO', progress: 10, label: '音频' },
  { status: 'TRANSCRIBING', progress: 45, label: '转写' },
  { status: 'SUMMARIZING', progress: 70, label: '总结' },
  { status: 'GENERATING_CHAPTERS', progress: 90, label: '章节' },
  { status: 'SUCCEEDED', progress: 100, label: '完成' }
]

const labels = {
  QUEUED: '任务已进入队列',
  EXTRACTING_AUDIO: '正在提取音频',
  TRANSCRIBING: '正在识别语音',
  SUMMARIZING: '正在生成总结',
  GENERATING_CHAPTERS: '正在生成章节',
  RETRYING: '任务异常，准备重试',
  SUCCEEDED: '分析完成',
  FAILED: '分析失败'
}

const statusLabel = computed(() => props.job ? labels[props.job.status] || props.job.status : '准备分析视频内容')

const stageClass = (index) => {
  if (!props.job) return ''
  if (props.job.status === 'SUCCEEDED') return 'completed'
  const current = stages.findIndex(stage => stage.status === props.job.status)
  const effective = current >= 0
    ? current
    : Math.max(0, stages.findLastIndex(stage => stage.progress <= (props.job.progress || 0)))
  if (index < effective) return 'completed'
  if (index === effective) return props.job.status === 'FAILED' ? 'failed' : 'active'
  return ''
}
</script>

<style scoped>
.analysis-panel { display: grid; gap: 16px; padding: 20px; border: 1px solid var(--border); border-radius: 16px; background: var(--panel); }
.analysis-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.analysis-heading h3 { margin: 4px 0 0; font-size: 17px; }
.progress-value { color: var(--accent); font-family: var(--mono); }
.progress-track { overflow: hidden; height: 6px; border-radius: 99px; background: #272b31; }
.progress-fill { height: 100%; border-radius: inherit; background: var(--accent); transition: width .35s ease; }
.stage-list { display: grid; grid-template-columns: repeat(6, 1fr); gap: 6px; }
.stage { display: grid; justify-items: center; gap: 5px; color: var(--muted); font-size: 10px; }
.stage span { width: 8px; height: 8px; border: 1px solid var(--border-strong); border-radius: 50%; }
.stage.active, .stage.completed { color: var(--text); }
.stage.active span { border-color: var(--accent); box-shadow: 0 0 12px var(--accent); }
.stage.completed span { border-color: var(--accent); background: var(--accent); }
.stage.failed { color: var(--danger); }
.stage.failed span { border-color: var(--danger); background: var(--danger); }
.status-note { margin: 0; color: var(--warning); font-size: 12px; }
.analysis-error { margin: 0; color: var(--danger); font-size: 13px; }
</style>
