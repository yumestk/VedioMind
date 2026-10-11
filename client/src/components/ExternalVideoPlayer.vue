<template>
  <section class="external-player-card">
    <div class="external-frame">
      <iframe
        v-if="embedUrl"
        :key="embedUrl"
        :src="embedUrl"
        :title="`${platform || '外部'} 视频播放器`"
        allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
        allowfullscreen
        referrerpolicy="strict-origin-when-cross-origin"
      ></iframe>
      <template v-else>
        <img v-if="coverUrl" :src="coverUrl" alt="视频封面" referrerpolicy="no-referrer" />
        <div class="external-fallback">
          <strong>暂时无法加载平台播放器</strong>
          <span>请使用下方入口在原平台查看。</span>
        </div>
      </template>
    </div>
    <footer class="external-toolbar">
      <div>
        <span class="platform-badge">{{ platform || 'EXTERNAL' }}</span>
        <small>官方嵌入播放 · 视频未缓存到本站</small>
      </div>
      <a class="secondary-button compact" :href="sourceUrl" target="_blank" rel="noreferrer">打开原视频 ↗</a>
    </footer>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { buildExternalEmbedUrl } from '../utils/externalVideoEmbed'

const props = defineProps({
  coverUrl: { type: String, default: '' },
  platform: { type: String, default: '' },
  sourceUrl: { type: String, required: true },
  startMs: { type: Number, default: null }
})

const embedUrl = computed(() => buildExternalEmbedUrl(
  props.platform,
  props.sourceUrl,
  props.startMs
))
</script>

<style scoped>
.external-player-card { overflow: hidden; width: 100%; border: 1px solid var(--border); border-radius: 18px; background: #050608; box-shadow: var(--shadow); }
.external-frame { position: relative; display: grid; width: 100%; aspect-ratio: 16 / 9; place-items: center; background: #050608; }
.external-frame iframe, .external-frame img { position: absolute; inset: 0; width: 100%; height: 100%; border: 0; }
.external-frame img { object-fit: cover; opacity: .35; }
.external-fallback { position: relative; z-index: 1; display: grid; justify-items: center; gap: 7px; color: var(--muted); text-align: center; }
.external-fallback strong { color: var(--text); }
.external-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 12px 14px; border-top: 1px solid var(--border); }
.external-toolbar > div { display: flex; min-width: 0; align-items: center; gap: 10px; }
.external-toolbar small { overflow: hidden; color: var(--muted); text-overflow: ellipsis; white-space: nowrap; }
.platform-badge { padding: 5px 8px; border: 1px solid var(--border-strong); border-radius: 99px; color: var(--accent); font: 10px var(--mono); }
.external-toolbar a { flex: none; text-decoration: none; }
@media (max-width: 560px) { .external-toolbar small { display: none; } }
</style>
