import { defineStore } from 'pinia'
import { reactive, watch } from 'vue'

import type { ViewMode } from '@/api/types'

export type { ViewMode }

export interface Settings {
  themeMode: 'system' | 'light' | 'dark'
  seedColor: string
  weekStartsOn: number
  /** Cached default view from the user account (for the initial render before `/auth/me`) */
  defaultView: ViewMode
  showWeekNumbers: boolean
}

const KEY = 'synchaven.settings'
const DEFAULTS: Settings = {
  themeMode: 'system',
  seedColor: '#6750A4',
  weekStartsOn: 1,
  defaultView: 'month',
  showWeekNumbers: true,
}

export function loadSettings(): Settings {
  try {
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) ?? '{}') }
  } catch {
    return { ...DEFAULTS }
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const settings = reactive<Settings>(loadSettings())
  watch(settings, (s) => localStorage.setItem(KEY, JSON.stringify(s)), { deep: true })
  return { settings }
})
