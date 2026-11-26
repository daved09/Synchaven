<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDisplay } from 'vuetify'
import type { Task, TaskList, ViewEvent } from '@/api/types'
import AccountMenu from '@/components/AccountMenu.vue'
import EventDetails from '@/components/EventDetails.vue'
import EventDialog, { type EventPreset } from '@/components/EventDialog.vue'
import TaskDetails from '@/components/TaskDetails.vue'
import TaskDialog, { type TaskPreset } from '@/components/TaskDialog.vue'
import TaskPanel from '@/components/TaskPanel.vue'
import CalendarList from '@/components/calendar/CalendarList.vue'
import CalendarPager from '@/components/calendar/CalendarPager.vue'
import TaskListList from '@/components/calendar/TaskListList.vue'
import ViewModeMenu from '@/components/calendar/ViewModeMenu.vue'
import { useEventActions } from '@/composables/eventActions'
import { useShortcuts } from '@/composables/shortcuts'
import { useTaskActions } from '@/composables/taskActions'
import { useAuthStore } from '@/stores/auth'
import { useCalendarsStore } from '@/stores/calendars'
import { useEventsStore } from '@/stores/events'
import { useNotifyStore } from '@/stores/notify'
import { useSettingsStore, type ViewMode } from '@/stores/settings'
import { useTasksStore } from '@/stores/tasks'
import { useViewStore, VIEW_MODES } from '@/stores/view'
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
const eventActions = useEventActions()
const taskActions = useTaskActions()

const drawer = ref(mdAndUp.value)
const searchOpen = ref(false)
const pager = ref<InstanceType<typeof CalendarPager>>()

// Events
const detailsOpen = ref(false)
const selected = ref<ViewEvent | null>(null)
const editorOpen = ref(false)
const editId = ref<string | null>(null)
const preset = ref<EventPreset | null>(null)
/** Draft card of the event currently being created */
const draft = ref<EventDraft | null>(null)
const draftAnchor = ref<HTMLElement | null>(null)

// Tasks
const taskPanel = ref(false)
/** List selected in the task panel */
const taskListId = ref('')
const taskDetailsOpen = ref(false)
const taskDetailsId = ref<string | null>(null)
const taskEditorOpen = ref(false)
const editTask = ref<Task | null>(null)
const taskPreset = ref<TaskPreset | null>(null)

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
const findDraftEl = () => pager.value?.findDraftEl() ?? null
// If the card moves (other day, all-day …), a new element is created; the dialog follows it.
// If it disappears from the visible range, the dialog stays at the last position.
watch(draft, () => nextTick(() => {
  const el = findDraftEl()
  if (el) draftAnchor.value = el
}))

// --- URL sync (?view=week&date=2026-11-22) ---
function readRoute() {
  const m = route.query.view as ViewMode | undefined
  if (m && VIEW_MODES.includes(m)) view.show(m)
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
})

// --- Events ---
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
  if (await eventActions.remove(ev, scope)) detailsOpen.value = false
}
function moveEvent(ev: ViewEvent, newStart: Date) {
  if (isDraft(ev)) return
  if (ev.task) taskActions.move(ev.task, newStart)
  else eventActions.move(ev, newStart)
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
async function deleteTask(t: Task) {
  if (await taskActions.remove(t)) taskDetailsOpen.value = false
}
function showTaskList(l: TaskList) {
  taskListId.value = l.id
  taskPanel.value = true
}

function openSearch() {
  view.show('list')
  searchOpen.value = true
}
function closeSearch() {
  searchOpen.value = false
  view.search = ''
}

useShortcuts({
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
  '/': openSearch,
})
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
            {{ $t('calendarView.create') }}
          </v-btn>
        </template>
        <v-list density="compact" rounded="lg">
          <v-list-item prepend-icon="mdi-calendar-blank-outline" :title="$t('calendarView.event')" subtitle="C" @click="newEvent()" />
          <v-list-item prepend-icon="mdi-checkbox-marked-circle-outline" :title="$t('task.task')" subtitle="A" @click="newTask()" />
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
    <CalendarList />
    <TaskListList @show="showTaskList" />
  </v-navigation-drawer>

  <v-app-bar flat color="surface" density="comfortable" class="app-bar">
    <v-app-bar-nav-icon :title="$t('common.menu')" @click="drawer = !drawer" />
    <div v-if="lgAndUp" class="d-flex align-center mr-6">
      <v-icon icon="mdi-calendar-month" color="primary" size="28" class="mr-2" />
      <span class="text-title-large">{{ $t('nav.calendar') }}</span>
    </div>
    <v-btn v-if="!smAndDown" variant="outlined" class="mr-2" :title="`${$t('common.today')} (H)`" @click="view.today()">{{ $t('common.today') }}</v-btn>
    <v-btn v-else icon="mdi-calendar-today-outline" size="small" :title="$t('common.today')" @click="view.today()" />
    <v-btn v-if="!smAndDown" icon="mdi-chevron-left" :title="`${$t('common.previous')} (←)`" @click="view.step(-1)" />
    <v-btn v-if="!smAndDown" icon="mdi-chevron-right" :title="`${$t('common.next')} (→)`" @click="view.step(1)" />
    <div class="title ml-2" :class="smAndDown ? 'text-title-medium' : 'text-title-large'">{{ view.title }}</div>
    <v-spacer />
    <v-progress-circular v-if="events.loading" indeterminate size="20" width="2" color="primary" class="mr-3" />

    <v-text-field
      v-if="searchOpen" v-model="view.search" :placeholder="$t('calendarView.search')" density="compact" variant="solo-filled"
      flat rounded="xl" hide-details autofocus clearable prepend-inner-icon="mdi-magnify" class="search mr-2"
      bg-color="surface-container-high" @keydown.esc="closeSearch"
    />
    <v-btn v-else icon="mdi-magnify" :title="`${$t('common.search')} (/)`" @click="openSearch" />

    <ViewModeMenu />
    <v-btn
      :icon="taskPanel ? 'mdi-checkbox-marked-circle' : 'mdi-checkbox-marked-circle-outline'" :title="$t('tasks.title')"
      :active="taskPanel" @click="taskPanel = !taskPanel"
    />
    <v-btn icon="mdi-account-box-multiple-outline" :title="$t('nav.contacts')" :to="{ name: 'contacts' }" />
    <AccountMenu />
  </v-app-bar>

  <v-navigation-drawer
    v-model="taskPanel" location="right" :temporary="smAndDown" width="340" color="surface-container-low" border="0"
  >
    <TaskPanel v-model:list-id="taskListId" @open="openTask" @close="taskPanel = false" />
  </v-navigation-drawer>

  <v-main class="app-main">
    <CalendarPager
      ref="pager" :events="gridEvents" :agenda-events="calendarItems"
      @select="openEvent" @create="newEvent" @open-day="(d) => view.goto(d, 'day')" @move="moveEvent"
      @toggle="taskActions.toggle"
    />
    <v-btn
      v-if="smAndDown" class="fab" color="primary-container" size="x-large" icon="mdi-plus" rounded="lg" elevation="3"
      :title="$t('calendarView.createEvent')" @click="newEvent()"
    />
  </v-main>

  <EventDetails v-model="detailsOpen" :event="selected" @edit="editEvent" @delete="deleteEvent" />
  <EventDialog v-model="editorOpen" v-model:draft="draft" :event-id="editId" :preset="preset" :anchor="draftAnchor" />
  <TaskDetails
    v-model="taskDetailsOpen" :task-id="taskDetailsId" @edit="editTaskOpen" @delete="deleteTask" @toggle="taskActions.toggle"
    @open="openTask"
  />
  <TaskDialog v-model="taskEditorOpen" :task="editTask" :preset="taskPreset" />
</template>

<style scoped>
.app-bar { border-bottom: none; }
.title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.search { width: 280px; max-width: 40vw; }
.mini { min-width: 0; }
.mini :deep(.v-date-picker-month) { padding: 0 12px; }
.mini :deep(.v-date-picker-month__days) { column-gap: 0; flex: 1; grid-template-columns: repeat(7, minmax(0, 1fr)); }
.mini :deep(.v-date-picker-month__day) { width: auto; }
.mini :deep(.v-date-picker-controls) { padding-inline: 12px 4px; }
</style>
