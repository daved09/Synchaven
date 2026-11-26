<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDisplay } from 'vuetify'
import type { Task, ViewEvent } from '@/api/types'
import AgendaView from '@/components/AgendaView.vue'
import MonthView from '@/components/MonthView.vue'
import TimeGrid from '@/components/TimeGrid.vue'
import { useSettingsStore } from '@/stores/settings'
import { LIST_DAYS, useViewStore } from '@/stores/view'
import { addDays, dayKey } from '@/utils/date'

/**
 * The current period in the selected view. On touch devices the neighboring periods sit left and right of it:
 * the pages follow the finger and snap to a period on release.
 */
defineProps<{
  /** Events in the month and time grid (including the draft card) */
  events: ViewEvent[]
  /** Events in the list */
  agendaEvents: ViewEvent[]
}>()
const emit = defineEmits<{
  select: [ev: ViewEvent]
  create: [start: Date, allDay: boolean]
  openDay: [day: Date]
  move: [ev: ViewEvent, newStart: Date]
  toggle: [task: Task]
}>()

const { mdAndUp } = useDisplay()
const view = useViewStore()
const { settings } = useSettingsStore()

const touchDevice = window.matchMedia('(pointer: coarse)').matches
const pages = computed(() => (touchDevice ? [-1, 0, 1] : [0]).map((off) => {
  const date = off === 0 ? view.date : view.shift(view.date, off)
  const { start, end } = view.rangeOf(date)
  const n = Math.round((end.getTime() - start.getTime()) / 86_400_000)
  // Keyed by date: after a swipe the neighboring page becomes the current one without rebuilding
  const key = `${view.mode}:${touchDevice ? dayKey(date) : 0}`
  return { off, key, date, start, end, days: Array.from({ length: n }, (_, i) => addDays(start, i)) }
}))

const onToggle = (ev: ViewEvent) => ev.task && emit('toggle', ev.task)

// --- Swipe gestures ---
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

/** Element of the draft card on the current page */
const findDraftEl = () => surface.value?.querySelector<HTMLElement>('.page.current .draft') ?? null
defineExpose({ findDraftEl })
</script>

<template>
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
          :events="events" :start="p.start" :end="p.end" :month="p.date.getMonth()"
          :week-starts-on="settings.weekStartsOn" :show-week-numbers="settings.showWeekNumbers && mdAndUp"
          @select="(ev) => emit('select', ev)" @create="(d) => emit('create', d, false)" @open-day="(d) => emit('openDay', d)"
          @move="(ev, s) => emit('move', ev, s)" @toggle="onToggle"
        />
        <TimeGrid
          v-else-if="view.mode === 'week' || view.mode === 'day'"
          :events="events" :days="p.days"
          @select="(ev) => emit('select', ev)" @create="(s, allDay) => emit('create', s, allDay)" @open-day="(d) => emit('openDay', d)"
          @move="(ev, s) => emit('move', ev, s)" @toggle="onToggle"
        />
        <AgendaView
          v-else
          :events="agendaEvents" :start="p.start" :days="LIST_DAYS" :search="view.search"
          @select="(ev) => emit('select', ev)" @open-day="(d) => emit('openDay', d)" @toggle="onToggle"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.track { display: flex; height: 100%; }
.track.settling { transition: transform .25s cubic-bezier(.2, 0, 0, 1); }
.page { flex: 0 0 100%; min-width: 0; height: 100%; overflow: hidden; }
/* Horizontal dragging belongs to the swipe gesture, vertical to scrolling */
.page, .page :deep(.tg-scroll), .page :deep(.agenda) { touch-action: pan-y; }
</style>
