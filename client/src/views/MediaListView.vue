<template>
  <main class="page list-page">
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow">VIDEO INTELLIGENCE WORKSPACE</p>
        <h1>让长视频变成<br><span>可理解的内容资产</span></h1>
        <p class="hero-description">上传本地视频，或通过平台字幕解析公开视频链接，在统一工作台完成总结、章节与问答。</p>
      </div>

      <section class="upload-panel" :class="{ busy: uploading, dragover }">
        <div v-if="uploading" class="uploading-state">
          <div class="spinner"></div>
          <strong>{{ uploadLabel }}</strong>
          <div v-if="uploadProgress > 0" class="upload-progress"><span :style="{ width: `${uploadProgress}%` }"></span></div>
          <small>{{ uploadProgress ? `${uploadProgress}%` : (uploadLabel === '正在解析平台字幕' ? '正在读取平台信息与字幕' : '正在准备上传') }}</small>
        </div>
        <div
          v-else
          class="upload-options"
          @dragover.prevent="dragover = true"
          @dragleave.prevent="dragover = false"
          @drop.prevent="handleDrop"
        >
          <label class="upload-option local-option">
            <input type="file" accept="video/*" hidden @change="handleFileInput" />
            <span class="option-index">01</span>
            <div>
              <strong>本地视频</strong>
              <p>{{ dragover ? '松开即可上传' : '点击选择或拖拽视频文件' }}</p>
            </div>
            <span class="option-arrow">↗</span>
          </label>
          <div class="upload-option url-option">
            <span class="option-index">02</span>
            <div class="url-content">
              <strong>链接分析</strong>
              <div class="url-row">
                <input v-model.trim="videoUrl" placeholder="粘贴 Bilibili / YouTube 链接" @keyup.enter="uploadUrl" />
                <button class="primary-button compact" @click="uploadUrl">解析链接</button>
              </div>
              <p>读取平台字幕，不下载视频；V1 不提供站内播放。</p>
            </div>
          </div>
        </div>
      </section>
    </section>

    <section class="library-section">
      <div class="section-heading">
        <div>
          <p class="eyebrow">MEDIA LIBRARY</p>
          <h2>内容工作台</h2>
        </div>
        <span class="count-badge">{{ mediaList.length }} 个视频</span>
      </div>

      <div v-if="!currentUser" class="empty-library">
        <h3>登录后开始构建你的视频知识库</h3>
        <p>上传、分析和工作台数据会关联到当前账号。</p>
        <button class="primary-button" @click="openAuth">登录 / 注册</button>
      </div>
      <div v-else-if="loading" class="empty-library"><div class="spinner"></div><p>正在读取媒体列表…</p></div>
      <div v-else-if="!mediaList.length" class="empty-library">
        <h3>还没有视频</h3>
        <p>从上方上传一个本地 MP4，完成后会直接进入内容工作台。</p>
      </div>
      <div v-else class="media-grid">
        <article v-for="media in mediaList" :key="media.id" class="media-card" @click="openMedia(media.id)">
          <div class="media-visual">
            <img v-if="media.coverUrl" :src="media.coverUrl" alt="" />
            <span class="media-type">{{ mediaLabel(media) }}</span>
            <span class="play-mark">{{ media.playbackAvailable ? '▶' : '↗' }}</span>
          </div>
          <div class="media-card-body">
            <div>
              <h3 :title="media.filename">{{ media.filename }}</h3>
              <p>{{ formatDate(media.uploadTime) }} · {{ media.sourceType === 'EXTERNAL_URL' ? '仅内容分析' : formatSize(media.fileSize) }}</p>
            </div>
            <button class="icon-button danger-hover" aria-label="删除视频" @click.stop="removeMedia(media)">×</button>
          </div>
        </article>
      </div>
    </section>
  </main>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { deleteMedia, importMediaUrl, listMedia, uploadMedia } from '../api/media'
import { submitAnalysis } from '../api/analysis'
import { errorMessage } from '../api/http'
import { useSession } from '../composables/useSession'
import { useToast } from '../composables/useToast'

const router = useRouter()
const { currentUser, openAuth } = useSession()
const { showToast } = useToast()
const mediaList = ref([])
const loading = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadLabel = ref('正在上传视频')
const dragover = ref(false)
const videoUrl = ref('')

const loadMedia = async () => {
  if (!currentUser.value) {
    mediaList.value = []
    return
  }
  loading.value = true
  try {
    mediaList.value = await listMedia(currentUser.value.id)
  } catch (error) {
    showToast(errorMessage(error, '媒体列表加载失败'), 'error')
  } finally {
    loading.value = false
  }
}

watch(currentUser, loadMedia, { immediate: true })

const requireUser = () => {
  if (currentUser.value) return true
  openAuth()
  showToast('请先登录再上传视频', 'error')
  return false
}

const handleFileInput = (event) => {
  const selected = event.target.files?.[0]
  event.target.value = ''
  if (selected) uploadFile(selected)
}

const handleDrop = (event) => {
  dragover.value = false
  const selected = event.dataTransfer.files?.[0]
  if (!selected) return
  if (!selected.type.startsWith('video/')) {
    showToast('请选择视频文件', 'error')
    return
  }
  uploadFile(selected)
}

const uploadFile = async (file) => {
  if (!requireUser()) return
  uploading.value = true
  uploadProgress.value = 0
  uploadLabel.value = `正在上传 ${file.name}`
  try {
    const media = await uploadMedia(file, currentUser.value.id, value => { uploadProgress.value = value })
    showToast('上传完成，正在打开内容工作台')
    await router.push({ name: 'media-workspace', params: { mediaId: media.id } })
  } catch (error) {
    showToast(errorMessage(error, '上传失败'), 'error')
  } finally {
    uploading.value = false
  }
}

const uploadUrl = async () => {
  if (!requireUser() || !videoUrl.value) return
  try {
    const parsed = new URL(videoUrl.value)
    if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error()
  } catch {
    showToast('请输入有效的 HTTP/HTTPS 视频地址', 'error')
    return
  }

  uploading.value = true
  uploadProgress.value = 0
  uploadLabel.value = '正在解析平台字幕'
  try {
    const media = await importMediaUrl(videoUrl.value, currentUser.value.id)
    videoUrl.value = ''
    try {
      await submitAnalysis(media.id)
      showToast('字幕导入完成，AI 分析已开始')
    } catch (error) {
      showToast(errorMessage(error, '字幕已保存，但 AI 任务提交失败，可在工作台重试'), 'error')
    }
    await router.push({ name: 'media-workspace', params: { mediaId: media.id } })
  } catch (error) {
    showToast(errorMessage(error, '网络视频导入失败'), 'error')
  } finally {
    uploading.value = false
  }
}

const removeMedia = async (media) => {
  if (!confirm(`确认删除“${media.filename}”吗？`)) return
  try {
    await deleteMedia(media.id, currentUser.value.id)
    mediaList.value = mediaList.value.filter(item => item.id !== media.id)
    showToast('视频已删除')
  } catch (error) {
    showToast(errorMessage(error, '删除失败'), 'error')
  }
}

const openMedia = (mediaId) => router.push({ name: 'media-workspace', params: { mediaId } })

const formatDate = (value) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '--'
const formatSize = (bytes) => {
  if (bytes == null) return '大小未知'
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}
const mediaLabel = (media) => media.sourcePlatform || media.mimeType || 'VIDEO'
</script>

<style scoped>
.list-page { padding-bottom: 80px; }
.hero { display: grid; grid-template-columns: minmax(0, .9fr) minmax(480px, 1.1fr); gap: 56px; align-items: center; min-height: 580px; padding: 60px 0; }
.hero-copy h1 { margin: 12px 0 22px; font-size: clamp(48px, 6vw, 86px); line-height: .98; letter-spacing: -.055em; }
.hero-copy h1 span { color: var(--accent); }
.hero-description { max-width: 580px; color: var(--muted); font-size: 17px; line-height: 1.8; }
.upload-panel { min-height: 330px; overflow: hidden; border: 1px solid var(--border); border-radius: 24px; background: linear-gradient(145deg, rgba(25, 28, 33, .95), rgba(13, 15, 18, .96)); box-shadow: var(--shadow); }
.upload-panel.dragover { border-color: var(--accent); box-shadow: 0 0 40px rgba(197, 249, 70, .12); }
.upload-options { display: grid; height: 100%; min-height: 330px; grid-template-rows: 1fr 1fr; }
.upload-option { display: grid; grid-template-columns: 44px 1fr auto; gap: 16px; align-items: center; padding: 34px; color: inherit; cursor: pointer; }
.upload-option + .upload-option { border-top: 1px solid var(--border); }
.upload-option:hover { background: rgba(197, 249, 70, .045); }
.option-index { align-self: start; color: var(--accent); font: 12px var(--mono); }
.upload-option strong { font-size: 22px; }
.upload-option p { margin: 7px 0 0; color: var(--muted); }
.option-arrow { color: var(--accent); font-size: 26px; }
.url-content { min-width: 0; }
.url-row { display: flex; gap: 8px; margin-top: 12px; }
.url-row input { min-width: 0; flex: 1; }
.uploading-state { display: grid; min-height: 330px; place-content: center; justify-items: center; gap: 16px; padding: 40px; text-align: center; }
.uploading-state small { color: var(--muted); }
.upload-progress { overflow: hidden; width: min(320px, 70vw); height: 6px; border-radius: 10px; background: #2b2f35; }
.upload-progress span { display: block; height: 100%; background: var(--accent); transition: width .2s; }
.library-section { padding-top: 40px; }
.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 20px; margin-bottom: 24px; }
.section-heading h2 { margin: 5px 0 0; font-size: 34px; }
.count-badge { padding: 7px 11px; border: 1px solid var(--border); border-radius: 99px; color: var(--muted); font-size: 12px; }
.empty-library { display: grid; min-height: 240px; place-content: center; justify-items: center; gap: 10px; border: 1px dashed var(--border); border-radius: 20px; color: var(--muted); text-align: center; }
.empty-library h3, .empty-library p { margin: 0; }
.empty-library .primary-button { margin-top: 12px; }
.media-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.media-card { overflow: hidden; border: 1px solid var(--border); border-radius: 18px; background: var(--panel); cursor: pointer; transition: transform .2s, border-color .2s; }
.media-card:hover { transform: translateY(-4px); border-color: var(--border-strong); }
.media-visual { position: relative; display: grid; aspect-ratio: 16 / 8; place-items: center; background: radial-gradient(circle at 70% 20%, rgba(197, 249, 70, .12), transparent 38%), linear-gradient(135deg, #1c2026, #0e1014); }
.media-visual img { position: absolute; inset: 0; width: 100%; height: 100%; opacity: .58; object-fit: cover; }
.media-type { position: absolute; top: 14px; left: 14px; max-width: calc(100% - 28px); overflow: hidden; color: var(--muted); font: 10px var(--mono); text-overflow: ellipsis; white-space: nowrap; }
.play-mark { position: relative; display: grid; width: 48px; height: 48px; place-items: center; border: 1px solid var(--border-strong); border-radius: 50%; background: rgba(5, 6, 8, .72); color: var(--accent); }
.media-card-body { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 18px; }
.media-card h3 { overflow: hidden; margin: 0 0 7px; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.media-card p { margin: 0; color: var(--muted); font-size: 11px; }
.danger-hover:hover { border-color: var(--danger); color: var(--danger); }
@media (max-width: 980px) { .hero { grid-template-columns: 1fr; gap: 28px; padding-top: 40px; } .media-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 640px) { .hero { min-height: auto; } .hero-copy h1 { font-size: 46px; } .upload-option { padding: 24px 18px; } .media-grid { grid-template-columns: 1fr; } .url-row { align-items: stretch; flex-direction: column; } }
</style>
