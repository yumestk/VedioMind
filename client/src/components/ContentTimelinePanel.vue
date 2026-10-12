<template>
  <div v-if="timeline.length" class="timeline-panel">
    <div v-if="segments.length" class="timeline-toolbar">
      <label class="search-box">
        <span>⌕</span>
        <input v-model.trim="query" placeholder="搜索字幕内容" />
      </label>
      <button
        v-if="playbackSyncEnabled"
        class="secondary-button compact"
        :class="{ active: followPlayback }"
        @click="toggleFollow"
      >
        {{ followPlayback ? '跟随中' : '跟随播放' }}
      </button>
      <button class="secondary-button compact" @click="copyTranscript">复制</button>
      <button class="secondary-button compact" @click="download('txt')">TXT</button>
      <button class="secondary-button compact" @click="download('md')">Markdown</button>
    </div>

    <div v-if="query" class="search-results">
      <p class="match-count">找到 {{ searchResults.length }} 条匹配字幕</p>
      <button
        v-for="result in searchResults"
        :key="result.segment.id"
        class="search-result"
        @click="$emit('seek', result.segment.startMs)"
      >
        <span class="result-time">{{ formatTime(result.segment.startMs) }}</span>
        <span class="result-content">
          <strong>{{ result.segment.text }}</strong>
          <small>{{ result.section?.title || formatRange(result.section) }}</small>
        </span>
      </button>
      <p v-if="!searchResults.length" class="no-results">没有找到匹配字幕</p>
    </div>

    <div v-else class="timeline-sections">
      <article
        v-for="section in timeline"
        :key="section.id"
        class="timeline-section"
        :class="{ active: section.id === activeSection?.id }"
      >
        <div class="section-header">
          <button
            class="section-toggle"
            :aria-expanded="expandedSectionId === section.id"
            @click="toggleSection(section.id)"
          >
            <span class="section-index">{{ String(section.index + 1).padStart(2, '0') }}</span>
            <span class="section-title">{{ section.synthetic ? formatRange(section) : section.title }}</span>
            <span class="section-caret">{{ expandedSectionId === section.id ? '−' : '+' }}</span>
          </button>
          <button class="section-time" @click="navigateSection(section)">{{ formatTime(section.startMs) }}</button>
        </div>

        <div v-if="expandedSectionId === section.id" class="paragraph-list">
          <button
            v-for="paragraph in section.paragraphs"
            :key="paragraph.id"
            :ref="element => setParagraphElement(paragraph.id, element)"
            class="paragraph-row"
            :class="{ active: paragraph.id === activeParagraph?.id }"
            @click="$emit('seek', paragraph.startMs)"
          >
            <span class="paragraph-time">{{ formatTime(paragraph.startMs) }}</span>
            <span class="paragraph-text">{{ paragraph.text }}</span>
          </button>
          <p v-if="!section.paragraphs.length" class="no-results">这个章节暂时没有字幕。</p>
        </div>
      </article>
    </div>
  </div>

  <div v-else class="empty-content">
    <span>⌁</span>
    <h3>还没有内容时间轴</h3>
    <p>完成视频分析后，章节和段落式字幕会显示在这里。</p>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useToast } from '../composables/useToast'
import { buildContentTimeline, timelineSegmentSections } from '../utils/contentTimeline'

const props = defineProps({
  chapters: { type: Array, default: () => [] },
  segments: { type: Array, default: () => [] },
  currentTimeMs: { type: Number, default: 0 },
  filename: { type: String, default: 'transcript' },
  playbackSyncEnabled: { type: Boolean, default: true }
})

const emit = defineEmits(['seek'])
const { showToast } = useToast()
const query = ref('')
const followPlayback = ref(true)
const expandedSectionId = ref(null)
const paragraphElements = new Map()

const timeline = computed(() => buildContentTimeline(props.chapters, props.segments))
const sectionBySegmentId = computed(() => timelineSegmentSections(timeline.value))

const activeSection = computed(() => {
  let active = null
  for (const section of timeline.value) {
    if (section.startMs > props.currentTimeMs) break
    active = section
  }
  return active
})

const activeParagraph = computed(() => {
  const section = activeSection.value
  if (!section) return null
  return section.paragraphs.find(paragraph => (
    paragraph.startMs <= props.currentTimeMs && props.currentTimeMs < paragraph.endMs
  )) || null
})

const searchResults = computed(() => {
  const normalizedQuery = query.value.toLocaleLowerCase()
  if (!normalizedQuery) return []
  return props.segments
    .filter(segment => segment.text.toLocaleLowerCase().includes(normalizedQuery))
    .map(segment => ({ segment, section: sectionBySegmentId.value.get(segment.id) }))
})

watch(timeline, sections => {
  if (!sections.length) {
    expandedSectionId.value = null
    return
  }
  if (!sections.some(section => section.id === expandedSectionId.value)) {
    expandedSectionId.value = sections[0].id
  }
}, { immediate: true })

watch(query, value => {
  if (value) followPlayback.value = false
})

watch(() => [activeSection.value?.id, activeParagraph.value?.id], async ([sectionId, paragraphId]) => {
  if (!props.playbackSyncEnabled || !followPlayback.value || query.value || !sectionId) return
  expandedSectionId.value = sectionId
  if (!paragraphId) return
  await nextTick()
  paragraphElements.get(paragraphId)?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
})

const setParagraphElement = (paragraphId, element) => {
  if (element) paragraphElements.set(paragraphId, element)
  else paragraphElements.delete(paragraphId)
}

const toggleSection = (sectionId) => {
  followPlayback.value = false
  expandedSectionId.value = expandedSectionId.value === sectionId ? null : sectionId
}

const navigateSection = (section) => {
  expandedSectionId.value = section.id
  emit('seek', section.startMs)
}

const toggleFollow = async () => {
  followPlayback.value = !followPlayback.value
  if (!followPlayback.value) return
  query.value = ''
  if (activeSection.value) expandedSectionId.value = activeSection.value.id
  if (!activeParagraph.value) return
  await nextTick()
  paragraphElements.get(activeParagraph.value.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
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

const formatRange = (section) => section
  ? `${formatTime(section.startMs)}–${formatTime(section.endMs)}`
  : '未分组'

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
.timeline-panel { display: grid; gap: 18px; }
.timeline-toolbar { display: flex; flex-wrap: wrap; gap: 8px; }
.search-box { display: flex; flex: 1 1 220px; align-items: center; gap: 8px; padding: 0 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
.search-box input { min-width: 0; border: 0; background: transparent; }
.secondary-button.active { border-color: var(--accent); color: var(--accent); }
.match-count, .no-results { margin: 0; color: var(--muted); font-size: 12px; }
.match-count { color: var(--accent); }
.search-results, .timeline-sections { display: grid; gap: 8px; }
.search-result { display: grid; grid-template-columns: 58px minmax(0, 1fr); gap: 12px; width: 100%; padding: 12px; border: 1px solid transparent; border-radius: 10px; background: transparent; color: var(--text); text-align: left; }
.search-result:hover { border-color: var(--border); background: var(--surface); }
.result-time, .paragraph-time, .section-time { color: var(--accent); font: 10px var(--mono); }
.result-content { display: grid; gap: 5px; }
.result-content strong { overflow-wrap: anywhere; font-size: 14px; line-height: 1.6; }
.result-content small { color: var(--muted); }
.timeline-section { overflow: hidden; border: 1px solid var(--border); border-radius: 12px; background: rgba(255, 255, 255, .012); }
.timeline-section.active { border-color: rgba(197, 249, 70, .34); }
.section-header { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: stretch; }
.section-toggle, .section-time { border: 0; background: transparent; }
.section-toggle { display: grid; grid-template-columns: 30px minmax(0, 1fr) 20px; align-items: center; gap: 10px; padding: 15px 10px 15px 14px; color: var(--text); text-align: left; }
.section-toggle:hover, .section-time:hover { background: var(--surface); }
.section-index { color: var(--muted); font: 10px var(--mono); }
.section-title { overflow-wrap: anywhere; font-size: 14px; font-weight: 700; line-height: 1.45; }
.section-caret { color: var(--muted); font-size: 18px; text-align: center; }
.section-time { padding: 0 14px; border-left: 1px solid var(--border); }
.paragraph-list { display: grid; padding: 0 10px 10px; border-top: 1px solid var(--border); }
.paragraph-row { display: grid; grid-template-columns: 58px minmax(0, 1fr); gap: 12px; width: 100%; padding: 14px 10px; border: 0; border-bottom: 1px solid var(--border); background: transparent; color: #cdd0d5; text-align: left; }
.paragraph-row:last-of-type { border-bottom: 0; }
.paragraph-row:hover { background: var(--surface); }
.paragraph-row.active { background: rgba(197, 249, 70, .08); color: var(--text); }
.paragraph-text { overflow-wrap: anywhere; line-height: 1.8; }
.paragraph-list .no-results { padding: 18px 10px 8px; }
@media (max-width: 640px) {
  .section-toggle { grid-template-columns: 24px minmax(0, 1fr) 18px; padding-left: 10px; }
  .section-time { padding: 0 10px; }
  .paragraph-row, .search-result { grid-template-columns: 48px minmax(0, 1fr); gap: 8px; }
}
</style>
