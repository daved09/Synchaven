<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDisplay } from 'vuetify'
import { api } from '@/api/client'
import type { EventInput } from '@/api/types'
import { useCalendarsStore } from '@/stores/calendars'
import { useEventsStore } from '@/stores/events'
import { useNotifyStore } from '@/stores/notify'
import {
  addDays, addMinutes, allDayToLocal, browserTimeZone, combine, dayKey, localToAllDay, parseDayKey, timeKey,
} from '@/utils/date'
import { buildRRule, emptyForm, jsDayToCode, parseRRule, type RecurrenceForm } from '@/utils/rrule'
import RecurrenceEditor from './RecurrenceEditor.vue'

export interface EventPreset {
  start: Date
  end: Date
  allDay: boolean
  calendarId?: string
}

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ eventId: string | null; preset: EventPreset | null }>()

const { xs } = useDisplay()
const calendars = useCalendarsStore()
const events = useEventsStore()
const notify = useNotifyStore()

const formRef = ref()
const loading = ref(false)
const saving = ref(false)
const title = ref('')
const calendarId = ref('')
const allDay = ref(false)
const startDate = ref('')
const startTime = ref('09:00')
const endDate = ref('')
const endTime = ref('10:00')
const location = ref('')
const description = ref('')
const timezone = ref(browserTimeZone())
const exdates = ref<string[]>([])
const rec = ref<RecurrenceForm>(emptyForm(new Date()))
const isSeries = ref(false)

const start = computed(() => (allDay.value ? parseDayKey(startDate.value) ?? new Date() : combine(startDate.value, startTime.value)))
const end = computed(() => (allDay.value ? parseDayKey(endDate.value) ?? new Date() : combine(endDate.value, endTime.value)))

function fill(s: Date, e: Date, isAllDay: boolean) {
  allDay.value = isAllDay
  startDate.value = dayKey(s)
  startTime.value = timeKey(s)
  // Ganztägig: Enddatum wird inklusiv angezeigt
  const shownEnd = isAllDay ? addDays(e, -1) : e
  endDate.value = dayKey(shownEnd < s ? s : shownEnd)
  endTime.value = timeKey(e)
}

watch(open, async (v) => {
  if (!v) return
  formRef.value?.resetValidation()
  if (props.eventId) {
    loading.value = true
    try {
      const ev = await api.event(props.eventId)
      const s = ev.all_day ? allDayToLocal(ev.start) : new Date(ev.start)
      const e = ev.all_day ? allDayToLocal(ev.end) : new Date(ev.end)
      title.value = ev.title
      calendarId.value = ev.calendar_id
      location.value = ev.location
      description.value = ev.description
      timezone.value = ev.timezone || browserTimeZone()
      exdates.value = ev.exdates
      fill(s, e, ev.all_day)
      rec.value = parseRRule(ev.rrule, s)
      isSeries.value = !!ev.rrule
    } catch (e) {
      notify.fail(e)
      open.value = false
    } finally {
      loading.value = false
    }
  } else {
    const p = props.preset ?? { start: new Date(), end: addMinutes(new Date(), 60), allDay: false }
    title.value = ''
    calendarId.value = p.calendarId ?? calendars.writable.find((c) => c.visible)?.id ?? calendars.writable[0]?.id ?? ''
    location.value = ''
    description.value = ''
    timezone.value = browserTimeZone()
    exdates.value = []
    fill(p.start, p.end, p.allDay)
    rec.value = emptyForm(p.start)
    isSeries.value = false
  }
})

/** Beim Verschieben des Beginns bleibt die Dauer erhalten. */
function changeStart(date: string | null, time: string | null) {
  const oldStart = start.value
  const duration = end.value.getTime() - oldStart.getTime()
  if (date !== null) startDate.value = date
  if (time !== null) startTime.value = time
  if (!startDate.value) return
  const newEnd = new Date(start.value.getTime() + Math.max(0, duration))
  endDate.value = dayKey(newEnd)
  if (!allDay.value) endTime.value = timeKey(newEnd)
  if (rec.value.freq === 'WEEKLY' && rec.value.byday.length <= 1) rec.value = { ...rec.value, byday: [jsDayToCode(start.value)] }
}

const endError = computed(() => (startDate.value && endDate.value && end.value < start.value ? 'Ende liegt vor dem Beginn' : ''))

const calendarItems = computed(() => calendars.writable.map((c) => ({ title: c.name, value: c.id, color: c.color })))

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid || endError.value) return
  const s = start.value
  const input: EventInput = {
    calendar_id: calendarId.value,
    title: title.value.trim(),
    description: description.value,
    location: location.value.trim(),
    start: allDay.value ? localToAllDay(s) : s.toISOString(),
    end: allDay.value ? localToAllDay(addDays(end.value, 1)) : end.value.toISOString(),
    all_day: allDay.value,
    timezone: timezone.value,
    rrule: buildRRule(rec.value, s),
    exdates: exdates.value,
  }
  saving.value = true
  try {
    if (props.eventId) await events.update(props.eventId, input)
    else await events.create(input)
    notify.info(props.eventId ? 'Termin gespeichert' : 'Termin erstellt')
    open.value = false
  } catch (e) {
    notify.fail(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog v-model="open" :fullscreen="xs" max-width="640" persistent @keydown.esc="open = false">
    <v-card color="surface-container-high" :loading="loading">
      <v-toolbar color="transparent" density="comfortable">
        <v-btn icon="mdi-close" @click="open = false" />
        <v-toolbar-title>{{ eventId ? 'Termin bearbeiten' : 'Neuer Termin' }}</v-toolbar-title>
        <v-btn color="primary" variant="flat" class="mr-3" :loading="saving" :disabled="loading" @click="save">Speichern</v-btn>
      </v-toolbar>
      <v-card-text class="pt-2">
        <v-form ref="formRef" :disabled="loading" @submit.prevent="save">
          <v-text-field
            v-model="title" label="Titel" autofocus maxlength="500" class="mb-1"
            :rules="[(v: string) => !!v.trim() || 'Bitte einen Titel eingeben']"
          />

          <v-select v-model="calendarId" :items="calendarItems" label="Kalender" prepend-inner-icon="mdi-calendar-outline" class="mb-1">
            <template #item="{ props: p, item }">
              <v-list-item v-bind="p">
                <template #prepend><span class="dot mr-3" :style="{ background: item.raw.color }" /></template>
              </v-list-item>
            </template>
            <template #selection="{ item }">
              <span class="dot mr-2" :style="{ background: item.raw.color }" />{{ item.title }}
            </template>
          </v-select>

          <v-switch v-model="allDay" label="Ganztägig" color="primary" hide-details inset class="mb-3" />

          <div class="d-flex ga-3 flex-wrap">
            <v-text-field
              :model-value="startDate" type="date" label="Beginn" class="flex-1-1" style="min-width: 160px"
              :rules="[(v: string) => !!v || 'Pflichtfeld']"
              @update:model-value="(v: string) => changeStart(v, null)"
            />
            <v-text-field
              v-if="!allDay" :model-value="startTime" type="time" label="Uhrzeit" style="max-width: 150px"
              @update:model-value="(v: string) => changeStart(null, v)"
            />
          </div>
          <div class="d-flex ga-3 flex-wrap">
            <v-text-field
              v-model="endDate" type="date" label="Ende" class="flex-1-1" style="min-width: 160px"
              :error-messages="endError" :rules="[(v: string) => !!v || 'Pflichtfeld']"
            />
            <v-text-field v-if="!allDay" v-model="endTime" type="time" label="Uhrzeit" style="max-width: 150px" />
          </div>
          <div v-if="!allDay && timezone !== browserTimeZone()" class="text-body-small text-medium-emphasis mb-3 mt-n2">
            <v-icon icon="mdi-earth" size="14" /> Zeitzone des Termins: {{ timezone }}
          </div>

          <RecurrenceEditor v-model="rec" :start="start" class="mb-4" />
          <v-alert v-if="isSeries" type="info" variant="tonal" density="compact" class="mb-4" icon="mdi-information-outline">
            Änderungen gelten für alle Termine dieser Serie.
          </v-alert>

          <v-text-field v-model="location" label="Ort" prepend-inner-icon="mdi-map-marker-outline" />
          <v-textarea v-model="description" label="Beschreibung" prepend-inner-icon="mdi-text" rows="3" auto-grow />
        </v-form>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.dot { display: inline-block; width: 12px; height: 12px; border-radius: 6px; flex: 0 0 12px; }
</style>
