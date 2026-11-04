<template>
  <main class="page courses-page">
    <h1>课程学习</h1>
    <p>按章节组织网课，观看时向 AI 提问。学习记录仅自己可见。</p>
    <button v-if="!currentUser" class="primary-button" @click="openAuth">登录 / 注册</button>
    <template v-else>
      <form class="course-form" @submit.prevent="create">
        <input v-model.trim="title" required maxlength="100" placeholder="新课程名称" aria-label="新课程名称" />
        <button class="primary-button" :disabled="busy">创建课程</button>
      </form>
      <p v-if="error" role="alert">{{ error }}</p>
      <div class="courses-grid">
        <router-link v-for="course in courses" :key="course.id" :to="`/courses/${course.id}`" class="course-card">
          <small>{{ course.canManage ? '我的课程 · ' : '' }}{{ course.status === 'PUBLISHED' ? '已发布' : '草稿' }}</small>
          <h2>{{ course.title }}</h2><p>{{ course.description || '打开章节与课程视频' }}</p>
        </router-link>
      </div>
      <p v-if="!courses.length && !loading">还没有课程，可以先创建一门。</p>
    </template>
  </main>
</template>
<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from '../composables/useSession'
import { listCourses, createCourse } from '../api/courses'
import { errorMessage } from '../api/http'
const { currentUser, openAuth } = useSession()
const router = useRouter()
const courses = ref([]), title = ref(''), error = ref(''), busy = ref(false), loading = ref(false)
let revision = 0
watch(() => currentUser.value?.id, async user => {
  const request = ++revision
  courses.value = []; error.value = ''
  if (!user) return
  loading.value = true
  try { const result = await listCourses(); if (request === revision) courses.value = result }
  catch (e) { if (request === revision) error.value = errorMessage(e) }
  finally { if (request === revision) loading.value = false }
}, { immediate: true })
const create = async () => {
  busy.value = true; error.value = ''
  try { const result = await createCourse({ title: title.value }); await router.push(`/courses/${result.course.id}`) }
  catch (e) { error.value = errorMessage(e) } finally { busy.value = false }
}
</script>
<style scoped>
.courses-page { padding-block: 32px 70px; } .course-form { display:flex; gap:12px; margin:24px 0; }
input { padding:12px; border:1px solid var(--border); border-radius:8px; background:var(--panel); color:var(--text); }
.courses-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(250px,1fr)); gap:20px; }
.course-card { padding:24px; border:1px solid var(--border); border-radius:16px; background:var(--panel); color:var(--text); overflow-wrap:anywhere; }
small { color:var(--accent); } p { color:var(--muted); } [role=alert] { color:var(--danger); }
</style>
