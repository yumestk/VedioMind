<template>
  <article v-if="summary" class="markdown-body" v-html="renderedSummary"></article>
  <div v-else class="empty-content">
    <span>✦</span>
    <h3>还没有 AI 总结</h3>
    <p>启动分析后，系统会基于一次完整转写生成结构化总结。</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

const props = defineProps({ summary: { type: String, default: '' } })

const renderedSummary = computed(() => {
  if (!props.summary) return ''
  let content = props.summary.replace(/<think>[\s\S]*?<\/think>/gi, '')
  if (content.includes('</think>')) content = content.split('</think>').pop()
  if (!content.trim()) content = props.summary
  return DOMPurify.sanitize(marked.parse(content))
})
</script>
