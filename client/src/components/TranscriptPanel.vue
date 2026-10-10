<template>
  <div v-if="segments.length" class="transcript-panel">
    <div class="transcript-toolbar">
      <label class="search-box">
        <span>⌕</span>
        <input v-model.trim="query" placeholder="搜索字幕内容" />
      </label>
      <button v-if="seekable" class="secondary-button compact" :class="{ active: followPlayback }" @click="toggleFollow">
        {{ followPlayback ? '跟随中' : '跟随播放' }}
      </button>
      <button class="secondary-button compact" @click="copyTranscript">复制</button>
      <button class="secondary-button compact" @click="download('txt')">TXT</button>
      <button class="secondary-button compact" @click="download('md')">Markdown</button>
    </div>
    <p v-if="query" class="match-count">找到 {{ visibleSegments.length }} 条匹配字幕</p>
    <div class="segment-list">
      <button
        v-for="segment in visibleSegments"
        :key="segment.id"
        :ref="element => setSegmentElement(segment.id, element)"
        class="segment-row"
        :class="{ active: segment.id === activeSegmentId }"
        @click="$emit('seek', segment.startMs)"
      >
        <span class="segment-time">{{ formatTime(segment.startMs) }}</span>
        <span class="segment-text">{{ segment.text }}</span>
      </button>
    </div>
  </div>
  <div v-else class="empty-content">
    <span>⌁</span>
    <h3>还没有时间戳字幕</h3>
    <p>完成视频分析后，句子级字幕会显示在这里并与播放器同步。</p>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useToast } from '../composables/useToast'

const props = defineProps({
  segments: { type: Array, default: () => [] },
  currentTimeMs: { type: Number, default: 0 },
  filename: { type: String, default: 'transcript' },
  seekable: { type: Boolean, default: true }
})

defineEmits(['seek'])

const query = ref('')
const followPlayback = ref(true)
const segmentElements = new Map()
const { showToast } = useToast()

const visibleSegments = computed(() => {
  if (!query.value) return props.segments
  const normalizedQuery = query.value.toLocaleLowerCase()
  return props.segments.filter(segment => segment.text.toLocaleLowerCase().includes(normalizedQuery))
})

const activeSegmentId = computed(() => {
  if (!props.seekable) return null
  let left = 0
  let right = props.segments.length - 1
  let candidate = null

  while (left <= right) {
    const middle = Math.floor((left + right) / 2)
    const segment = props.segments[middle]
    if (segment.startMs <= props.currentTimeMs) {
      candidate = segment
      left = middle + 1
    } else {
      right = middle - 1
    }
  }

  if (!candidate || props.currentTimeMs >= candidate.endMs) return null
  return candidate.id
})

watch(query, value => {
  if (value) followPlayback.value = false
})

watch(activeSegmentId, async segmentId => {
  if (!segmentId || !followPlayback.value || query.value) return
  await nextTick()
  segmentElements.get(segmentId)?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
})

const setSegmentElement = (segmentId, element) => {
  if (element) segmentElements.set(segmentId, element)
  else segmentElements.delete(segmentId)
}

const toggleFollow = async () => {
  followPlayback.value = !followPlayback.value
  if (!followPlayback.value) return
  query.value = ''
  if (!activeSegmentId.value) return
  await nextTick()
  segmentElements.get(activeSegmentId.value)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

const plainTranscript = computed(() => props.segments.map(segment => segment.text).join('\n'))

const copyTranscript = async () => {
  try {
    await navigator.clipboard.writeText(plainTranscript.value)
    showToast('字幕已复制')
  } catch {
    showToast('浏览器未允许写入剪贴板', 'error')
  }
}

const download = (extension) => {
  const baseName = props.filename.replace(/\.[^/.]+$/, '')
  const lines = props.segments.map(segment => `[${formatTime(segment.startMs)}] ${segment.text}`)
  const content = extension === 'md'
    ? `# ${baseName} 字幕\n\n${lines.map(line => `- ${line}`).join('\n')}`
    : lines.join('\n')
  const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `${baseName}.${extension}`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
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
.transcript-panel { display: grid; gap: 16px; }
.transcript-toolbar { display: flex; flex-wrap: wrap; gap: 8px; }
.search-box { display: flex; flex: 1 1 220px; align-items: center; gap: 8px; padding: 0 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
.search-box input { min-width: 0; border: 0; background: transparent; }
.secondary-button.active { border-color: var(--accent); color: var(--accent); }
.match-count { margin: 0; color: var(--accent); font-size: 12px; }
.segment-list { display: grid; gap: 5px; }
.segment-row { display: grid; grid-template-columns: 58px minmax(0, 1fr); gap: 12px; width: 100%; padding: 12px; border: 1px solid transparent; border-radius: 10px; background: transparent; color: #cdd0d5; text-align: left; transition: border-color .15s, background .15s, color .15s; }
.segment-row:hover { border-color: var(--border); background: var(--surface); }
.segment-row.active { border-color: rgba(197, 249, 70, .38); background: rgba(197, 249, 70, .08); color: var(--text); }
.segment-time { padding-top: 2px; color: var(--accent); font: 10px var(--mono); }
.segment-text { overflow-wrap: anywhere; line-height: 1.7; }
</style>
