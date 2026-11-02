import { ref } from 'vue'
import { currentUser, logout, sessionStatus, restoreSession } from '../api/session'
const authOpen = ref(false)
export const useSession = () => ({
  currentUser, authOpen, logout, sessionStatus, restoreSession,
  openAuth: () => { authOpen.value = true },
  closeAuth: () => { authOpen.value = false }
})
