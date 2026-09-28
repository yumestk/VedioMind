import { ref } from 'vue'

const readSavedUser = () => {
  try {
    return JSON.parse(localStorage.getItem('user'))
  } catch {
    localStorage.removeItem('user')
    return null
  }
}

const currentUser = ref(readSavedUser())
const authOpen = ref(false)

export const useSession = () => {
  const setUser = (user) => {
    currentUser.value = user
    localStorage.setItem('user', JSON.stringify(user))
  }

  const logout = () => {
    currentUser.value = null
    localStorage.removeItem('user')
  }

  return {
    currentUser,
    authOpen,
    openAuth: () => { authOpen.value = true },
    closeAuth: () => { authOpen.value = false },
    setUser,
    logout
  }
}
