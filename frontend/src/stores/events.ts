import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { EventInput, Occurrence, ViewEvent } from '@/api/types'
import { allDayToLocal } from '@/utils/date'
import { useCalendarsStore } from './calendars'

export const useEventsStore = defineStore('events', () => {
  const calendars = useCalendarsStore()
  const raw = ref<Occurrence[]>([])
  const loading = ref(false)
  let range: { start: Date; end: Date } | null = null
  let requestId = 0

  const all = computed<ViewEvent[]>(() =>
    raw.value.map((o) => ({
      ...o,
      startDate: o.all_day ? allDayToLocal(o.start) : new Date(o.start),
      endDate: o.all_day ? allDayToLocal(o.end) : new Date(o.end),
      color: calendars.byId.get(o.calendar_id)?.color ?? '#6750A4',
    })),
  )
  const visible = computed(() => all.value.filter((e) => calendars.visibleIds.has(e.calendar_id)))

  async function load(start: Date, end: Date) {
    range = { start, end }
    const id = ++requestId
    loading.value = true
    try {
      const data = await api.occurrences(start, end)
      if (id === requestId) raw.value = data
    } finally {
      if (id === requestId) loading.value = false
    }
  }
  async function reload() {
    if (range) await load(range.start, range.end)
  }

  async function create(input: EventInput) {
    const e = await api.createEvent(input)
    await reload()
    return e
  }
  async function update(id: string, input: EventInput) {
    const e = await api.updateEvent(id, input)
    await reload()
    return e
  }
  async function remove(id: string) {
    await api.deleteEvent(id)
    await reload()
  }
  async function removeOccurrence(eventId: string, occurrenceStart: string) {
    await api.addExdate(eventId, occurrenceStart)
    await reload()
  }
  function reset() {
    raw.value = []
    range = null
  }
  return { all, visible, loading, load, reload, create, update, remove, removeOccurrence, reset }
})
