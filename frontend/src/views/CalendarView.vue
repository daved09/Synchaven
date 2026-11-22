<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDisplay } from 'vuetify'
import type { Calendar, ViewEvent } from '@/api/types'
import AccountMenu from '@/components/AccountMenu.vue'
import CalendarDialog from '@/components/CalendarDialog.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EventDetails from '@/components/EventDetails.vue'
import EventDialog, { type EventPreset } from '@/components/EventDialog.vue'
import MonthView from '@/components/MonthView.vue'
import AgendaView from '@/components/AgendaView.vue'
import TimeGrid from '@/components/TimeGrid.vue'
import { useAuthStore } from '@/stores/auth'
import { useCalendarsStore } from '@/stores/calendars'
import { useEventsStore } from '@/stores/events'
import { useNotifyStore } from '@/stores/notify'
import { useSettingsStore, type ViewMode } from '@/stores/settings'
import { LIST_DAYS, useViewStore } from '@/stores/view'
import { addDays, addMinutes, dayKey, parseDayKey } from '@/utils/date'

const route = useRoute()
const router = useRouter()
const { mdAndUp, lgAndUp, smAndDown } = useDisplay()
const auth = useAuthStore()
const calendars = useCalendarsStore()
const events = useEventsStore()
const notify = useNotifyStore()
const view = useViewStore()
const { settings } = useSettingsStore()

const drawer = ref(mdAndUp.value)
const searchOpen = ref(false)
const confirmRef = ref<InstanceType<typeof ConfirmDialog>>()
const importInput = ref<HTMLInputElement>()
let importTarget: Calendar | null = null

const detailsOpen = ref(false)
const selected = ref<ViewEvent | null>(null)
const editorOpen = ref(false)
const editId = ref<string | null>(null)
const preset = ref<EventPreset | null>(null)
const calendarDialog = ref(false)
const editCalendar = ref<Calendar | null>(null)
const accountMenu = ref<InstanceType<typeof AccountMenu>>()

const modes: { value: ViewMode; title: string; icon: string; key: string }[] = [
  { value: 'month', title: 'Monat', icon: 'mdi-calendar-month-outline', key: 'M' },
  { value: 'week', title: 'Woche', icon: 'mdi-calendar-week-outline', key: 'W' },
  { value: 'day', title: 'Tag', icon: 'mdi-calendar-today-outline', key: 'T' },
  { value: 'list', title: 'Terminliste', icon: 'mdi-format-list-bulleted', key: 'L' },
]
const currentMode = computed(() => modes.find((m) => m.value === view.mode)!)
const days = computed(() => {
  const { start, end } = view.range
  const n = Math.round((end.getTime() - start.getTime()) / 86_400_000)
  return Array.from({ length: n }, (_, i) => addDays(start, i))
})

// --- URL-Synchronisierung (?view=week&date=2026-11-22) ---
function readRoute() {
  const m = route.query.view as ViewMode | undefined
  if (m && modes.some((x) => x.value === m)) view.show(m)
  const d = parseDayKey((route.query.date as string) ?? '')
  if (d) view.date = d
}
readRoute()
watch(() => [view.mode, view.date] as const, ([mode, date]) => {
  router.replace({ query: { view: mode, date: dayKey(date) } })
})

// --- Daten laden ---
watch(() => [view.range.start.getTime(), view.range.end.getTime()], () => {
  events.load(view.range.start, view.range.end).catch((e) => notify.fail(e))
})
onMounted(async () => {
  try {
    await Promise.all([auth.fetchMe(), calendars.load()])
    await events.load(view.range.start, view.range.end)
  } catch (e) {
    notify.fail(e)
  }
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// --- Aktionen ---
function openEvent(ev: ViewEvent) {
  selected.value = ev
  detailsOpen.value = true
}
function newEvent(start?: Date, allDay = false) {
  let s = start
  if (!s) {
    const now = new Date()
    s = new Date(view.date)
    s.setHours(now.getHours() + 1, 0, 0, 0)
  } else if (view.mode === 'month' && !allDay) {
    s = new Date(s)
    s.setHours(9, 0, 0, 0)
  }
  preset.value = { start: s, end: allDay ? addDays(s, 1) : addMinutes(s, 60), allDay }
  editId.value = null
  editorOpen.value = true
}
function editEvent(ev: ViewEvent) {
  detailsOpen.value = false
  editId.value = ev.event_id
  preset.value = null
  editorOpen.value = true
}
async function deleteEvent(ev: ViewEvent, scope: 'one' | 'all') {
  const text = scope === 'one'
    ? `Nur der Termin am ${ev.startDate.toLocaleDateString('de-DE')} wird aus der Serie entfernt.`
    : ev.recurring ? 'Alle Termine dieser Serie werden gelöscht.' : `„${ev.title}“ wird gelöscht.`
  if (!(await confirmRef.value!.ask('Termin löschen?', text))) return
  try {
    if (scope === 'one') await events.removeOccurrence(ev.event_id, ev.start)
    else await events.remove(ev.event_id)
    detailsOpen.value = false
    notify.info('Termin gelöscht')
  } catch (e) {
    notify.fail(e)
  }
}
async function moveEvent(ev: ViewEvent, newStart: Date) {
  if (ev.recurring && !(await confirmRef.value!.ask(
    'Serientermin verschieben?',
    `Nur der Termin am ${ev.startDate.toLocaleDateString('de-DE')} wird verschoben und dabei aus der Serie gelöst.`,
    'Verschieben',
    'primary',
  ))) return
  try {
    await events.move(ev, newStart)
    notify.info('Termin verschoben')
  } catch (e) {
    notify.fail(e)
  }
}
function openDay(d: Date) {
  view.goto(d, 'day')
}

function newCalendar() {
  editCalendar.value = null
  calendarDialog.value = true
}
function editCal(cal: Calendar) {
  editCalendar.value = cal
  calendarDialog.value = true
}
async function toggleCalendar(cal: Calendar) {
  try {
    await calendars.toggleVisible(cal)
  } catch (e) {
    notify.fail(e)
  }
}
async function deleteCalendar(cal: Calendar) {
  if (!(await confirmRef.value!.ask('Kalender löschen?', `„${cal.name}“ und alle enthaltenen Termine werden unwiderruflich gelöscht.`))) return
  try {
    await calendars.remove(cal.id)
    await events.reload()
    notify.info('Kalender gelöscht')
  } catch (e) {
    notify.fail(e)
  }
}
async function exportCalendar(cal: Calendar) {
  try {
    await calendars.exportIcs(cal)
  } catch (e) {
    notify.fail(e)
  }
}
function startImport(cal: Calendar) {
  importTarget = cal
  importInput.value!.value = ''
  importInput.value!.click()
}
async function onImportFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file || !importTarget) return
  try {
    const r = await calendars.importIcs(importTarget, file)
    await events.reload()
    notify.info(`${r.imported} Termin(e) importiert${r.skipped.length ? `, ${r.skipped.length} Hinweis(e)` : ''}`)
  } catch (err) {
    notify.fail(err)
  }
}
watch(calendarDialog, (v) => !v && events.reload())

// --- Wischgesten (Handy): links/rechts blättern ---
let touchX = 0
let touchY = 0
function onTouchStart(e: TouchEvent) {
  touchX = e.touches[0].clientX
  touchY = e.touches[0].clientY
}
function onTouchEnd(e: TouchEvent) {
  const dx = e.changedTouches[0].clientX - touchX
  const dy = e.changedTouches[0].clientY - touchY
  if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 2) view.step(dx < 0 ? 1 : -1)
}

// --- Tastenkürzel ---
const anyDialog = () => detailsOpen.value || editorOpen.value || calendarDialog.value || !!accountMenu.value?.dialogOpen
function onKey(e: KeyboardEvent) {
  const t = e.target as HTMLElement
  if (e.ctrlKey || e.metaKey || e.altKey || anyDialog()) return
  if (t.closest('input, textarea, select, [contenteditable="true"], .v-overlay')) return
  const k = e.key.toLowerCase()
  const byKey: Record<string, () => void> = {
    m: () => view.select('month'),
    w: () => view.select('week'),
    t: () => view.select('day'),
    l: () => view.select('list'),
    h: () => view.today(),
    c: () => newEvent(),
    n: () => newEvent(),
    arrowleft: () => view.step(-1),
    arrowright: () => view.step(1),
    j: () => view.step(1),
    k: () => view.step(-1),
    '/': () => { view.show('list'); searchOpen.value = true },
  }
  const fn = byKey[k]
  if (fn) {
    e.preventDefault()
    fn()
  }
}
</script>

<template>
  <v-navigation-drawer
    v-model="drawer" :temporary="smAndDown" :mobile="smAndDown" width="320" color="surface-container-low" border="0"
  >
    <div class="pa-4 pb-2">
      <v-btn
        color="primary-container" size="x-large" rounded="lg" class="create-btn" prepend-icon="mdi-plus" elevation="1"
        @click="newEvent()"
      >
        Erstellen
      </v-btn>
    </div>
    <v-date-picker
      :model-value="view.date"
      hide-header
      show-adjacent-months
      :first-day-of-week="settings.weekStartsOn"
      color="primary"
      bg-color="transparent"
      density="compact"
      width="100%"
      class="mini"
      @update:model-value="(d: unknown) => view.goto(d as Date)"
    />
    <v-list nav density="comfortable" class="pt-0" bg-color="transparent">
      <div class="d-flex align-center pl-4 pr-2">
        <v-list-subheader class="flex-grow-1 px-0">Meine Kalender</v-list-subheader>
        <v-btn icon="mdi-plus" size="small" variant="text" title="Neuer Kalender" @click="newCalendar" />
      </div>
      <v-list-item v-for="cal in calendars.list" :key="cal.id" rounded="xl" class="cal-item" @click="toggleCalendar(cal)">
        <template #prepend>
          <v-checkbox-btn :model-value="cal.visible" :color="cal.color" :base-color="cal.color" density="compact" class="mr-1" @click.stop="toggleCalendar(cal)" />
        </template>
        <v-list-item-title>{{ cal.name }}</v-list-item-title>
        <template #append>
          <v-menu location="end">
            <template #activator="{ props: p }">
              <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" class="cal-menu" @click.stop />
            </template>
            <v-list density="compact" rounded="lg">
              <v-list-item prepend-icon="mdi-pencil-outline" title="Bearbeiten" @click="editCal(cal)" />
              <v-list-item prepend-icon="mdi-download-outline" title="Exportieren (.ics)" @click="exportCalendar(cal)" />
              <template v-if="cal.kind !== 'birthdays'">
                <v-list-item prepend-icon="mdi-upload-outline" title="Importieren (.ics)" @click="startImport(cal)" />
                <v-divider />
                <v-list-item prepend-icon="mdi-delete-outline" title="Löschen" base-color="error" @click="deleteCalendar(cal)" />
              </template>
            </v-list>
          </v-menu>
        </template>
      </v-list-item>
    </v-list>
    <input ref="importInput" type="file" accept=".ics,text/calendar" hidden @change="onImportFile" />
  </v-navigation-drawer>

  <v-app-bar flat color="surface" density="comfortable" class="app-bar">
    <v-app-bar-nav-icon title="Menü" @click="drawer = !drawer" />
    <div v-if="lgAndUp" class="d-flex align-center mr-6">
      <v-icon icon="mdi-calendar-month" color="primary" size="28" class="mr-2" />
      <span class="text-title-large">Kalender</span>
    </div>
    <v-btn v-if="!smAndDown" variant="outlined" class="mr-2" title="Heute (H)" @click="view.today()">Heute</v-btn>
    <v-btn v-else icon="mdi-calendar-today-outline" size="small" title="Heute" @click="view.today()" />
    <v-btn v-if="!smAndDown" icon="mdi-chevron-left" title="Zurück (←)" @click="view.step(-1)" />
    <v-btn v-if="!smAndDown" icon="mdi-chevron-right" title="Weiter (→)" @click="view.step(1)" />
    <div class="title ml-2" :class="smAndDown ? 'text-title-medium' : 'text-title-large'">{{ view.title }}</div>
    <v-spacer />
    <v-progress-circular v-if="events.loading" indeterminate size="20" width="2" color="primary" class="mr-3" />

    <template v-if="view.mode === 'list' || searchOpen">
      <v-text-field
        v-if="searchOpen" v-model="view.search" placeholder="Termine durchsuchen" density="compact" variant="solo-filled"
        flat rounded="xl" hide-details autofocus clearable prepend-inner-icon="mdi-magnify" class="search mr-2"
        bg-color="surface-container-high"
        @keydown.esc="searchOpen = false; view.search = ''"
      />
    </template>
    <v-btn
      v-if="!searchOpen" icon="mdi-magnify" title="Suchen (/)"
      @click="view.show('list'); searchOpen = true"
    />

    <v-btn-toggle
      v-if="lgAndUp" :model-value="view.mode" mandatory divided variant="outlined" rounded="xl" density="comfortable" class="mx-2"
      @update:model-value="(m: ViewMode) => view.select(m)"
    >
      <v-btn v-for="m in modes" :key="m.value" :value="m.value" :title="`${m.title} (${m.key})`">{{ m.title }}</v-btn>
    </v-btn-toggle>
    <v-menu v-else>
      <template #activator="{ props: p }">
        <v-btn v-bind="p" :icon="currentMode.icon" :title="currentMode.title" />
      </template>
      <v-list density="compact" rounded="lg">
        <v-list-item
          v-for="m in modes" :key="m.value" :prepend-icon="m.icon" :title="m.title" :active="view.mode === m.value"
          @click="view.select(m.value)"
        />
      </v-list>
    </v-menu>

    <v-btn icon="mdi-account-box-multiple-outline" title="Kontakte" :to="{ name: 'contacts' }" />
    <AccountMenu ref="accountMenu" />
  </v-app-bar>

  <v-main class="main">
    <div class="view-surface" @touchstart.passive="onTouchStart" @touchend.passive="onTouchEnd">
      <MonthView
        v-if="view.mode === 'month'"
        :events="events.visible" :start="view.range.start" :end="view.range.end" :month="view.date.getMonth()"
        :week-starts-on="settings.weekStartsOn" :show-week-numbers="settings.showWeekNumbers && mdAndUp"
        @select="openEvent" @create="(d) => newEvent(d)" @open-day="openDay" @move="moveEvent"
      />
      <TimeGrid
        v-else-if="view.mode === 'week' || view.mode === 'day'"
        :key="view.mode"
        :events="events.visible" :days="days" :day-start-hour="settings.dayStartHour"
        @select="openEvent" @create="(s, allDay) => newEvent(s, allDay)" @open-day="openDay" @move="moveEvent"
      />
      <AgendaView
        v-else
        :events="events.visible" :start="view.range.start" :days="LIST_DAYS" :search="view.search"
        @select="openEvent" @open-day="openDay"
      />
    </div>
    <v-btn
      v-if="smAndDown" class="fab" color="primary-container" size="x-large" icon="mdi-plus" rounded="lg" elevation="3"
      title="Termin erstellen" @click="newEvent()"
    />
  </v-main>

  <EventDetails v-model="detailsOpen" :event="selected" @edit="editEvent" @delete="deleteEvent" />
  <EventDialog v-model="editorOpen" :event-id="editId" :preset="preset" />
  <CalendarDialog v-model="calendarDialog" :calendar="editCalendar" />
  <ConfirmDialog ref="confirmRef" />
</template>

<style scoped>
.app-bar { border-bottom: none; }
.title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.main { height: 100dvh; background: rgb(var(--v-theme-surface-container-low)); }
.view-surface {
  height: 100%; background: rgb(var(--v-theme-surface)); border-top-left-radius: 24px; overflow: hidden;
}
.create-btn { text-transform: none; letter-spacing: .1px; font-weight: 500; }
.search { width: 280px; max-width: 40vw; }
.cal-item .cal-menu { opacity: 0; transition: opacity .15s; }
.cal-item:hover .cal-menu, .cal-item:focus-within .cal-menu { opacity: 1; }
@media (hover: none) { .cal-item .cal-menu { opacity: 1; } }
.mini { min-width: 0; }
.mini :deep(.v-date-picker-month) { padding: 0 12px; }
.mini :deep(.v-date-picker-month__days) { column-gap: 0; flex: 1; grid-template-columns: repeat(7, minmax(0, 1fr)); }
.mini :deep(.v-date-picker-month__day) { width: auto; }
.mini :deep(.v-date-picker-controls) { padding-inline: 12px 4px; }
.fab { position: fixed; right: 16px; bottom: 16px; z-index: 5; }
@media (max-width: 959px) { .view-surface { border-radius: 0; } }
</style>
