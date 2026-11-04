<template>
  <main class="page course-detail">
    <router-link to="/courses">← 课程列表</router-link>
    <button v-if="!currentUser" class="primary-button" @click="openAuth">登录 / 注册</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <h1>{{ detail.course.title }}</h1><p>{{ detail.course.description }}</p>
      <template v-if="detail.course.canManage">
        <button class="primary-button" :disabled="busy" @click="changeVisibility">
          {{ detail.course.status === 'PUBLISHED' ? '撤回课程' : '发布课程' }}
        </button>
        <p>发布后所有登录用户可学习。撤回或移除视频会禁用相应来源，保留学生已有学习成果。</p>
        <form class="organize-form" @submit.prevent="addSection">
          <input v-model.trim="sectionTitle" required maxlength="100" placeholder="章节标题" aria-label="章节标题" />
          <button class="secondary-button" :disabled="busy">添加章节</button>
        </form>
      </template>
      <section v-for="section in detail.sections" :key="section.id" class="course-section">
        <h2>{{ section.title }}</h2>
        <div v-for="material in section.materials" :key="material.id" class="material-row">
          <router-link :to="`/courses/${courseId}/media/${material.mediaId}`">▶ {{ material.title }}</router-link>
          <button v-if="detail.course.canManage" class="secondary-button" :disabled="busy" @click="remove(material)">移除</button>
        </div>
        <form v-if="detail.course.canManage" class="organize-form" @submit.prevent="addMaterial(section)">
          <select v-model="selectedMedia[section.id]" required aria-label="选择本人视频">
            <option value="" disabled>选择本人视频</option>
            <option v-for="media in availableMedia" :key="media.id" :value="media.id">{{ media.filename }}</option>
          </select>
          <button class="secondary-button" :disabled="busy">添加视频</button>
        </form>
      </section>
      <p v-if="!detail.sections.length">还没有章节。</p>
    </template>
  </main>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { useSession } from '../composables/useSession'
import { listMedia } from '../api/media'
import { getCourse, addCourseSection, addCourseMaterial, removeCourseMaterial, setCourseVisibility } from '../api/courses'
import { errorMessage } from '../api/http'
const props = defineProps({ courseId: { type: String, required: true } })
const { currentUser, openAuth } = useSession()
const detail = ref(null), library = ref([]), sectionTitle = ref(''), selectedMedia = ref({}), busy = ref(false), error = ref('')
const availableMedia = computed(() => library.value.filter(media => !detail.value?.sections.some(s => s.materials.some(m => m.mediaId === media.id))))
let revision = 0
watch(() => currentUser.value?.id, async user => {
  const request = ++revision
  detail.value = null; library.value = []; error.value = ''
  if (!user) return
  try {
    const result = await getCourse(props.courseId)
    const media = result.course.canManage ? await listMedia() : []
    if (request === revision) { detail.value = result; library.value = media; for (const section of result.sections) selectedMedia.value[section.id] = '' }
  } catch (e) { if (request === revision) error.value = errorMessage(e, '课程不存在或已撤回') }
}, { immediate: true })
const mutate = async operation => {
  const request = revision
  busy.value = true; error.value = ''
  try { const result = await operation(); if (request === revision) { detail.value = result; for (const section of result.sections) selectedMedia.value[section.id] ??= '' } }
  catch (e) { if (request === revision) error.value = errorMessage(e) } finally { busy.value = false }
}
const addSection = () => mutate(async () => {
  const result = await addCourseSection(props.courseId, { title: sectionTitle.value, position: detail.value.sections.length })
  sectionTitle.value = ''; return result
})
const addMaterial = section => mutate(async () => {
  const result = await addCourseMaterial(props.courseId, section.id, { mediaId: selectedMedia.value[section.id], position: section.materials.length })
  selectedMedia.value[section.id] = ''; return result
})
const remove = material => mutate(() => removeCourseMaterial(props.courseId, material.id))
const changeVisibility = () => mutate(() => setCourseVisibility(props.courseId, detail.value.course.status !== 'PUBLISHED'))
</script>
<style scoped>
.course-detail { padding-block:32px 70px; } .course-section { margin-top:24px; padding:24px; border:1px solid var(--border); border-radius:14px; background:var(--panel); }
.material-row,.organize-form { display:flex; align-items:center; gap:14px; margin-top:16px; } .material-row { justify-content:space-between; }
a { color:var(--accent); overflow-wrap:anywhere; } p { color:var(--muted); } [role=alert] { color:var(--danger); }
input,select { min-width:0; max-width:70%; padding:12px; border:1px solid var(--border); border-radius:8px; background:var(--panel); color:var(--text); }
</style>
