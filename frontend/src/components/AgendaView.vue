<script setup lang="ts">
import { t } from '@/i18n'
import { computed } from 'vue'
import type { ViewEvent } from '@/api/types'
import { addDays, fmt, fmtTime, isSameDay, startOfDay } from '@/utils/date'
import { taskIcon } from '@/stores/tasks'

const props = defineProps<{ events: ViewEvent[]; start: Date; days: number; search: string }>()
const emit = defineEmits<{ select: [ev: ViewEvent, el: HTMLElement]; openDay: [day: Date]; toggle: [ev: ViewEvent] }>()

const filtered = computed(() => {
  const q = props.search.trim().toLowerCase()
  if (!q) return props.events
  return props.events.filter((e) => `${e.title}\n${e.location}\n${e.description}`.toLowerCase().includes(q))
})

const groups = computed(() => {
  const out: { day: Date; items: ViewEvent[] }[] = []
  for (let i = 0; i < props.days; i++) {
    const day = addDays(props.start, i)
    const next = addDays(day, 1)
    const items = filtered.value.filter((e) =>
      e.startDate < next && (e.endDate > day || (e.endDate.getTime() === e.startDate.getTime() && e.startDate >= day)),
    )
    if (items.length) out.push({ day, items })
  }
  return out
})

function timeText(e: ViewEvent, day: Date) {
  if (e.task) return e.all_day ? t('task.task') : fmtTime(e.startDate)
  if (e.all_day) return t('event.allDay')
  const startsToday = isSameDay(e.startDate, day)
  const endsToday = e.endDate <= addDays(startOfDay(day), 1)
  if (startsToday && endsToday) return `${fmtTime(e.startDate)} – ${fmtTime(e.endDate)}`
  if (startsToday) return t('event.from', { time: fmtTime(e.startDate) })
  if (endsToday) return t('event.until', { time: fmtTime(e.endDate) })
  return t('event.allDay')
}
const today = new Date()
</script>

<template>
  <div class="agenda">
    <div v-if="!groups.length" class="empty">
      <v-icon icon="mdi-calendar-blank-outline" size="64" color="outline" />
      <div class="text-body-large mt-4">{{ search ? $t('agenda.noMatches') : $t('agenda.empty') }}</div>
    </div>
    <div v-for="g in groups" :key="g.day.getTime()" class="group">
      <button class="day" :class="{ today: isSameDay(g.day, today) }" @click="emit('openDay', g.day)">
        <span class="num">{{ g.day.getDate() }}</span>
        <span class="text-label-medium meta">
          <span>{{ fmt(g.day, { weekday: 'short' }) }}</span>
          <span>{{ fmt(g.day, { month: 'short' }) }}</span>
        </span>
      </button>
      <div class="items">
        <div
          v-for="e in g.items"
          :key="e.id"
          class="row"
          role="button"
          tabindex="0"
          @click="emit('select', e, $event.currentTarget as HTMLElement)"
          @keydown.enter="emit('select', e, $event.currentTarget as HTMLElement)"
        >
          <span
            v-if="e.task" class="task-check" role="button" :style="{ color: e.color }"
            :title="e.task.completed ? $t('task.markOpen') : $t('task.markDone')"
            @click.stop="emit('toggle', e)"
          ><v-icon :icon="taskIcon(e.task)" size="18" /></span>
          <span v-else class="dot" :style="{ background: e.color }" />
          <span class="time text-body-medium">{{ timeText(e, g.day) }}</span>
          <span class="title text-body-large" :class="{ done: e.task?.completed }">
            {{ e.title }}
            <v-icon v-if="e.recurring" icon="mdi-repeat" size="14" class="ml-1 text-medium-emphasis" />
            <span v-if="e.location" class="loc text-body-medium"> · {{ e.location }}</span>
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.agenda { height: 100%; overflow-y: auto; padding: 8px 16px 96px; max-width: 960px; }
.empty { display: flex; flex-direction: column; align-items: center; padding-top: 15vh; color: rgb(var(--v-theme-on-m3-surface-variant)); }
.group { display: flex; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); padding: 8px 0; }
.day {
  width: 96px; flex: 0 0 96px; display: flex; align-items: center; gap: 8px; align-self: flex-start;
  border-radius: 20px; padding: 2px 4px; text-align: left;
}
.day .num {
  width: 36px; height: 36px; border-radius: 18px; display: inline-flex; align-items: center; justify-content: center;
  font-size: 20px; color: rgb(var(--v-theme-on-surface));
}
.day .meta { display: flex; flex-direction: column; line-height: 14px; text-transform: uppercase; color: rgb(var(--v-theme-on-m3-surface-variant)); }
.day.today .num { background: rgb(var(--v-theme-primary)); color: rgb(var(--v-theme-on-primary)); }
.day.today .meta { color: rgb(var(--v-theme-primary)); }
.day:hover .num { box-shadow: 0 0 0 2px rgba(var(--v-theme-primary), .3); }
.items { flex: 1; min-width: 0; }
.row {
  display: flex; align-items: center; gap: 16px; padding: 8px 12px; border-radius: 12px; cursor: pointer;
}
.row:hover { background: rgba(var(--v-theme-on-surface), .06); }
.row:focus-visible { outline: 2px solid rgb(var(--v-theme-primary)); }
.dot { width: 12px; height: 12px; border-radius: 6px; flex: 0 0 12px; }
.task-check { display: inline-flex; flex: 0 0 12px; justify-content: center; width: 12px; border-radius: 50%; }
.task-check:hover { background: rgba(var(--v-theme-on-surface), .08); }
.title.done { text-decoration: line-through; opacity: .6; }
.time { width: 120px; flex: 0 0 120px; color: rgb(var(--v-theme-on-m3-surface-variant)); }
.title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.loc { color: rgb(var(--v-theme-on-m3-surface-variant)); }
@media (max-width: 600px) {
  .day { width: 56px; flex-basis: 56px; flex-direction: column; gap: 0; }
  .day .meta { flex-direction: row; gap: 3px; }
  .time { width: 84px; flex-basis: 84px; font-size: 13px; }
  .row { gap: 10px; padding: 8px; }
}
</style>
