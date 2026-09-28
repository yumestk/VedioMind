<template>
  <div class="dialog-backdrop" @click.self="$emit('close')">
    <section class="auth-dialog" role="dialog" aria-modal="true" aria-labelledby="auth-title">
      <button class="icon-button dialog-close" aria-label="关闭" @click="$emit('close')">×</button>
      <p class="eyebrow">ACCOUNT ACCESS</p>
      <h2 id="auth-title">{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
      <p class="dialog-subtitle">登录后上传视频，并在内容工作台中持续分析。</p>

      <form @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model.trim="form.username" autocomplete="username" required />
        </label>
        <label>
          <span>密码</span>
          <input v-model="form.password" type="password" autocomplete="current-password" required />
        </label>
        <label v-if="mode === 'register'">
          <span>昵称</span>
          <input v-model.trim="form.nickname" autocomplete="nickname" />
        </label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="primary-button full-button" :disabled="loading">
          {{ loading ? '请求处理中…' : mode === 'login' ? '登录' : '注册' }}
        </button>
      </form>

      <button class="text-button switch-button" @click="switchMode">
        {{ mode === 'login' ? '没有账号？创建一个' : '已有账号？返回登录' }}
      </button>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { login, register } from '../api/auth'
import { errorMessage } from '../api/http'
import { useSession } from '../composables/useSession'
import { useToast } from '../composables/useToast'

defineEmits(['close'])

const mode = ref('login')
const loading = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '', nickname: '' })
const { setUser, closeAuth } = useSession()
const { showToast } = useToast()

const switchMode = () => {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
}

const submit = async () => {
  loading.value = true
  error.value = ''
  try {
    const result = mode.value === 'login' ? await login(form) : await register(form)
    if (result.code !== 200) throw new Error(result.msg || '操作失败')

    if (mode.value === 'register') {
      mode.value = 'login'
      form.password = ''
      showToast('注册成功，请登录')
      return
    }

    setUser(result.userInfo)
    closeAuth()
    showToast(`欢迎回来，${result.userInfo.nickname || result.userInfo.username}`)
  } catch (requestError) {
    error.value = errorMessage(requestError, '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.dialog-backdrop { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 20px; background: rgba(4, 5, 8, .82); backdrop-filter: blur(12px); }
.auth-dialog { position: relative; width: min(420px, 100%); padding: 36px; border: 1px solid var(--border); border-radius: 20px; background: var(--panel); box-shadow: 0 30px 90px rgba(0, 0, 0, .55); }
.dialog-close { position: absolute; top: 16px; right: 16px; }
h2 { margin: 4px 0 8px; font-size: 30px; }
.dialog-subtitle { margin: 0 0 28px; color: var(--muted); }
form { display: grid; gap: 16px; }
label { display: grid; gap: 8px; color: var(--muted); font-size: 13px; }
input { width: 100%; }
.form-error { margin: 0; color: var(--danger); font-size: 13px; }
.full-button { width: 100%; margin-top: 4px; }
.switch-button { display: block; margin: 18px auto 0; }
</style>
