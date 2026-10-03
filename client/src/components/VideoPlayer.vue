<template>
  <section class="video-shell">
    <video
      v-if="src"
      ref="video"
      :key="src"
      class="video-element"
      :src="src"
      :poster="poster || undefined"
      controls
      preload="metadata"
      @error="playbackError = true"
      @loadedmetadata="handleLoadedMetadata"
      @timeupdate="emitCurrentTime"
      @seeked="emitCurrentTime"
    />
    <div v-else class="video-placeholder">
      <span class="placeholder-icon">▶</span>
      <p>播放地址暂不可用</p>
    </div>
    <div v-if="playbackError" class="video-error">
      <strong>浏览器无法播放这个视频</strong>
      <span>文件仍可进行 AI 分析；V1 暂未提供自动转码。</span>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue'

const emit = defineEmits(['time-update'])

defineProps({
  src: { type: String, default: '' },
  poster: { type: String, default: '' }
})

const video = ref(null)
const playbackError = ref(false)

const emitCurrentTime = () => {
  emit('time-update', Math.round((video.value?.currentTime || 0) * 1000))
}

const handleLoadedMetadata = () => {
  playbackError.value = false
  emitCurrentTime()
}

const seekTo = (milliseconds) => {
  if (!video.value) return
  video.value.currentTime = milliseconds / 1000
  video.value.play().catch(() => {})
}

defineExpose({ seekTo })
</script>

<style scoped>
.video-shell { position: relative; overflow: hidden; width: 100%; aspect-ratio: 16 / 9; border: 1px solid var(--border); border-radius: 18px; background: #050608; box-shadow: var(--shadow); }
.video-element { display: block; width: 100%; height: 100%; background: #000; object-fit: contain; }
.video-placeholder { display: grid; height: 100%; place-content: center; justify-items: center; color: var(--muted); }
.placeholder-icon { display: grid; width: 58px; height: 58px; place-items: center; border: 1px solid var(--border-strong); border-radius: 50%; color: var(--accent); }
.video-error { position: absolute; inset: auto 18px 18px; display: grid; gap: 4px; padding: 14px 16px; border: 1px solid rgba(255, 107, 107, .35); border-radius: 12px; background: rgba(23, 9, 12, .92); color: var(--danger); }
.video-error span { color: #d7a7aa; font-size: 13px; }
</style>
