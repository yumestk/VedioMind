<template>
  <div class="app-shell">
    <div class="ambient-grid"></div>
    <header class="site-header">
      <div class="header-inner">
        <router-link class="brand" to="/">
          <span class="brand-mark">VM</span>
          <span><strong>Vedio</strong>Mind</span>
          <small>LAB</small>
        </router-link>
        <nav class="header-actions">
          <span class="system-state"><i></i> SYSTEM READY</span>
          <button v-if="!currentUser" class="secondary-button compact" @click="openAuth">登录 / 注册</button>
          <div v-else class="account-chip">
            <span>{{ currentUser.nickname || currentUser.username }}</span>
            <button class="icon-button" aria-label="退出登录" @click="handleLogout">↗</button>
          </div>
        </nav>
      </div>
    </header>

    <router-view />

    <AuthDialog v-if="authOpen" @close="closeAuth" />
    <transition name="toast">
      <div v-if="toast" class="toast-message" :class="toast.type">{{ toast.message }}</div>
    </transition>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import AuthDialog from './components/AuthDialog.vue'
import { useSession } from './composables/useSession'
import { useToast } from './composables/useToast'

const router = useRouter()
const { currentUser, authOpen, openAuth, closeAuth, logout } = useSession()
const { toast, showToast } = useToast()

const handleLogout = async () => {
  logout()
  await router.push('/')
  showToast('已退出登录')
}
</script>

<style scoped>
.app-shell { position: relative; min-height: 100vh; }
.ambient-grid { position: fixed; inset: 0; z-index: -1; pointer-events: none; background-image: linear-gradient(rgba(255,255,255,.018) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.018) 1px, transparent 1px), radial-gradient(circle at 18% 0%, rgba(197,249,70,.08), transparent 28%); background-size: 42px 42px, 42px 42px, auto; mask-image: linear-gradient(to bottom, black, transparent 80%); }
.site-header { position: sticky; top: 0; z-index: 100; border-bottom: 1px solid rgba(255,255,255,.07); background: rgba(10, 11, 14, .82); backdrop-filter: blur(18px); }
.header-inner { display: flex; width: min(1440px, calc(100% - 40px)); min-height: 70px; align-items: center; justify-content: space-between; gap: 20px; margin: auto; }
.brand { display: flex; align-items: center; gap: 9px; color: var(--text); font-size: 20px; letter-spacing: -.04em; }
.brand-mark { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 8px; background: var(--accent); color: #0b0d08; font: 700 12px var(--mono); letter-spacing: 0; }
.brand small { margin-left: 2px; color: var(--accent); font: 9px var(--mono); letter-spacing: .12em; }
.header-actions, .account-chip { display: flex; align-items: center; gap: 12px; }
.system-state { display: flex; align-items: center; gap: 7px; color: var(--muted); font: 9px var(--mono); letter-spacing: .08em; }
.system-state i { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 10px var(--accent); }
.account-chip { padding: 5px 5px 5px 12px; border: 1px solid var(--border); border-radius: 99px; color: var(--muted); font-size: 12px; }
.toast-message { position: fixed; right: 24px; bottom: 24px; z-index: 1200; max-width: min(420px, calc(100vw - 48px)); padding: 14px 18px; border: 1px solid rgba(197,249,70,.3); border-radius: 12px; background: rgba(24, 28, 19, .96); box-shadow: var(--shadow); color: var(--text); }
.toast-message.error { border-color: rgba(255,107,107,.35); background: rgba(35,15,18,.96); color: #ffc1c1; }
.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(10px); }
@media (max-width: 600px) { .system-state { display: none; } .header-inner { width: min(100% - 24px, 1440px); } }
</style>
