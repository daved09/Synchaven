import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import {
  addDays, addMonths, fmt, isoWeek, startOfDay, startOfMonth, startOfWeek,
} from '@/utils/date'
import { useAuthStore } from './auth'
import { useNotifyStore } from './notify'
import { useSettingsStore, type ViewMode } from './settings'

export const LIST_DAYS = 60

/** Aktuelle Ansicht und angezeigter Zeitraum */
export const useViewStore = defineStore('view', () => {
  const { settings } = useSettingsStore()
  const auth = useAuthStore()
  const notify = useNotifyStore()
  const mode = ref<ViewMode>(auth.user?.default_view ?? settings.defaultView)
  /** Ansicht wurde bereits bewusst gewählt (Benutzer oder URL) – die Standardansicht überschreibt sie nicht mehr */
  let pinned = false

  // Standardansicht aus dem Benutzerkonto übernehmen, sobald es geladen oder geändert ist
  watch(() => auth.user?.default_view, (v) => {
    if (!v) return
    settings.defaultView = v
    if (!pinned) mode.value = v
  }, { immediate: true })
  const date = ref<Date>(startOfDay(new Date()))
  const search = ref('')

  /** Tage, die die aktuelle Ansicht für das Datum `d` zeigt (für Monat: ganze Wochen) */
  function rangeOf(d: Date): { start: Date; end: Date } {
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
  }
  const range = computed(() => rangeOf(date.value))
  /** Datum des um `n` Zeiträume verschobenen Zeitraums */
  function shift(d: Date, n: number): Date {
    return mode.value === 'month' ? addMonths(d, n)
      : mode.value === 'week' ? addDays(d, 7 * n)
      : mode.value === 'day' ? addDays(d, n)
      : addDays(d, 30 * n)
  }

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
    date.value = shift(date.value, dir)
  }
  function today() {
    date.value = startOfDay(new Date())
  }
  function goto(d: Date, m?: ViewMode) {
    date.value = startOfDay(d)
    if (m) show(m)
  }
  /** Zeigt eine Ansicht, ohne die Standardansicht zu ändern */
  function show(m: ViewMode) {
    mode.value = m
    pinned = true
  }
  /** Vom Benutzer gewählte Ansicht – wird zugleich als Standardansicht im Benutzerkonto gespeichert */
  function select(m: ViewMode) {
    show(m)
    if (auth.user && auth.user.default_view !== m) auth.update({ default_view: m }).catch((e) => notify.fail(e))
  }
  return { mode, date, search, range, rangeOf, shift, title, step, today, goto, show, select }
})
