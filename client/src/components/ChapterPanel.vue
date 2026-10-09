<template>
  <div v-if="chapters.length" class="chapter-panel">
    <div class="chapter-heading">
      <div>
        <p class="eyebrow">VIDEO CHAPTERS</p>
        <h3>章节时间轴</h3>
      </div>
      <span>{{ chapters.length }} 章</span>
    </div>
    <div class="chapter-list">
      <button
        v-for="chapter in chapters"
        :key="chapter.id"
        class="chapter-row"
        :class="{ active: chapter.id === activeChapterId }"
        :aria-current="chapter.id === activeChapterId ? 'true' : undefined"
        @click="$emit('seek', chapter.startMs)"
      >
        <span class="timeline-marker"><i></i></span>
        <span class="chapter-index">{{ String(chapter.index + 1).padStart(2, '0') }}</span>
        <span class="chapter-content">
          <strong>{{ chapter.title }}</strong>
          <small>{{ formatTime(chapter.startMs) }}</small>
        </span>
      </button>
    </div>
  </div>
  <div v-else class="empty-content">
    <span>⌁</span>
    <h3>还没有自动章节</h3>
    <p>完成视频分析后，系统会根据字幕主题生成可跳转的章节时间轴。</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  chapters: { type: Array, default: () => [] },
  currentTimeMs: { type: Number, default: 0 }
})

defineEmits(['seek'])

const activeChapterId = computed(() => {
  let left = 0
  let right = props.chapters.length - 1
  let active = null

  while (left <= right) {
    const middle = Math.floor((left + right) / 2)
    const chapter = props.chapters[middle]
    if (chapter.startMs <= props.currentTimeMs) {
      active = chapter
      left = middle + 1
    } else {
      right = middle - 1
    }
  }
  return active?.id || null
})

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
.chapter-panel { display: grid; gap: 22px; }
.chapter-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; }
.chapter-heading h3 { margin: 4px 0 0; font-size: 18px; }
.chapter-heading > span { color: var(--muted); font: 10px var(--mono); }
.chapter-list { display: grid; }
.chapter-row { position: relative; display: grid; grid-template-columns: 22px 34px minmax(0, 1fr); gap: 10px; width: 100%; padding: 0 10px 22px 0; border: 0; background: transparent; color: var(--muted); text-align: left; }
.chapter-row:last-child { padding-bottom: 0; }
.timeline-marker { position: relative; display: flex; justify-content: center; align-self: stretch; }
.timeline-marker::after { position: absolute; top: 13px; bottom: -9px; width: 1px; background: var(--border); content: ''; }
.chapter-row:last-child .timeline-marker::after { display: none; }
.timeline-marker i { position: relative; z-index: 1; width: 9px; height: 9px; margin-top: 4px; border: 2px solid var(--border-strong); border-radius: 50%; background: var(--panel); transition: border-color .15s, background .15s, box-shadow .15s; }
.chapter-index { padding-top: 2px; font: 10px var(--mono); }
.chapter-content { display: grid; gap: 5px; padding: 0 10px 12px; border-bottom: 1px solid var(--border); }
.chapter-content strong { color: #cdd0d5; font-size: 15px; line-height: 1.45; transition: color .15s; }
.chapter-content small { color: var(--accent); font: 10px var(--mono); }
.chapter-row:hover .chapter-content strong, .chapter-row.active .chapter-content strong { color: var(--text); }
.chapter-row.active .timeline-marker i { border-color: var(--accent); background: var(--accent); box-shadow: 0 0 12px rgba(197, 249, 70, .55); }
.chapter-row.active .chapter-index { color: var(--accent); }
</style>
