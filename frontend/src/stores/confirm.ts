import { t } from '@/i18n'
import { defineStore } from 'pinia'
import { ref } from 'vue'

/** Confirmation prompt shown by the single ConfirmDialog in App.vue */
export const useConfirmStore = defineStore('confirm', () => {
  const open = ref(false)
  const title = ref('')
  const text = ref('')
  const confirmText = ref('')
  const confirmColor = ref('error')
  let resolver: ((v: boolean) => void) | null = null

  /** Resolves to true if the user confirms; a pending question is answered with false */
  function ask(heading: string, body: string, ok = t('common.delete'), color = 'error'): Promise<boolean> {
    resolver?.(false)
    title.value = heading
    text.value = body
    confirmText.value = ok
    confirmColor.value = color
    open.value = true
    return new Promise((resolve) => (resolver = resolve))
  }
  function close(result: boolean) {
    open.value = false
    resolver?.(result)
    resolver = null
  }

  return { open, title, text, confirmText, confirmColor, ask, close }
})
