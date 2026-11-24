<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDisplay } from 'vuetify'
import type { Calendar, Task, TaskList, ViewEvent } from '@/api/types'
import AccountMenu from '@/components/AccountMenu.vue'
import CalendarDialog from '@/components/CalendarDialog.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EventDetails from '@/components/EventDetails.vue'
import EventDialog, { type EventPreset } from '@/components/EventDialog.vue'
import MonthView from '@/components/MonthView.vue'
import AgendaView from '@/components/AgendaView.vue'
import TimeGrid from '@/components/TimeGrid.vue'
import TaskDetails from '@/components/TaskDetails.vue'
import TaskDialog, { type TaskPreset } from '@/components/TaskDialog.vue'
import TaskListDialog from '@/components/TaskListDialog.vue'
import TaskPanel from '@/components/TaskPanel.vue'
import { useAuthStore } from '@/stores/auth'
import { useCalendarsStore } from '@/stores/calendars'
import { useEventsStore } from '@/stores/events'
import { useNotifyStore } from '@/stores/notify'
import { useSettingsStore, type ViewMode } from '@/stores/settings'
import { useTasksStore } from '@/stores/tasks'
import { LIST_DAYS, useViewStore } from '@/stores/view'
import { addDays, addMinutes, dayKey, parseDayKey } from '@/utils/date'
import { draftEvent, isDraft, type EventDraft } from '@/utils/draft'

const route = useRoute()
const router = useRouter()
const { mdAndUp, lgAndUp, smAndDown } = useDisplay()
const auth = useAuthStore()
const calendars = useCalendarsStore()
const events = useEventsStore()
const notify = useNotifyStore()
const view = useViewStore()
const tasks = useTasksStore()
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
/** Draft card of the event currently being created */
const draft = ref<EventDraft | null>(null)
const draftAnchor = ref<HTMLElement | null>(null)
const calendarDialog = ref(false)
const editCalendar = ref<Calendar | null>(null)
const accountMenu = ref<InstanceType<typeof AccountMenu>>()

// Tasks
const taskPanel = ref(false)
/** List selected in the task panel */
const taskListId = ref('')
const taskDetailsOpen = ref(false)
const taskDetailsId = ref<string | null>(null)
const taskEditorOpen = ref(false)
const editTask = ref<Task | null>(null)
const taskPreset = ref<TaskPreset | null>(null)
const taskListDialog = ref(false)
const editTaskList = ref<TaskList | null>(null)

const modes: { value: ViewMode; title: string; icon: string; key: string }[] = [
  { value: 'month', title: 'Monat', icon: 'mdi-calendar-month-outline', key: 'M' },
  { value: 'week', title: 'Woche', icon: 'mdi-calendar-week-outline', key: 'W' },
  { value: 'day', title: 'Tag', icon: 'mdi-calendar-today-outline', key: 'T' },
  { value: 'list', title: 'Terminliste', icon: 'mdi-format-list-bulleted', key: 'L' },
]
const currentMode = computed(() => modes.find((m) => m.value === view.mode)!)
// On touch devices, the neighboring periods sit left and right of the current page, ready for swiping
const touchDevice = window.matchMedia('(pointer: coarse)').matches
const pages = computed(() => (touchDevice ? [-1, 0, 1] : [0]).map((off) => {
  const date = off === 0 ? view.date : view.shift(view.date, off)
  const { start, end } = view.rangeOf(date)
  const n = Math.round((end.getTime() - start.getTime()) / 86_400_000)
  // Keyed by date: after a swipe the neighboring page becomes the current one without rebuilding
  const key = `${view.mode}:${touchDevice ? dayKey(date) : 0}`
  return { off, key, date, start, end, days: Array.from({ length: n }, (_, i) => addDays(start, i)) }
}))

/** Events and due tasks, sorted by start (all-day first) */
const calendarItems = computed(() =>
  [...events.visible, ...tasks.calendarEvents].sort((a, b) =>
    a.startDate.getTime() - b.startDate.getTime() || Number(b.all_day) - Number(a.all_day) || a.title.localeCompare(b.title)),
)
/** Events in the grid including the draft card */
const gridEvents = computed(() => {
  const d = draft.value
  if (!d) return calendarItems.value
  return [...calendarItems.value, draftEvent(d, calendars.byId.get(d.calendarId)?.color ?? '#6750A4')]
})
const findDraftEl = () => surface.value?.querySelector<HTMLElement>('.page.current .draft') ?? null
// If the card moves (other day, all-day …), a new element is created; the dialog follows it.
// If it disappears from the visible range, the dialog stays at the last position.
watch(draft, () => nextTick(() => {
  const el = findDraftEl()
  if (el) draftAnchor.value = el
}))

// --- URL sync (?view=week&date=2026-11-22) ---
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

// --- Load data: current period plus previous and next (for swiping) ---
const loadRange = computed(() => ({
  start: view.rangeOf(view.shift(view.date, -1)).start,
  end: view.rangeOf(view.shift(view.date, 1)).end,
}))
watch(() => [loadRange.value.start.getTime(), loadRange.value.end.getTime()], () => {
  events.load(loadRange.value.start, loadRange.value.end).catch((e) => notify.fail(e))
})
onMounted(async () => {
  try {
    await Promise.all([auth.fetchMe(), calendars.load(), tasks.load()])
    await events.load(loadRange.value.start, loadRange.value.end)
  } catch (e) {
    notify.fail(e)
  }
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// --- Actions ---
function openEvent(ev: ViewEvent) {
  if (isDraft(ev)) return
  if (ev.task) return openTask(ev.task)
  selected.value = ev
  detailsOpen.value = true
}
async function newEvent(start?: Date, allDay = false) {
  let s = start
  if (!s) {
    const now = new Date()
    s = new Date(view.date)
    s.setHours(now.getHours() + 1, 0, 0, 0)
  } else if (view.mode === 'month' && !allDay) {
    s = new Date(s)
    s.setHours(9, 0, 0, 0)
  }
  const calendarId = calendars.writable.find((c) => c.visible)?.id ?? calendars.writable[0]?.id
  preset.value = { start: s, end: allDay ? addDays(s, 1) : addMinutes(s, 60), allDay, calendarId }
  editId.value = null
  // Show the card first so the dialog can open next to it
  draftAnchor.value = null
  if (calendarId && view.mode !== 'list') {
    draft.value = { ...preset.value, title: '', calendarId }
    await nextTick()
    draftAnchor.value = findDraftEl()
    draftAnchor.value?.scrollIntoView({ block: 'nearest' })
  }
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
  if (isDraft(ev)) return
  if (ev.task) {
    try {
      await tasks.move(ev.task, newStart)
      notify.info('Aufgabe verschoben')
    } catch (e) {
      notify.fail(e)
    }
    return
  }
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
// --- Tasks ---
function openTask(t: Task) {
  taskDetailsId.value = t.id
  taskDetailsOpen.value = true
}
/** New task; without a date, due on the displayed day (like in Google Calendar) */
function newTask(due?: Date, allDay = true) {
  editTask.value = null
  taskPreset.value = { due: due ?? view.date, allDay, listId: taskListId.value || undefined }
  taskEditorOpen.value = true
}
function editTaskOpen(t: Task) {
  taskDetailsOpen.value = false
  editTask.value = t
  taskPreset.value = null
  taskEditorOpen.value = true
}
async function toggleTask(t: Task) {
  try {
    const done = !t.completed
    await tasks.setCompleted(t, done)
    notify.info(done ? 'Aufgabe erledigt' : 'Aufgabe wieder offen')
  } catch (e) {
    notify.fail(e)
  }
}
async function deleteTask(t: Task) {
  const subs = tasks.subtasksOf(t.id).length
  const text = subs === 0 ? `„${t.title}“ wird gelöscht.`
    : `„${t.title}“ und ${subs === 1 ? 'eine Unteraufgabe werden' : `${subs} Unteraufgaben werden`} gelöscht.`
  if (!(await confirmRef.value!.ask('Aufgabe löschen?', text))) return
  try {
    await tasks.remove(t.id)
    taskDetailsOpen.value = false
    notify.info('Aufgabe gelöscht')
  } catch (e) {
    notify.fail(e)
  }
}
async function toggleTaskList(l: TaskList) {
  try {
    await tasks.toggleListVisible(l)
  } catch (e) {
    notify.fail(e)
  }
}
function newTaskList() {
  editTaskList.value = null
  taskListDialog.value = true
}
function editTaskListOpen(l: TaskList) {
  editTaskList.value = l
  taskListDialog.value = true
}
function showTaskList(l: TaskList) {
  taskListId.value = l.id
  taskPanel.value = true
}
async function deleteTaskList(l: TaskList) {
  if (!(await confirmRef.value!.ask('Liste löschen?', `„${l.name}“ und alle enthaltenen Aufgaben werden unwiderruflich gelöscht.`))) return
  try {
    await tasks.removeList(l.id)
    notify.info('Liste gelöscht')
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

// --- Swipe gestures (touch): the view follows the finger and snaps to a period on release ---
const SETTLE_MS = 250
const surface = ref<HTMLElement>()
/** Current offset of the pages in pixels */
const dragX = ref(0)
/** Pages are currently gliding into their final position */
const settling = ref(false)
let swipe: { x: number; y: number; axis: 'x' | 'y' | null; lastX: number; lastT: number; v: number } | null = null

function onTouchStart(e: TouchEvent) {
  if (!touchDevice || settling.value || e.touches.length !== 1) {
    swipe = null
    return
  }
  const t = e.touches[0]
  swipe = { x: t.clientX, y: t.clientY, axis: null, lastX: t.clientX, lastT: e.timeStamp, v: 0 }
}
function onTouchMove(e: TouchEvent) {
  if (!swipe) return
  if (e.touches.length !== 1) return endSwipe(e, true)
  const t = e.touches[0]
  const dx = t.clientX - swipe.x
  const dy = t.clientY - swipe.y
  if (!swipe.axis) {
    // Only determine the direction after some movement – vertical stays normal scrolling
    if (Math.hypot(dx, dy) < 10) return
    swipe.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y'
    if (swipe.axis === 'x') syncGridScroll()
  }
  if (swipe.axis !== 'x') return
  const dt = e.timeStamp - swipe.lastT
  if (dt > 0) swipe.v = 0.8 * ((t.clientX - swipe.lastX) / dt) + 0.2 * swipe.v
  swipe.lastX = t.clientX
  swipe.lastT = e.timeStamp
  dragX.value = dx
}
function endSwipe(e: TouchEvent, cancel = false) {
  const s = swipe
  swipe = null
  if (!s || s.axis !== 'x') return
  const w = surface.value?.clientWidth || window.innerWidth
  const dx = dragX.value
  // Finger stopped before release → no momentum
  const v = e.timeStamp - s.lastT > 100 ? 0 : s.v
  const fling = Math.abs(v) > 0.4 && Math.sign(v) === Math.sign(dx)
  const dir: -1 | 0 | 1 = cancel || (Math.abs(dx) < w / 2 && !fling) ? 0 : dx < 0 ? 1 : -1
  settling.value = true
  dragX.value = -dir * w
  setTimeout(() => {
    // Reset without transition: the neighboring page is now the current one and already in place
    settling.value = false
    if (dir) view.step(dir)
    dragX.value = 0
  }, SETTLE_MS)
}
/** Scroll neighboring pages to the same time as the current one, so the hours line up while swiping */
function syncGridScroll() {
  const current = surface.value?.querySelector<HTMLElement>('.page.current .tg-scroll')
  if (!current) return
  surface.value!.querySelectorAll<HTMLElement>('.page:not(.current) .tg-scroll').forEach((el) => {
    el.scrollTop = current.scrollTop
  })
}

// --- Keyboard shortcuts ---
const anyDialog = () => detailsOpen.value || editorOpen.value || calendarDialog.value || !!accountMenu.value?.dialogOpen
  || taskDetailsOpen.value || taskEditorOpen.value || taskListDialog.value
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
    a: () => newTask(),
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
      <v-menu location="bottom start">
        <template #activator="{ props: p }">
          <v-btn
            v-bind="p" color="primary-container" size="x-large" rounded="lg" class="create-btn" prepend-icon="mdi-plus"
            append-icon="mdi-menu-down" elevation="1"
          >
            Erstellen
          </v-btn>
        </template>
        <v-list density="compact" rounded="lg">
          <v-list-item prepend-icon="mdi-calendar-blank-outline" title="Termin" subtitle="C" @click="newEvent()" />
          <v-list-item prepend-icon="mdi-checkbox-marked-circle-outline" title="Aufgabe" subtitle="A" @click="newTask()" />
        </v-list>
      </v-menu>
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
    <v-list nav density="comfortable" class="pt-0" bg-color="transparent">
      <div class="d-flex align-center pl-4 pr-2">
        <v-list-subheader class="flex-grow-1 px-0">Aufgaben</v-list-subheader>
        <v-btn icon="mdi-plus" size="small" variant="text" title="Neue Aufgabenliste" @click="newTaskList" />
      </div>
      <v-list-item v-for="l in tasks.lists" :key="l.id" rounded="xl" class="cal-item" @click="toggleTaskList(l)">
        <template #prepend>
          <v-checkbox-btn :model-value="l.visible" :color="l.color" :base-color="l.color" density="compact" class="mr-1" @click.stop="toggleTaskList(l)" />
        </template>
        <v-list-item-title>{{ l.name }}</v-list-item-title>
        <template #append>
          <span v-if="tasks.openCount(l.id)" class="text-label-medium text-medium-emphasis mr-1">{{ tasks.openCount(l.id) }}</span>
          <v-menu location="end">
            <template #activator="{ props: p }">
              <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" class="cal-menu" @click.stop />
            </template>
            <v-list density="compact" rounded="lg">
              <v-list-item prepend-icon="mdi-format-list-checks" title="Aufgaben anzeigen" @click="showTaskList(l)" />
              <v-list-item prepend-icon="mdi-pencil-outline" title="Bearbeiten" @click="editTaskListOpen(l)" />
              <template v-if="tasks.lists.length > 1">
                <v-divider />
                <v-list-item prepend-icon="mdi-delete-outline" title="Löschen" base-color="error" @click="deleteTaskList(l)" />
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

    <v-btn
      :icon="taskPanel ? 'mdi-checkbox-marked-circle' : 'mdi-checkbox-marked-circle-outline'" title="Aufgaben"
      :active="taskPanel" @click="taskPanel = !taskPanel"
    />
    <v-btn icon="mdi-account-box-multiple-outline" title="Kontakte" :to="{ name: 'contacts' }" />
    <AccountMenu ref="accountMenu" />
  </v-app-bar>

  <v-navigation-drawer
    v-model="taskPanel" location="right" :temporary="smAndDown" width="340" color="surface-container-low" border="0"
  >
    <TaskPanel v-model:list-id="taskListId" @open="openTask" @close="taskPanel = false" />
  </v-navigation-drawer>

  <v-main class="main">
    <div
      ref="surface" class="view-surface"
      @touchstart.passive="onTouchStart" @touchmove.passive="onTouchMove"
      @touchend.passive="endSwipe($event)" @touchcancel.passive="endSwipe($event, true)"
    >
      <div
        class="track" :class="{ settling }"
        :style="touchDevice ? { transform: `translateX(calc(-100% + ${dragX}px))` } : undefined"
      >
        <div
          v-for="p in pages" :key="p.key" class="page" :class="{ current: p.off === 0 }"
          :inert="p.off !== 0 || undefined"
        >
          <MonthView
            v-if="view.mode === 'month'"
            :events="gridEvents" :start="p.start" :end="p.end" :month="p.date.getMonth()"
            :week-starts-on="settings.weekStartsOn" :show-week-numbers="settings.showWeekNumbers && mdAndUp"
            @select="openEvent" @create="(d) => newEvent(d)" @open-day="openDay" @move="moveEvent"
            @toggle="(ev) => ev.task && toggleTask(ev.task)"
          />
          <TimeGrid
            v-else-if="view.mode === 'week' || view.mode === 'day'"
            :events="gridEvents" :days="p.days" :day-start-hour="settings.dayStartHour"
            @select="openEvent" @create="(s, allDay) => newEvent(s, allDay)" @open-day="openDay" @move="moveEvent"
            @toggle="(ev) => ev.task && toggleTask(ev.task)"
          />
          <AgendaView
            v-else
            :events="calendarItems" :start="p.start" :days="LIST_DAYS" :search="view.search"
            @select="openEvent" @open-day="openDay" @toggle="(ev) => ev.task && toggleTask(ev.task)"
          />
        </div>
      </div>
    </div>
    <v-btn
      v-if="smAndDown" class="fab" color="primary-container" size="x-large" icon="mdi-plus" rounded="lg" elevation="3"
      title="Termin erstellen" @click="newEvent()"
    />
  </v-main>

  <EventDetails v-model="detailsOpen" :event="selected" @edit="editEvent" @delete="deleteEvent" />
  <EventDialog v-model="editorOpen" v-model:draft="draft" :event-id="editId" :preset="preset" :anchor="draftAnchor" />
  <CalendarDialog v-model="calendarDialog" :calendar="editCalendar" />
  <TaskDetails
    v-model="taskDetailsOpen" :task-id="taskDetailsId" @edit="editTaskOpen" @delete="deleteTask" @toggle="toggleTask"
    @open="openTask"
  />
  <TaskDialog v-model="taskEditorOpen" :task="editTask" :preset="taskPreset" />
  <TaskListDialog v-model="taskListDialog" :list="editTaskList" />
  <ConfirmDialog ref="confirmRef" />
</template>

<style scoped>
.app-bar { border-bottom: none; }
.title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.main { height: 100dvh; background: rgb(var(--v-theme-surface-container-low)); }
.view-surface {
  height: 100%; background: rgb(var(--v-theme-surface)); border-top-left-radius: 24px; overflow: hidden;
}
.track { display: flex; height: 100%; }
.track.settling { transition: transform .25s cubic-bezier(.2, 0, 0, 1); }
.page { flex: 0 0 100%; min-width: 0; height: 100%; overflow: hidden; }
/* Horizontal dragging belongs to the swipe gesture, vertical to scrolling */
.page, .page :deep(.tg-scroll), .page :deep(.agenda) { touch-action: pan-y; }
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
