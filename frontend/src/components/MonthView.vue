<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ViewEvent } from '@/api/types'
import { addDays, diffDays, fmt, fmtTime, isoWeek, isSameDay, startOfDay, weekdayNames } from '@/utils/date'
import { clampRow, isBarEvent, lastDay, layoutRow } from '@/utils/layout'
import { indexAtY, trackDrag } from '@/utils/drag'
import { onColor } from '@/plugins/theme'

const props = defineProps<{
  events: ViewEvent[]
  start: Date
  end: Date
  month: number
  weekStartsOn: number
  showWeekNumbers: boolean
}>()
const emit = defineEmits<{
  select: [ev: ViewEvent, el: HTMLElement]
  create: [day: Date]
  openDay: [day: Date]
  move: [ev: ViewEvent, newStart: Date]
}>()

const HEAD = 30
const LANE = 24
const body = ref<HTMLElement>()
const rowHeight = ref(120)
let observer: ResizeObserver | undefined

const weeks = computed(() => {
  const n = Math.round((props.end.getTime() - props.start.getTime()) / (7 * 86_400_000))
  return Array.from({ length: n }, (_, w) => addDays(props.start, w * 7))
})
const maxLanes = computed(() => Math.max(1, Math.floor((rowHeight.value - HEAD - 4) / LANE)))

const rows = computed(() =>
  weeks.value.map((weekStart) => {
    const days = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i))
    const { visible, more } = clampRow(layoutRow(props.events, weekStart, 7), 7, maxLanes.value)
    return { weekStart, days, items: visible, more }
  }),
)
const names = computed(() => weekdayNames(props.weekStartsOn))
const today = new Date()

onMounted(() => {
  observer = new ResizeObserver(() => {
    if (body.value && weeks.value.length) rowHeight.value = body.value.clientHeight / weeks.value.length
  })
  if (body.value) observer.observe(body.value)
})
onBeforeUnmount(() => observer?.disconnect())

// --- Drag & Drop: Termin auf einen anderen Tag ziehen, Uhrzeit bleibt erhalten ---
const drag = ref<{ ev: ViewEvent; start: Date } | null>(null)

/** Tag (Index ab Rasterbeginn) unter dem Zeiger */
function dayIndexAt(x: number, y: number) {
  const rows = Array.from(body.value?.querySelectorAll<HTMLElement>('.week-inner') ?? [])
  const row = indexAtY(rows, y)
  const r = rows[row].getBoundingClientRect()
  const col = Math.max(0, Math.min(6, Math.floor(((x - r.left) / r.width) * 7)))
  return row * 7 + col
}
function onItemDown(e: PointerEvent, ev: ViewEvent) {
  const idx0 = dayIndexAt(e.clientX, e.clientY)
  trackDrag(e, {
    move: (m) => (drag.value = { ev, start: addDays(ev.startDate, dayIndexAt(m.clientX, m.clientY) - idx0) }),
    drop: () => {
      const d = drag.value
      drag.value = null
      if (d && d.start.getTime() !== ev.startDate.getTime()) emit('move', ev, d.start)
    },
    cancel: () => (drag.value = null),
  })
}
/** Tage, die der Termin an der Zielposition belegen würde */
const dropDays = computed(() => {
  const d = drag.value
  if (!d) return new Set<number>()
  const first = startOfDay(d.start)
  const n = Math.max(1, diffDays(lastDay(d.ev), d.ev.startDate) + 1)
  return new Set(Array.from({ length: n }, (_, i) => addDays(first, i).getTime()))
})

function dayLabel(d: Date) {
  return d.getDate() === 1 ? fmt(d, { day: 'numeric', month: 'short' }) : String(d.getDate())
}
function barStyle(ev: ViewEvent) {
  return isBarEvent(ev) || !isSameDay(ev.startDate, ev.endDate)
}
</script>

<template>
  <div class="month" :class="{ 'with-wk': showWeekNumbers }">
    <div class="month-head">
      <div v-if="showWeekNumbers" class="wk-col" />
      <div v-for="n in names" :key="n" class="head-cell text-label-medium">{{ n }}</div>
    </div>
    <div ref="body" class="month-body">
      <div v-for="row in rows" :key="row.weekStart.getTime()" class="week">
        <div v-if="showWeekNumbers" class="wk-col wk text-label-small">{{ isoWeek(row.weekStart) }}</div>
        <div class="week-inner">
          <div class="cells">
            <div
              v-for="d in row.days"
              :key="d.getTime()"
              class="cell"
              :class="{ other: d.getMonth() !== month, 'drop-target': dropDays.has(d.getTime()) }"
              @click="emit('create', d)"
            >
              <button
                class="daynum text-label-medium"
                :class="{ today: isSameDay(d, today) }"
                :aria-label="fmt(d, { weekday: 'long', day: 'numeric', month: 'long' })"
                @click.stop="emit('openDay', d)"
              >
                {{ dayLabel(d) }}
              </button>
            </div>
          </div>
          <div
            v-for="it in row.items"
            :key="it.ev.id"
            class="item"
            :class="{ bar: barStyle(it.ev), 'cont-before': it.contBefore, 'cont-after': it.contAfter, dragging: drag?.ev.id === it.ev.id }"
            :style="{
              left: `calc(${(it.start / 7) * 100}% + 3px)`,
              width: `calc(${((it.end - it.start) / 7) * 100}% - 6px)`,
              top: `${HEAD + it.lane * LANE}px`,
              ...(barStyle(it.ev) ? { background: it.ev.color, color: onColor(it.ev.color) } : {}),
            }"
            role="button"
            tabindex="0"
            :title="it.ev.title"
            @pointerdown="onItemDown($event, it.ev)"
            @click.stop="emit('select', it.ev, $event.currentTarget as HTMLElement)"
            @keydown.enter="emit('select', it.ev, $event.currentTarget as HTMLElement)"
          >
            <template v-if="barStyle(it.ev)">
              <span v-if="!it.ev.all_day && !it.contBefore" class="time">{{ fmtTime(it.ev.startDate) }}</span>
              {{ it.ev.title }}
            </template>
            <template v-else>
              <span class="dot" :style="{ background: it.ev.color }" />
              <span class="time">{{ fmtTime(it.ev.startDate) }}</span>
              <span class="title">{{ it.ev.title }}</span>
            </template>
          </div>
          <template v-for="(count, i) in row.more" :key="i">
            <button
              v-if="count > 0"
              class="more text-label-small"
              :style="{ left: `calc(${(i / 7) * 100}% + 3px)`, width: `calc(${100 / 7}% - 6px)`, top: `${HEAD + (maxLanes - 1) * LANE}px` }"
              @click.stop="emit('openDay', row.days[i])"
            >
              +{{ count }} weitere
            </button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.month { display: flex; flex-direction: column; height: 100%; }
.month-head { display: flex; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); }
.head-cell {
  flex: 1; text-align: center; padding: 6px 0; text-transform: uppercase;
  color: rgb(var(--v-theme-on-m3-surface-variant));
}
.wk-col { width: 32px; flex: 0 0 32px; }
.wk {
  color: rgb(var(--v-theme-outline)); text-align: center; padding-top: 8px;
  border-bottom: 1px solid rgb(var(--v-theme-outline-variant));
}
.month-body { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.week { flex: 1; display: flex; min-height: 72px; }
.week-inner { position: relative; flex: 1; overflow: hidden; }
.cells { display: flex; height: 100%; }
.cell {
  flex: 1; border-right: 1px solid rgb(var(--v-theme-outline-variant));
  border-bottom: 1px solid rgb(var(--v-theme-outline-variant));
  text-align: center; cursor: pointer; padding-top: 3px; min-width: 0;
  transition: background-color .15s;
}
.cell:hover { background: rgba(var(--v-theme-on-surface), .04); }
.cell:last-child { border-right: none; }
.cell.other { background: rgba(var(--v-theme-on-surface), .025); }
.cell.other .daynum { color: rgb(var(--v-theme-outline)); }
.daynum {
  min-width: 24px; height: 24px; border-radius: 12px; padding: 0 6px;
  color: rgb(var(--v-theme-on-surface));
}
.daynum:hover { background: rgba(var(--v-theme-on-surface), .08); }
.daynum.today { background: rgb(var(--v-theme-primary)); color: rgb(var(--v-theme-on-primary)); }
.item {
  position: absolute; height: 22px; line-height: 22px; font-size: 12px; padding: 0 6px;
  border-radius: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer;
  display: flex; align-items: center; gap: 4px; color: rgb(var(--v-theme-on-surface));
}
.item:hover { background: rgba(var(--v-theme-on-surface), .08); }
.item:focus-visible { outline: 2px solid rgb(var(--v-theme-primary)); }
.item { user-select: none; }
.item.dragging { opacity: .35; }
.cell.drop-target, .cell.drop-target:hover {
  background: rgba(var(--v-theme-primary), .14);
  box-shadow: inset 0 0 0 2px rgba(var(--v-theme-primary), .5);
}
.item.bar { font-weight: 500; display: block; }
.item.bar:hover { filter: brightness(1.08); }
.item.cont-before { border-top-left-radius: 0; border-bottom-left-radius: 0; }
.item.cont-after { border-top-right-radius: 0; border-bottom-right-radius: 0; }
.item .time { opacity: .85; margin-right: 2px; }
.item .title { overflow: hidden; text-overflow: ellipsis; }
.dot { width: 8px; height: 8px; border-radius: 4px; flex: 0 0 8px; }
@media (max-width: 600px) {
  .item { padding: 0 3px; font-size: 11px; }
  .item:not(.bar) .time { display: none; }
  .dot { width: 6px; height: 6px; flex-basis: 6px; }
}
.more {
  position: absolute; height: 22px; border-radius: 6px; text-align: left; padding: 0 6px;
  color: rgb(var(--v-theme-on-m3-surface-variant));
}
.more:hover { background: rgba(var(--v-theme-on-surface), .08); }
</style>
