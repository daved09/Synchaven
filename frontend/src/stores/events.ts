import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { EventInput, Occurrence, ViewEvent } from '@/api/types'
import { addDays, allDayToLocal, diffDays, localToAllDay } from '@/utils/date'
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
  /**
   * Verschiebt einen Termin so, dass er bei `newStart` beginnt; die Dauer bleibt erhalten.
   * Bei Serien wird nur dieses Vorkommen verschoben: es wird als eigener Termin angelegt
   * und in der Serie als Ausnahme eingetragen.
   */
  async function move(ev: ViewEvent, newStart: Date) {
    let start: string
    let end: string
    if (ev.all_day) {
      const days = diffDays(newStart, ev.startDate)
      if (days === 0) return
      start = localToAllDay(addDays(ev.startDate, days))
      end = localToAllDay(addDays(ev.endDate, days))
    } else {
      const delta = newStart.getTime() - ev.startDate.getTime()
      if (delta === 0) return
      start = newStart.toISOString()
      end = new Date(ev.endDate.getTime() + delta).toISOString()
    }

    // Sofort an neuer Stelle anzeigen, Server bestätigt danach per reload()
    raw.value = raw.value.map((o) => (o.id === ev.id ? { ...o, start, end } : o))
    try {
      const full = await api.event(ev.event_id)
      const input: EventInput = {
        calendar_id: full.calendar_id,
        title: full.title,
        description: full.description,
        location: full.location,
        start,
        end,
        all_day: full.all_day,
        timezone: full.timezone,
        rrule: full.rrule,
        exdates: full.exdates,
      }
      if (ev.recurring) {
        // Erst anlegen, dann ausnehmen: schlägt etwas fehl, gibt es eher ein Duplikat als einen Verlust
        await api.createEvent({ ...input, rrule: null, exdates: [] })
        await api.addExdate(ev.event_id, ev.start)
      } else {
        await api.updateEvent(ev.event_id, input)
      }
    } finally {
      await reload()
    }
  }
  function reset() {
    raw.value = []
    range = null
  }
  return { all, visible, loading, load, reload, create, update, move, remove, removeOccurrence, reset }
})
