<template>
  <div v-if="transcript" class="transcript-panel">
    <div class="transcript-toolbar">
      <label class="search-box">
        <span>⌕</span>
        <input v-model.trim="query" placeholder="搜索字幕内容" />
      </label>
      <button class="secondary-button compact" @click="copyTranscript">复制</button>
      <button class="secondary-button compact" @click="download('txt')">TXT</button>
      <button class="secondary-button compact" @click="download('md')">Markdown</button>
    </div>
    <p v-if="query" class="match-count">找到 {{ matchCount }} 处匹配</p>
    <div class="transcript-text">
      <template v-for="(part, index) in highlightedParts" :key="index">
        <mark v-if="part.match">{{ part.text }}</mark><template v-else>{{ part.text }}</template>
      </template>
    </div>
  </div>
  <div v-else class="empty-content">
    <span>⌁</span>
    <h3>还没有字幕</h3>
    <p>完成视频分析后，完整 Transcript 会显示在这里。</p>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useToast } from '../composables/useToast'

const props = defineProps({
  transcript: { type: String, default: '' },
  filename: { type: String, default: 'transcript' }
})

const query = ref('')
const { showToast } = useToast()

const escapedQuery = computed(() => query.value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))

const highlightedParts = computed(() => {
  if (!query.value) return [{ text: props.transcript, match: false }]
  const matcher = new RegExp(`(${escapedQuery.value})`, 'gi')
  return props.transcript.split(matcher).filter(Boolean).map(text => ({
    text,
    match: text.toLocaleLowerCase().includes(query.value.toLocaleLowerCase())
  }))
})

const matchCount = computed(() => highlightedParts.value.filter(part => part.match).length)

const copyTranscript = async () => {
  try {
    await navigator.clipboard.writeText(props.transcript)
    showToast('字幕已复制')
  } catch {
    showToast('浏览器未允许读取剪贴板', 'error')
  }
}

const download = (extension) => {
  const baseName = props.filename.replace(/\.[^/.]+$/, '')
  const content = extension === 'md' ? `# ${baseName} 字幕\n\n${props.transcript}` : props.transcript
  const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `${baseName}.${extension}`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.transcript-panel { display: grid; gap: 16px; }
.transcript-toolbar { display: flex; flex-wrap: wrap; gap: 8px; }
.search-box { display: flex; flex: 1 1 220px; align-items: center; gap: 8px; padding: 0 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
.search-box input { min-width: 0; border: 0; background: transparent; }
.match-count { margin: 0; color: var(--accent); font-size: 12px; }
.transcript-text { overflow-wrap: anywhere; color: #cdd0d5; line-height: 1.9; white-space: pre-wrap; }
mark { padding: 1px 3px; border-radius: 3px; background: var(--accent); color: #11150a; }
</style>
