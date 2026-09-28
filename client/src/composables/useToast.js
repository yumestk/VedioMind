import { ref } from 'vue'

const toast = ref(null)
let timer

export const useToast = () => {
  const showToast = (message, type = 'success') => {
    toast.value = { message, type }
    clearTimeout(timer)
    timer = setTimeout(() => { toast.value = null }, 4000)
  }

  return { toast, showToast }
}
