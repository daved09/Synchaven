import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiError } from '@/api/client'
import { t } from '@/i18n'

export const useNotifyStore = defineStore('notify', () => {
  const show = ref(false)
  const text = ref('')
  const error = ref(false)

  function info(msg: string) {
    text.value = msg
    error.value = false
    show.value = true
  }
  function fail(e: unknown, fallback = t('errors.generic')) {
    text.value = e instanceof ApiError || e instanceof Error ? e.message : fallback
    error.value = true
    show.value = true
  }
  return { show, text, error, info, fail }
})
