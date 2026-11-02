import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'

import { restoreSession } from './api/session'

await restoreSession().catch(() => {})
createApp(App).use(router).mount('#app')
