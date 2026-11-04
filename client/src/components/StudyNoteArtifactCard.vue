<template>
  <section class="note-card" aria-label="已保存学习笔记">
    <header><strong>学习笔记 · 版本 {{ current.version }}</strong><span v-if="current.edited">已手工编辑</span></header>
    <template v-if="editing">
      <label>标题<input v-model="draft.title" maxlength="160" /></label>
      <label>核心结论<textarea v-model="draft.coreConclusion" maxlength="4000" /></label>
      <div v-for="(point, index) in draft.knowledgePoints" :key="index">
        <label>知识点 {{ index + 1 }}<input v-model="point.title" maxlength="160" /></label>
        <label>说明<textarea v-model="point.explanation" maxlength="2400" /></label>
      </div>
      <button :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存修改' }}</button>
      <button :disabled="saving" @click="editing = false">取消编辑</button>
    </template>
    <template v-else>
      <h2>{{ current.content.title }}</h2><p>{{ current.content.coreConclusion }}</p>
      <article v-for="(point, index) in current.content.knowledgePoints" :key="index">
        <h3>{{ index + 1 }}. {{ point.title }}</h3><p>{{ point.explanation }}</p>
      </article>
      <p>覆盖 {{ current.coverageMaterialIds.length }} 个视频</p>
      <ul v-if="current.content.gaps.length"><li v-for="gap in current.content.gaps" :key="gap">{{ gap }}</li></ul>
      <details><summary>查看引用来源</summary>
        <div v-for="source in current.citations" :key="source.evidence.ref">
          <button :disabled="!source.available" @click="$emit('seek', { materialId: source.evidence.materialId, startMs: source.evidence.startMs })">
            视频 {{ source.evidence.materialId }} · {{ Math.floor(source.evidence.startMs / 1000) }} 秒
            {{ source.available ? '定位原文' : '来源失效' }}
          </button><p v-if="source.available">{{ source.evidence.text }}</p>
        </div>
      </details>
      <button @click="edit">编辑笔记</button>
    </template>
    <p v-if="failure" role="alert">{{ failure }}</p>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import { editStudyArtifact } from '../api/media'
import { errorMessage } from '../api/http'
const props = defineProps({ artifact: { type: Object, required: true } })
defineEmits(['seek'])
const current = ref(props.artifact), draft = ref(null), editing = ref(false), saving = ref(false), failure = ref('')
watch(() => props.artifact, value => { if (!editing.value) current.value = value })
const edit = () => { draft.value = JSON.parse(JSON.stringify(current.value.content)); editing.value = true; failure.value = '' }
const save = async () => {
  saving.value = true; failure.value = ''
  try { current.value = await editStudyArtifact(current.value.id, { baseVersion: current.value.version, content: draft.value }); editing.value = false }
  catch (error) { failure.value = errorMessage(error, '保存失败，请刷新笔记后重试；当前编辑内容已保留') }
  finally { saving.value = false }
}
</script>
<style scoped>
.note-card { display:grid; gap:12px; padding:16px; border:1px solid var(--border); border-radius:10px; background:var(--panel); }
header { display:flex; justify-content:space-between; gap:12px; }
p { line-height:1.75; white-space:pre-wrap; }
label { display:grid; gap:8px; margin-bottom:10px; }
input,textarea { width:100%; box-sizing:border-box; background:var(--surface); color:var(--text); border:1px solid var(--border); padding:10px; border-radius:6px; }
textarea { min-height:100px; resize:vertical; }
button { margin-right:8px; }
</style>
