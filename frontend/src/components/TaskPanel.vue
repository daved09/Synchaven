<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Task } from '@/api/types'
import { useNotifyStore } from '@/stores/notify'
import { taskDueDate, useTasksStore } from '@/stores/tasks'
import { addDays, fmt, fmtTime, isSameDay, startOfDay } from '@/utils/date'

/** Selected list (shared with the calendar, e.g. for "New task") */
const listId = defineModel<string>('listId', { required: true })
const emit = defineEmits<{ open: [task: Task]; close: [] }>()
const tasks = useTasksStore()
const notify = useNotifyStore()

const quickTitle = ref('')
const adding = ref(false)
const showCompleted = ref(false)
/** Parent task under which a subtask is currently being entered */
const subFor = ref<string | null>(null)
const subTitle = ref('')

watch(() => tasks.lists, (l) => {
  if (l.length && !l.some((x) => x.id === listId.value)) listId.value = l[0].id
}, { immediate: true })

const list = computed(() => tasks.listById.get(listId.value))
const open = computed(() => tasks.openOf(listId.value))
const completed = computed(() => tasks.completedOf(listId.value))
const listItems = computed(() => tasks.lists.map((l) => ({ title: l.name, value: l.id, color: l.color })))

function dueText(t: Task) {
  const d = taskDueDate(t)
  if (!d) return ''
  const today = startOfDay(new Date())
  const day = isSameDay(d, today) ? 'Heute'
    : isSameDay(d, addDays(today, 1)) ? 'Morgen'
    : isSameDay(d, addDays(today, -1)) ? 'Gestern'
    : fmt(d, { weekday: 'short', day: 'numeric', month: 'short', ...(d.getFullYear() !== today.getFullYear() ? { year: 'numeric' } : {}) })
  return t.all_day ? day : `${day}, ${fmtTime(d)}`
}
function overdue(t: Task) {
  const d = taskDueDate(t)
  if (!d || t.completed) return false
  return t.all_day ? d < startOfDay(new Date()) : d < new Date()
}

async function quickAdd() {
  const title = quickTitle.value.trim()
  if (!title || !listId.value) return
  adding.value = true
  try {
    await tasks.create({ list_id: listId.value, title, notes: '', due: null, all_day: true })
    quickTitle.value = ''
  } catch (e) {
    notify.fail(e)
  } finally {
    adding.value = false
  }
}
function startSubtask(t: Task) {
  subFor.value = t.id
  subTitle.value = ''
}
async function addSubtask(parent: Task) {
  const title = subTitle.value.trim()
  if (!title) {
    subFor.value = null
    return
  }
  adding.value = true
  try {
    await tasks.createSubtask(parent, title)
    subTitle.value = '' // field stays open for the next subtask
  } catch (e) {
    notify.fail(e)
  } finally {
    adding.value = false
  }
}
function parentTitle(t: Task) {
  return t.parent_id ? tasks.tasks.find((x) => x.id === t.parent_id)?.title : undefined
}
async function toggle(t: Task) {
  try {
    await tasks.setCompleted(t, !t.completed)
  } catch (e) {
    notify.fail(e)
  }
}
async function clearCompleted() {
  try {
    const n = await tasks.clearCompleted(listId.value)
    notify.info(n === 1 ? '1 erledigte Aufgabe gelöscht' : `${n} erledigte Aufgaben gelöscht`)
  } catch (e) {
    notify.fail(e)
  }
}
</script>

<template>
  <div class="panel">
    <div class="d-flex align-center pl-4 pr-1 pt-2">
      <span class="text-title-medium flex-1-1">Aufgaben</span>
      <v-menu location="bottom end">
        <template #activator="{ props: p }">
          <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" title="Weitere Aktionen" />
        </template>
        <v-list density="compact" rounded="lg">
          <v-list-item
            prepend-icon="mdi-delete-sweep-outline" title="Erledigte Aufgaben löschen" :disabled="!completed.length"
            @click="clearCompleted"
          />
        </v-list>
      </v-menu>
      <v-btn icon="mdi-close" size="small" variant="text" title="Schließen" @click="emit('close')" />
    </div>
    <div class="px-4 pt-2">
      <v-select
        v-model="listId" :items="listItems" aria-label="Liste" density="compact" variant="outlined" hide-details
        class="mb-3"
      >
        <template #item="{ props: p, item }">
          <v-list-item v-bind="p">
            <template #prepend><span class="dot mr-3" :style="{ background: item.raw.color }" /></template>
          </v-list-item>
        </template>
        <template #selection="{ item }">
          <span class="dot mr-2" :style="{ background: item.raw.color }" />{{ item.title }}
        </template>
      </v-select>
      <v-text-field
        v-model="quickTitle" placeholder="Aufgabe hinzufügen" aria-label="Neue Aufgabe" density="compact"
        variant="solo-filled" flat rounded="lg" hide-details prepend-inner-icon="mdi-plus" maxlength="500"
        bg-color="surface-container-high" :loading="adding" @keydown.enter="quickAdd"
      />
    </div>

    <div class="items">
      <div v-if="!open.length" class="empty text-body-medium">
        <v-icon icon="mdi-check-all" size="40" color="outline" />
        <div class="mt-2">Keine offenen Aufgaben</div>
      </div>
      <template v-for="t in open" :key="t.id">
        <div class="task" role="button" tabindex="0" @click="emit('open', t)" @keydown.enter="emit('open', t)">
          <v-btn
            icon size="small" variant="text" class="check" title="Als erledigt markieren" :color="list?.color"
            @click.stop="toggle(t)"
          >
            <v-icon icon="mdi-checkbox-blank-circle-outline" class="off" />
            <v-icon icon="mdi-check-circle-outline" class="on" />
          </v-btn>
          <div class="body">
            <div class="text-body-large name">{{ t.title }}</div>
            <div v-if="t.notes" class="text-body-small text-medium-emphasis notes">{{ t.notes }}</div>
            <v-chip
              v-if="t.due" size="x-small" variant="outlined" class="mt-1" :color="overdue(t) ? 'error' : undefined"
              prepend-icon="mdi-calendar-outline"
            >
              {{ dueText(t) }}
            </v-chip>
          </div>
          <v-btn
            v-if="!t.parent_id" icon="mdi-subdirectory-arrow-right" size="small" variant="text" class="add-sub"
            title="Unteraufgabe hinzufügen" @click.stop="startSubtask(t)"
          />
        </div>
        <template v-if="!t.parent_id">
          <div
            v-for="s in tasks.subtasksOf(t.id).filter((x) => !x.completed)" :key="s.id" class="task sub" role="button"
            tabindex="0" @click="emit('open', s)" @keydown.enter="emit('open', s)"
          >
            <v-btn
              icon size="small" variant="text" class="check" title="Als erledigt markieren" :color="list?.color"
              @click.stop="toggle(s)"
            >
              <v-icon icon="mdi-checkbox-blank-circle-outline" class="off" />
              <v-icon icon="mdi-check-circle-outline" class="on" />
            </v-btn>
            <div class="body">
              <div class="text-body-medium name">{{ s.title }}</div>
              <v-chip
                v-if="s.due" size="x-small" variant="outlined" class="mt-1" :color="overdue(s) ? 'error' : undefined"
                prepend-icon="mdi-calendar-outline"
              >
                {{ dueText(s) }}
              </v-chip>
            </div>
          </div>
          <div v-if="subFor === t.id" class="sub-input">
            <v-text-field
              v-model="subTitle" placeholder="Unteraufgabe hinzufügen" aria-label="Neue Unteraufgabe" density="compact"
              variant="underlined" hide-details autofocus maxlength="500" :loading="adding"
              @keydown.enter="addSubtask(t)" @keydown.esc="subFor = null" @blur="!subTitle.trim() && (subFor = null)"
            />
          </div>
        </template>
      </template>

      <template v-if="completed.length">
        <button class="completed-head text-title-small" @click="showCompleted = !showCompleted">
          <v-icon :icon="showCompleted ? 'mdi-chevron-down' : 'mdi-chevron-right'" size="20" />
          Erledigt ({{ completed.length }})
        </button>
        <template v-if="showCompleted">
          <div
            v-for="t in completed" :key="t.id" class="task done" role="button" tabindex="0"
            @click="emit('open', t)" @keydown.enter="emit('open', t)"
          >
            <v-btn
              icon="mdi-check" size="small" variant="text" class="check" title="Als offen markieren" :color="list?.color"
              @click.stop="toggle(t)"
            />
            <div class="body">
              <div class="text-body-large name">{{ t.title }}</div>
              <div v-if="parentTitle(t)" class="text-body-small text-medium-emphasis notes">
                <v-icon icon="mdi-subdirectory-arrow-right" size="14" /> {{ parentTitle(t) }}
              </div>
              <div v-if="t.due" class="text-body-small text-medium-emphasis">{{ dueText(t) }}</div>
            </div>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.panel { display: flex; flex-direction: column; height: 100%; }
.items { flex: 1; overflow-y: auto; padding: 8px 8px 24px; }
.empty { display: flex; flex-direction: column; align-items: center; padding: 32px 0; color: rgb(var(--v-theme-on-m3-surface-variant)); }
.dot { display: inline-block; width: 12px; height: 12px; border-radius: 6px; flex: 0 0 12px; }
.task { display: flex; align-items: flex-start; gap: 4px; padding: 4px 8px 6px 0; border-radius: 12px; cursor: pointer; }
.task:hover { background: rgba(var(--v-theme-on-surface), .06); }
.task:focus-visible { outline: 2px solid rgb(var(--v-theme-primary)); }
.body { flex: 1; min-width: 0; padding-top: 6px; }
.name { word-break: break-word; line-height: 20px; }
.notes { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.task.done .name { text-decoration: line-through; opacity: .6; }
.task.sub { margin-left: 32px; padding-top: 0; padding-bottom: 2px; }
.task.sub .body { padding-top: 8px; }
.sub-input { margin: 0 8px 4px 72px; }
.add-sub { opacity: 0; transition: opacity .15s; }
.task:hover .add-sub, .task:focus-within .add-sub { opacity: 1; }
@media (hover: none) { .add-sub { opacity: .7; } }
.check .on { display: none; }
.check:hover .on { display: inline-flex; }
.check:hover .off { display: none; }
.completed-head {
  display: flex; align-items: center; gap: 4px; width: 100%; padding: 12px 8px 8px; text-align: left;
  color: rgb(var(--v-theme-on-m3-surface-variant));
}
</style>
