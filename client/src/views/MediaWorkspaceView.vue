<template>
  <main class="page workspace-page">
    <div class="workspace-topbar">
      <router-link class="back-link" to="/">← 返回内容列表</router-link>
      <span v-if="media" class="media-state">{{ media.status }}</span>
    </div>

    <section v-if="!currentUser" class="workspace-empty">
      <h1>请先登录</h1>
      <p>媒体详情与分析任务需要关联当前账号。</p>
      <button class="primary-button" @click="openAuth">登录 / 注册</button>
    </section>
    <section v-else-if="loading" class="workspace-empty"><div class="spinner"></div><p>正在载入视频内容工作台…</p></section>
    <section v-else-if="loadError" class="workspace-empty">
      <h1>无法打开这个视频</h1>
      <p>{{ loadError }}</p>
      <router-link class="primary-button" to="/">返回列表</router-link>
    </section>
    <template v-else-if="media">
      <header class="workspace-heading">
        <div>
          <p class="eyebrow">MEDIA WORKSPACE / #{{ media.id }}</p>
          <h1>{{ media.filename }}</h1>
        </div>
        <div class="media-meta">
          <span>{{ formatSize(media.fileSize) }}</span>
          <span>{{ media.mimeType || '类型未知' }}</span>
          <span>{{ formatDate(media.uploadTime) }}</span>
        </div>
      </header>

      <div class="workspace-grid">
        <div class="player-column">
          <VideoPlayer
            ref="player"
            :src="media.playbackUrl"
            :poster="media.coverUrl"
            @time-update="currentTimeMs = $event"
          />
          <AnalysisPanel
            :job="job"
            :running="running"
            :submitting="submitting"
            :error="analysisError"
            :has-result="Boolean(media.summary)"
            @submit="startAnalysis"
          />
        </div>

        <section class="content-column">
          <div class="content-tabs" role="tablist">
            <button :class="{ active: activeTab === 'summary' }" @click="activeTab = 'summary'">AI 总结</button>
            <button :class="{ active: activeTab === 'transcript' }" @click="activeTab = 'transcript'">完整字幕</button>
            <button :class="{ active: activeTab === 'question' }" @click="activeTab = 'question'">视频问答</button>
          </div>
          <div class="content-scroll">
            <SummaryPanel v-show="activeTab === 'summary'" :summary="media.summary" />
            <TranscriptPanel
              v-show="activeTab === 'transcript'"
              :segments="transcriptSegments"
              :current-time-ms="currentTimeMs"
              :filename="media.filename"
              @seek="seekTo"
            />
            <QuestionPanel
              v-show="activeTab === 'question'"
              :media-id="numericMediaId"
              :user-id="currentUser.id"
              :has-transcript="Boolean(transcriptSegments.length)"
              @seek="seekTo"
            />
          </div>
        </section>
      </div>
    </template>
  </main>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { getMediaDetail, getMediaTranscript } from '../api/media'
import { errorMessage } from '../api/http'
import AnalysisPanel from '../components/AnalysisPanel.vue'
import QuestionPanel from '../components/QuestionPanel.vue'
import SummaryPanel from '../components/SummaryPanel.vue'
import TranscriptPanel from '../components/TranscriptPanel.vue'
import VideoPlayer from '../components/VideoPlayer.vue'
import { useAnalysisJob } from '../composables/useAnalysisJob'
import { useSession } from '../composables/useSession'
import { useToast } from '../composables/useToast'

const props = defineProps({ mediaId: { type: String, required: true } })
const { currentUser, openAuth } = useSession()
const { showToast } = useToast()
const media = ref(null)
const loading = ref(false)
const loadError = ref('')
const activeTab = ref('summary')
const transcriptSegments = ref([])
const currentTimeMs = ref(0)
const player = ref(null)
const numericMediaId = Number(props.mediaId)

const loadDetail = async () => {
  if (!currentUser.value) return
  loading.value = true
  loadError.value = ''
  try {
    const [detail, transcript] = await Promise.all([
      getMediaDetail(numericMediaId, currentUser.value.id),
      getMediaTranscript(numericMediaId, currentUser.value.id)
    ])
    media.value = detail
    transcriptSegments.value = transcript.segments
  } catch (error) {
    loadError.value = errorMessage(error, '媒体详情加载失败')
  } finally {
    loading.value = false
  }
}

const { job, running, submitting, error: analysisError, restore, submit } = useAnalysisJob(
  numericMediaId,
  async () => {
    await loadDetail()
    activeTab.value = 'summary'
    showToast('AI 分析完成')
  }
)

const initialize = async () => {
  if (!currentUser.value) {
    openAuth()
    return
  }
  await loadDetail()
  if (media.value) await restore()
}

const startAnalysis = async () => {
  try {
    await submit()
  } catch (error) {
    showToast(errorMessage(error, '任务提交失败'), 'error')
  }
}

watch(currentUser, (user, previous) => {
  if (user && !previous) initialize()
  if (!user) {
    media.value = null
    transcriptSegments.value = []
  }
})

onMounted(initialize)

const seekTo = (milliseconds) => {
  player.value?.seekTo(milliseconds)
}

const formatDate = (value) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '--'
const formatSize = (bytes) => {
  if (bytes == null) return '大小未知'
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}
</script>

<style scoped>
.workspace-page { padding-top: 32px; padding-bottom: 70px; }
.workspace-topbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 34px; }
.back-link { color: var(--muted); font-size: 13px; }
.back-link:hover { color: var(--accent); }
.media-state { padding: 6px 10px; border: 1px solid var(--border); border-radius: 99px; color: var(--accent); font: 10px var(--mono); }
.workspace-heading { display: flex; align-items: end; justify-content: space-between; gap: 30px; margin-bottom: 24px; }
.workspace-heading h1 { max-width: 850px; margin: 6px 0 0; overflow-wrap: anywhere; font-size: clamp(28px, 4vw, 50px); line-height: 1.08; }
.media-meta { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.media-meta span { padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px; color: var(--muted); font: 10px var(--mono); }
.workspace-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(360px, .8fr); gap: 22px; align-items: start; }
.player-column { display: grid; gap: 18px; }
.content-column { overflow: hidden; min-height: 640px; border: 1px solid var(--border); border-radius: 18px; background: var(--panel); box-shadow: var(--shadow); }
.content-tabs { display: flex; gap: 4px; padding: 10px; border-bottom: 1px solid var(--border); }
.content-tabs button { flex: 1; padding: 11px; border: 0; border-radius: 9px; background: transparent; color: var(--muted); }
.content-tabs button.active { background: var(--surface); color: var(--accent); }
.content-scroll { overflow: auto; height: 578px; padding: 26px; }
.workspace-empty { display: grid; min-height: 60vh; place-content: center; justify-items: center; gap: 12px; color: var(--muted); text-align: center; }
.workspace-empty h1, .workspace-empty p { margin: 0; }
@media (max-width: 1000px) { .workspace-grid { grid-template-columns: 1fr; } .content-column { min-height: 520px; } .content-scroll { height: auto; min-height: 460px; } }
@media (max-width: 640px) { .workspace-heading { align-items: flex-start; flex-direction: column; } .media-meta { justify-content: flex-start; } }
</style>
