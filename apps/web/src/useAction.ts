import { ref } from 'vue'

export function useAction() {
  const busy = ref(false)
  const error = ref('')
  const notice = ref('')
  async function action(work: () => Promise<void>, success = '') {
    if (busy.value) return
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      await work()
      notice.value = success
    } catch (failure) {
      error.value = failure instanceof Error ? failure.message : String(failure)
    } finally {
      busy.value = false
    }
  }
  return { busy, error, notice, action }
}
