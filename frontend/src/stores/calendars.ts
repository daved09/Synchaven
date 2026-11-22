import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { Calendar, CalendarInput } from '@/api/types'
import { browserTimeZone } from '@/utils/date'

export const useCalendarsStore = defineStore('calendars', () => {
  const list = ref<Calendar[]>([])
  const byId = computed(() => new Map(list.value.map((c) => [c.id, c])))
  const visibleIds = computed(() => new Set(list.value.filter((c) => c.visible).map((c) => c.id)))

  async function load() {
    list.value = await api.calendars()
  }
  async function create(data: CalendarInput) {
    const c = await api.createCalendar(data)
    list.value.push(c)
    return c
  }
  async function update(id: string, data: CalendarInput) {
    const c = await api.updateCalendar(id, data)
    list.value = list.value.map((x) => (x.id === id ? c : x))
    return c
  }
  async function remove(id: string) {
    await api.deleteCalendar(id)
    list.value = list.value.filter((x) => x.id !== id)
  }
  async function toggleVisible(cal: Calendar) {
    cal.visible = !cal.visible // optimistisch
    try {
      await update(cal.id, { name: cal.name, color: cal.color, description: cal.description, visible: cal.visible })
    } catch (e) {
      cal.visible = !cal.visible
      throw e
    }
  }
  async function exportIcs(cal: Calendar) {
    const text = await api.exportCalendar(cal.id)
    const url = URL.createObjectURL(new Blob([text], { type: 'text/calendar' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `${cal.name.replace(/[^\p{L}\p{N}_-]+/gu, '_')}.ics`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  async function importIcs(cal: Calendar, file: File) {
    return api.importCalendar(cal.id, await file.text(), browserTimeZone())
  }
  function reset() {
    list.value = []
  }
  return { list, byId, visibleIds, load, create, update, remove, toggleVisible, exportIcs, importIcs, reset }
})
