import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  addDays, addMonths, fmt, isoWeek, startOfDay, startOfMonth, startOfWeek,
} from '@/utils/date'
import { useSettingsStore, type ViewMode } from './settings'

export const LIST_DAYS = 60

/** Aktuelle Ansicht und angezeigter Zeitraum */
export const useViewStore = defineStore('view', () => {
  const { settings } = useSettingsStore()
  const mode = ref<ViewMode>(settings.defaultView)
  const date = ref<Date>(startOfDay(new Date()))
  const search = ref('')

  /** Sichtbare Tage (für Monat: 6 Wochen-Raster) */
  const range = computed(() => {
    const d = date.value
    switch (mode.value) {
      case 'month': {
        const start = startOfWeek(startOfMonth(d), settings.weekStartsOn)
        const lastOfMonth = new Date(d.getFullYear(), d.getMonth() + 1, 0)
        const weeks = Math.ceil((Math.round((lastOfMonth.getTime() - start.getTime()) / 86_400_000) + 1) / 7)
        return { start, end: addDays(start, weeks * 7) }
      }
      case 'week': {
        const start = startOfWeek(d, settings.weekStartsOn)
        return { start, end: addDays(start, 7) }
      }
      case 'day':
        return { start: d, end: addDays(d, 1) }
      case 'list':
        return { start: d, end: addDays(d, LIST_DAYS) }
    }
  })

  const title = computed(() => {
    const { start, end } = range.value
    switch (mode.value) {
      case 'month':
        return fmt(date.value, { month: 'long', year: 'numeric' })
      case 'week': {
        const last = addDays(end, -1)
        const same = start.getMonth() === last.getMonth()
        const label = same
          ? fmt(start, { month: 'long', year: 'numeric' })
          : `${fmt(start, { month: 'short' })} – ${fmt(last, { month: 'short', year: 'numeric' })}`
        return `${label} · KW ${isoWeek(start)}`
      }
      case 'day':
        return fmt(date.value, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
      case 'list':
        return `Ab ${fmt(date.value, { day: 'numeric', month: 'long', year: 'numeric' })}`
    }
  })

  function step(dir: 1 | -1) {
    const d = date.value
    date.value = mode.value === 'month' ? addMonths(d, dir)
      : mode.value === 'week' ? addDays(d, 7 * dir)
      : mode.value === 'day' ? addDays(d, dir)
      : addDays(d, 30 * dir)
  }
  function today() {
    date.value = startOfDay(new Date())
  }
  function goto(d: Date, m?: ViewMode) {
    date.value = startOfDay(d)
    if (m) mode.value = m
  }
  return { mode, date, search, range, title, step, today, goto }
})
