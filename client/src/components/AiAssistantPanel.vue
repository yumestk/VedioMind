<template>
  <section class="assistant-panel">
    <div v-if="isStudyMode" class="assistant-mode-switch" role="tablist" aria-label="AI 助手模式">
      <button
        type="button"
        role="tab"
        :aria-selected="mode === 'quick'"
        :class="{ active: mode === 'quick' }"
        @click="mode = 'quick'"
      >
        <strong>快速问答</strong>
        <span>多轮对话与时间引用</span>
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="mode === 'deep'"
        :class="{ active: mode === 'deep' }"
        @click="mode = 'deep'"
      >
        <strong>深度学习任务</strong>
        <span>规划、检索与结果校验</span>
      </button>
    </div>

    <ConversationPanel
      v-show="mode === 'quick'"
      :media-id="mediaId"
      :user-id="userId"
      :has-transcript="hasTranscript"
      :analysis-mode="analysisMode"
      @seek="$emit('seek', $event)"
    />
    <StudyAgentPanel
      v-if="isStudyMode"
      v-show="mode === 'deep'"
      :media-id="mediaId"
      :user-id="userId"
      :has-transcript="hasTranscript"
      @seek="$emit('seek', $event)"
    />
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import ConversationPanel from './ConversationPanel.vue'
import StudyAgentPanel from './StudyAgentPanel.vue'

const props = defineProps({
  mediaId: { type: Number, required: true },
  userId: { type: Number, required: true },
  hasTranscript: { type: Boolean, default: false },
  analysisMode: { type: String, default: '' }
})

defineEmits(['seek'])

const mode = ref('quick')
const isStudyMode = computed(() => props.analysisMode === 'STUDY')

watch(() => props.mediaId, () => { mode.value = 'quick' })
watch(isStudyMode, studyMode => { if (!studyMode) mode.value = 'quick' })
</script>

<style scoped>
.assistant-panel { min-height: 526px; }
.assistant-mode-switch { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-bottom: 24px; padding: 4px; border: 1px solid var(--border); border-radius: 12px; background: #0e1014; }
.assistant-mode-switch button { display: grid; gap: 4px; padding: 10px 12px; border: 0; border-radius: 8px; background: transparent; color: var(--muted); text-align: left; }
.assistant-mode-switch button.active { background: var(--surface); color: var(--text); }
.assistant-mode-switch button.active strong { color: var(--accent); }
.assistant-mode-switch span { font-size: 10px; }
@media (max-width: 520px) { .assistant-mode-switch { grid-template-columns: 1fr; } }
</style>
