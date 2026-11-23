<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ViewEvent } from '@/api/types'
import { addDays, diffDays, fmt, fmtTime, isSameDay, isoWeek, startOfDay } from '@/utils/date'
import { isBarEvent, lastDay, layoutDay, layoutRow } from '@/utils/layout'
import { indexAtX, trackDrag } from '@/utils/drag'
import { onColor } from '@/plugins/theme'
import { isDraft } from '@/utils/draft'
import { taskIcon } from '@/stores/tasks'

const props = defineProps<{
  events: ViewEvent[]
  days: Date[]
  dayStartHour: number
}>()
const emit = defineEmits<{
  select: [ev: ViewEvent, el: HTMLElement]
  create: [start: Date, allDay: boolean]
  openDay: [day: Date]
  move: [ev: ViewEvent, newStart: Date]
  /** Aufgabe abhaken oder wieder öffnen */
  toggle: [ev: ViewEvent]
}>()

const HOUR = 48
const LANE = 24
const PX_PER_MIN = HOUR / 60
const hours = Array.from({ length: 24 }, (_, h) => h)
const SNAP = 15
const scroller = ref<HTMLElement>()
const allday = ref<HTMLElement>()
const now = ref(new Date())
let timer: number | undefined

const barItems = computed(() => layoutRow(props.events.filter(isBarEvent), props.days[0], props.days.length))
const barLanes = computed(() => barItems.value.reduce((m, i) => Math.max(m, i.lane + 1), 0))
const columns = computed(() => props.days.map((d) => ({ day: d, items: layoutDay(props.events, d) })))
const nowTop = computed(() => (now.value.getTime() - startOfDay(now.value).getTime()) / 60_000 * PX_PER_MIN)

function hourLabel(h: number) {
  return h === 0 ? '' : fmtTime(new Date(2026, 0, 1, h))
}

function onColumnClick(e: MouseEvent, day: Date) {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const minutes = Math.floor((e.clientY - rect.top) / PX_PER_MIN / 30) * 30
  const start = startOfDay(day)
  start.setMinutes(Math.max(0, Math.min(minutes, 23 * 60 + 30)))
  emit('create', start, false)
}
function onAllDayClick(e: MouseEvent) {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const idx = Math.floor(((e.clientX - rect.left) / rect.width) * props.days.length)
  emit('create', props.days[Math.max(0, Math.min(idx, props.days.length - 1))], true)
}

// --- Drag & Drop ---
interface DragState { ev: ViewEvent; bar: boolean; lane: number; start: Date }
const drag = ref<DragState | null>(null)
let lastPointer: PointerEvent | null = null
let onScrollDuringDrag: (() => void) | null = null

const colEls = () => Array.from(scroller.value?.querySelectorAll<HTMLElement>('.col') ?? [])
function alldayIndex(x: number) {
  const r = allday.value!.getBoundingClientRect()
  return Math.max(0, Math.min(props.days.length - 1, Math.floor(((x - r.left) / r.width) * props.days.length)))
}

function onTimedDown(e: PointerEvent, ev: ViewEvent, colIdx: number) {
  if (ev.read_only) return
  const y0 = e.clientY
  const scroll0 = scroller.value!.scrollTop
  const startMin = (ev.startDate.getTime() - startOfDay(ev.startDate).getTime()) / 60_000
  // Bei mehrtägigen Terminen bezieht sich die Spalte auf den angeklickten Abschnitt
  const grabDay = diffDays(props.days[colIdx], ev.startDate)
  const update = (m: PointerEvent) => {
    lastPointer = m
    const dy = m.clientY - y0 + (scroller.value!.scrollTop - scroll0)
    const minutes = Math.round((startMin + dy / PX_PER_MIN) / SNAP) * SNAP
    const day = addDays(startOfDay(ev.startDate), indexAtX(colEls(), m.clientX) - colIdx + grabDay)
    day.setMinutes(Math.max(0, Math.min(minutes, 24 * 60 - SNAP)))
    drag.value = { ev, bar: false, lane: 0, start: day }
  }
  startDrag(e, ev, update)
}
function onBarDown(e: PointerEvent, ev: ViewEvent, lane: number) {
  if (ev.read_only) return
  const idx0 = alldayIndex(e.clientX)
  const update = (m: PointerEvent) => {
    lastPointer = m
    drag.value = { ev, bar: true, lane, start: addDays(ev.startDate, alldayIndex(m.clientX) - idx0) }
  }
  startDrag(e, ev, update)
}
function startDrag(e: PointerEvent, ev: ViewEvent, update: (m: PointerEvent) => void) {
  const end = () => {
    drag.value = null
    lastPointer = null
    onScrollDuringDrag = null
  }
  trackDrag(e, {
    start: () => (onScrollDuringDrag = () => lastPointer && update(lastPointer)),
    move: update,
    drop: () => {
      const d = drag.value
      end()
      if (d && d.start.getTime() !== ev.startDate.getTime()) emit('move', ev, d.start)
    },
    cancel: end,
  })
}

const timedGhost = computed(() => {
  const d = drag.value
  if (!d || d.bar) return null
  const col = props.days.findIndex((day) => isSameDay(day, d.start))
  const top = (d.start.getTime() - startOfDay(d.start).getTime()) / 60_000
  const duration = (d.ev.endDate.getTime() - d.ev.startDate.getTime()) / 60_000
  return {
    col, top, height: Math.max(20, Math.min(duration, 24 * 60 - top)),
    ev: d.ev, start: d.start, end: new Date(d.start.getTime() + duration * 60_000),
  }
})
const barGhost = computed(() => {
  const d = drag.value
  if (!d || !d.bar) return null
  const shift = diffDays(d.start, d.ev.startDate)
  const n = props.days.length
  const from = diffDays(d.ev.startDate, props.days[0]) + shift
  const to = diffDays(lastDay(d.ev), props.days[0]) + 1 + shift
  const start = Math.max(0, from)
  const end = Math.min(n, to)
  if (end <= start) return null
  return { ev: d.ev, lane: d.lane, start, end, contBefore: from < 0, contAfter: to > n }
})

function scrollToStart() {
  if (scroller.value) scroller.value.scrollTop = Math.max(0, props.dayStartHour * HOUR - 8)
}
onMounted(() => {
  scrollToStart()
  timer = window.setInterval(() => (now.value = new Date()), 60_000)
})
onBeforeUnmount(() => clearInterval(timer))
watch(() => props.days.length, () => nextTick(scrollToStart))

const today = new Date()
</script>

<template>
  <div class="tg">
    <!-- Kopfzeile mit Wochentagen -->
    <div class="tg-head">
      <div class="gutter text-label-small kw">
        <template v-if="days.length > 1">KW {{ isoWeek(days[0]) }}</template>
      </div>
      <div v-for="d in days" :key="d.getTime()" class="head-day" :class="{ today: isSameDay(d, today) }">
        <div class="wd text-label-medium">{{ fmt(d, { weekday: 'short' }) }}</div>
        <button class="num" :aria-label="fmt(d, { weekday: 'long', day: 'numeric', month: 'long' })" @click="emit('openDay', d)">
          {{ d.getDate() }}
        </button>
      </div>
    </div>

    <!-- Ganztägige und mehrtägige Termine -->
    <div class="tg-allday">
      <div class="gutter text-label-small allday-label">ganztägig</div>
      <div
        ref="allday"
        class="allday-area"
        :style="{ height: `${Math.max(1, barLanes) * LANE + 6}px`, backgroundSize: `${100 / days.length}% 100%` }"
        @click="onAllDayClick"
      >
        <div
          v-for="it in barItems"
          :key="it.ev.id"
          class="bar"
          :class="{ 'cont-before': it.contBefore, 'cont-after': it.contAfter, dragging: drag?.ev.id === it.ev.id, draft: isDraft(it.ev), done: it.ev.task?.completed }"
          :style="{
            left: `calc(${(it.start / days.length) * 100}% + 2px)`,
            width: `calc(${((it.end - it.start) / days.length) * 100}% - 4px)`,
            top: `${3 + it.lane * LANE}px`,
            background: it.ev.color,
            color: onColor(it.ev.color),
          }"
          role="button"
          tabindex="0"
          @pointerdown="onBarDown($event, it.ev, it.lane)"
          @click.stop="emit('select', it.ev, $event.currentTarget as HTMLElement)"
          @keydown.enter="emit('select', it.ev, $event.currentTarget as HTMLElement)"
        >
          <span
            v-if="it.ev.task" class="task-check" role="button"
            :title="it.ev.task.completed ? 'Als offen markieren' : 'Als erledigt markieren'"
            @click.stop="emit('toggle', it.ev)"
          ><v-icon :icon="taskIcon(it.ev.task)" size="14" /></span>
          <span class="bar-title">{{ it.ev.title }}</span>
        </div>
        <div
          v-if="barGhost"
          class="bar ghost"
          :class="{ 'cont-before': barGhost.contBefore, 'cont-after': barGhost.contAfter }"
          :style="{
            left: `calc(${(barGhost.start / days.length) * 100}% + 2px)`,
            width: `calc(${((barGhost.end - barGhost.start) / days.length) * 100}% - 4px)`,
            top: `${3 + barGhost.lane * LANE}px`,
            background: barGhost.ev.color,
            color: onColor(barGhost.ev.color),
          }"
        >
          {{ barGhost.ev.title }}
        </div>
      </div>
    </div>

    <!-- Zeitraster -->
    <div ref="scroller" class="tg-scroll" @scroll="onScrollDuringDrag?.()">
      <div class="tg-body" :style="{ height: `${24 * HOUR}px` }">
        <div class="gutter hours">
          <div v-for="h in hours" :key="h" class="hour-label text-label-small" :style="{ top: `${h * HOUR}px` }">
            {{ hourLabel(h) }}
          </div>
        </div>
        <div
          v-for="(col, ci) in columns"
          :key="col.day.getTime()"
          class="col"
          :style="{ backgroundSize: `100% ${HOUR}px` }"
          @click="onColumnClick($event, col.day)"
        >
          <div
            v-for="it in col.items"
            :key="it.ev.id"
            class="ev"
            :class="{ short: it.height < 55, 'cont-before': it.contBefore, 'cont-after': it.contAfter, dragging: drag?.ev.id === it.ev.id, draft: isDraft(it.ev), done: it.ev.task?.completed }"
            :style="{
              top: `${it.top * PX_PER_MIN}px`,
              height: `${it.height * PX_PER_MIN - 2}px`,
              left: `calc(${(it.col / it.cols) * 100}% + 1px)`,
              width: `calc(${(1 / it.cols) * 100}% - 4px)`,
              background: it.ev.color,
              color: onColor(it.ev.color),
            }"
            role="button"
            tabindex="0"
            @pointerdown="onTimedDown($event, it.ev, ci)"
            @click.stop="emit('select', it.ev, $event.currentTarget as HTMLElement)"
            @keydown.enter="emit('select', it.ev, $event.currentTarget as HTMLElement)"
          >
            <div class="ev-title">
              <span
                v-if="it.ev.task" class="task-check" role="button"
                :title="it.ev.task.completed ? 'Als offen markieren' : 'Als erledigt markieren'"
                @click.stop="emit('toggle', it.ev)"
              ><v-icon :icon="taskIcon(it.ev.task)" size="14" /></span>
              <span class="ev-name">{{ it.ev.title }}</span><span v-if="it.height < 55" class="ev-time">, {{ fmtTime(it.ev.startDate) }}</span>
            </div>
            <div v-if="it.height >= 55 && !it.ev.task" class="ev-time">
              {{ fmtTime(it.ev.startDate) }} – {{ fmtTime(it.ev.endDate) }}
            </div>
            <div v-if="it.height >= 75 && it.ev.location" class="ev-time ev-loc">{{ it.ev.location }}</div>
          </div>
          <div
            v-if="timedGhost && timedGhost.col === ci"
            class="ev ghost"
            :class="{ short: timedGhost.height < 55 }"
            :style="{
              top: `${timedGhost.top * PX_PER_MIN}px`,
              height: `${timedGhost.height * PX_PER_MIN - 2}px`,
              left: '1px',
              width: 'calc(100% - 4px)',
              background: timedGhost.ev.color,
              color: onColor(timedGhost.ev.color),
            }"
          >
            <div class="ev-title">{{ timedGhost.ev.title }}</div>
            <div class="ev-time">{{ fmtTime(timedGhost.start) }} – {{ fmtTime(timedGhost.end) }}</div>
          </div>
          <div v-if="isSameDay(col.day, now)" class="now" :style="{ top: `${nowTop}px` }" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tg { display: flex; flex-direction: column; height: 100%; }
.gutter { width: 64px; flex: 0 0 64px; }
.tg-head, .tg-allday { display: flex; padding-right: var(--scrollbar, 0px); }
.tg-head { padding-top: 6px; }
.kw { color: rgb(var(--v-theme-outline)); align-self: flex-end; text-align: center; padding-bottom: 4px; }
.head-day { flex: 1; text-align: center; min-width: 0; }
.head-day .wd { color: rgb(var(--v-theme-on-m3-surface-variant)); text-transform: uppercase; }
.head-day .num {
  width: 44px; height: 44px; border-radius: 22px; font-size: 22px; font-weight: 400;
  color: rgb(var(--v-theme-on-surface));
}
.head-day .num:hover { background: rgba(var(--v-theme-on-surface), .08); }
.head-day.today .wd { color: rgb(var(--v-theme-primary)); }
.head-day.today .num { background: rgb(var(--v-theme-primary)); color: rgb(var(--v-theme-on-primary)); }
.tg-allday { border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); }
.allday-label { color: rgb(var(--v-theme-outline)); text-align: right; padding: 6px 8px 0 0; font-size: 10px; }
/* Tagestrennlinien; Vuetifys Reset setzt background-repeat global auf no-repeat */
.allday-area {
  position: relative; flex: 1; cursor: pointer;
  background-image: linear-gradient(to right, rgb(var(--v-theme-outline-variant)) 1px, transparent 1px);
  background-repeat: repeat-x;
}
.bar {
  position: absolute; height: 22px; line-height: 22px; border-radius: 6px; padding: 0 8px;
  font-size: 12px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer;
}
.bar.cont-before { border-top-left-radius: 0; border-bottom-left-radius: 0; }
.bar.cont-after { border-top-right-radius: 0; border-bottom-right-radius: 0; }
.tg-scroll { flex: 1; overflow-y: auto; scrollbar-gutter: stable; }
.tg-head, .tg-allday { scrollbar-gutter: stable; overflow: hidden; }
.tg-body { display: flex; position: relative; }
.hours { position: relative; }
.hour-label {
  position: absolute; right: 8px; transform: translateY(-50%);
  color: rgb(var(--v-theme-outline));
}
.col {
  flex: 1; position: relative; min-width: 0; cursor: pointer;
  border-left: 1px solid rgb(var(--v-theme-outline-variant));
  /* Stundenlinien */
  background-image: linear-gradient(to bottom, rgb(var(--v-theme-outline-variant)) 1px, transparent 1px);
  background-repeat: repeat-y;
}
.col:last-child { border-right: 1px solid rgb(var(--v-theme-outline-variant)); }
.ev {
  position: absolute; border-radius: 8px; padding: 3px 8px; overflow: hidden; font-size: 12px; line-height: 16px;
  cursor: pointer; box-shadow: 0 0 0 1px rgb(var(--v-theme-surface));
  transition: filter .15s;
}
.ev:hover { filter: brightness(1.08); z-index: 2; }
.ev, .bar { user-select: none; }
.ev.dragging, .bar.dragging { opacity: .35; }
/* Vorläufige Karte beim Anlegen */
.ev.draft, .bar.draft { z-index: 3; box-shadow: 0 4px 12px rgba(0, 0, 0, .3); cursor: default; }
.ghost { z-index: 4; pointer-events: none; box-shadow: 0 4px 12px rgba(0, 0, 0, .3); cursor: grabbing; }
.ev:focus-visible { outline: 2px solid rgb(var(--v-theme-primary)); z-index: 2; }
.ev.short { padding-top: 1px; }
.ev.cont-before { border-top-left-radius: 0; border-top-right-radius: 0; }
.ev.cont-after { border-bottom-left-radius: 0; border-bottom-right-radius: 0; }
.ev-title { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* Aufgaben: Abhak-Symbol vor dem Titel, erledigte durchgestrichen */
.task-check { display: inline-flex; vertical-align: -2px; margin-right: 4px; border-radius: 50%; }
.task-check:hover { background: rgba(0, 0, 0, .15); }
.ev.done, .bar.done { opacity: .6; }
.ev.done .ev-name, .bar.done .bar-title { text-decoration: line-through; }
.ev-time { opacity: .9; font-weight: 400; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ev-loc { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
@media (max-width: 600px) {
  .gutter { width: 44px; flex-basis: 44px; }
  .ev { padding: 2px 3px; border-radius: 6px; font-size: 11px; }
  .bar { padding: 0 4px; }
  .head-day .num { width: 36px; height: 36px; font-size: 18px; }
  .allday-label { font-size: 9px; padding-right: 4px; }
}
.now { position: absolute; left: -1px; right: 0; height: 2px; background: rgb(var(--v-theme-error)); z-index: 3; pointer-events: none; }
.now::before {
  content: ''; position: absolute; left: -6px; top: -5px; width: 12px; height: 12px; border-radius: 6px;
  background: rgb(var(--v-theme-error));
}
</style>
