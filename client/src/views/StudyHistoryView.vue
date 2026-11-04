<template>
  <main class="page study-history">
    <h1>我的学习记录</h1><p>记录仅自己可见，课程撤回后仍保留已有成果。</p>
    <button v-if="!currentUser" class="primary-button" @click="openAuth">登录 / 注册</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="currentUser">
      <select v-model="selectedId" aria-label="选择学习记录">
        <option value="">选择学习记录</option>
        <option v-for="item in conversations" :key="item.id" :value="item.id">{{ item.title }}{{ item.sourceAvailable ? '' : ' · 来源已失效' }}</option>
      </select>
      <template v-if="selected">
        <router-link v-if="selected.sourceAvailable" :to="selected.courseId ? `/courses/${selected.courseId}/media/${selected.mediaId}` : `/media/${selected.mediaId}`">打开视频与提问 →</router-link>
        <ConversationPanel :key="`${currentUser.id}-${selected.id}`" :media-id="selected.mediaId" :user-id="currentUser.id"
          :archived-conversation="selected" :has-transcript="true" :busy="true" />
      </template>
      <p v-else-if="!conversations.length">还没有学习记录。</p>
    </template>
  </main>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { useSession } from '../composables/useSession'
import { listMyConversations } from '../api/courses'
import { errorMessage } from '../api/http'
import ConversationPanel from '../components/ConversationPanel.vue'
const { currentUser, openAuth } = useSession()
const conversations = ref([]), selectedId = ref(''), error = ref('')
const selected = computed(() => conversations.value.find(item => item.id === Number(selectedId.value)))
let revision = 0
watch(() => currentUser.value?.id, async user => {
  const request = ++revision
  conversations.value = []; selectedId.value = ''; error.value = ''
  if (!user) return
  try { const result = await listMyConversations(); if (request === revision) conversations.value = result }
  catch (e) { if (request === revision) error.value = errorMessage(e) }
}, { immediate: true })
</script>
<style scoped>
.study-history { max-width:950px; padding-block:32px 70px; } select { display:block; margin:24px 0; max-width:100%; padding:12px; border:1px solid var(--border); border-radius:8px; background:var(--panel); color:var(--text); }
a { display:block; margin-block:16px; color:var(--accent); } p { color:var(--muted); } [role=alert] { color:var(--danger); }
</style>
