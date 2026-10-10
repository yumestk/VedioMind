<template>
  <section class="external-card">
    <img v-if="coverUrl" :src="coverUrl" alt="视频封面" />
    <div class="external-overlay"></div>
    <div class="external-content">
      <span class="platform-badge">{{ platform || 'EXTERNAL' }}</span>
      <div>
        <p>该视频未缓存到本站</p>
        <h3>内容分析来自平台字幕</h3>
      </div>
      <a class="primary-button" :href="sourceUrl" target="_blank" rel="noreferrer">打开原视频 ↗</a>
    </div>
  </section>
</template>

<script setup>
defineProps({
  coverUrl: { type: String, default: '' },
  platform: { type: String, default: '' },
  sourceUrl: { type: String, required: true }
})
</script>

<style scoped>
.external-card { position: relative; overflow: hidden; width: 100%; aspect-ratio: 16 / 9; border: 1px solid var(--border); border-radius: 18px; background: #050608; box-shadow: var(--shadow); }
.external-card img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.external-overlay { position: absolute; inset: 0; background: linear-gradient(90deg, rgba(5, 6, 8, .96), rgba(5, 6, 8, .72) 52%, rgba(5, 6, 8, .3)); }
.external-content { position: relative; display: flex; height: 100%; flex-direction: column; align-items: flex-start; justify-content: center; gap: 18px; padding: clamp(24px, 5vw, 54px); }
.platform-badge { padding: 6px 9px; border: 1px solid var(--border-strong); border-radius: 99px; color: var(--accent); font: 10px var(--mono); }
.external-content p { margin: 0 0 6px; color: var(--muted); font-size: 12px; }
.external-content h3 { margin: 0; font-size: clamp(22px, 3vw, 34px); }
.external-content .primary-button { text-decoration: none; }
</style>
