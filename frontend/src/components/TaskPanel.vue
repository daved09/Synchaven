<script setup lang="ts">
import { t as tr } from '@/i18n'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { Task, TaskSortBy } from '@/api/types'
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
const manual = computed(() => list.value?.sort_by !== 'due')
const open = computed(() => inPreview(TOP, tasks.openOf(listId.value)))
const completed = computed(() => tasks.completedOf(listId.value))
const listItems = computed(() => tasks.lists.map((l) => ({ title: l.name, value: l.id, color: l.color })))

function dueText(t: Task) {
  const d = taskDueDate(t)
  if (!d) return ''
  const today = startOfDay(new Date())
  const day = isSameDay(d, today) ? tr('common.today')
    : isSameDay(d, addDays(today, 1)) ? tr('common.tomorrow')
    : isSameDay(d, addDays(today, -1)) ? tr('common.yesterday')
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
async function setSortBy(sortBy: TaskSortBy) {
  if (!list.value || list.value.sort_by === sortBy) return
  try {
    await tasks.setSortBy(list.value, sortBy)
  } catch (e) {
    notify.fail(e)
  }
}

// --- Own order: drag a task (touch: hold it first), or Alt+↑/↓ on a focused task ---
/** Group of the top-level tasks; subtasks are grouped by their parent task's ID */
const TOP = ''
/** Ongoing drag: the group's tasks in their previewed order */
const drag = ref<{ task: Task; group: string; order: string[]; moved: boolean } | null>(null)
const itemsEl = ref<HTMLElement>()

function inPreview(group: string, items: Task[]) {
  const d = drag.value
  if (!d || d.group !== group) return items
  const byId = new Map(items.map((t) => [t.id, t]))
  return d.order.map((id) => byId.get(id)).filter((t): t is Task => !!t)
}
function openSubtasks(parent: Task) {
  return inPreview(parent.id, tasks.subtasksOf(parent.id).filter((x) => !x.completed))
}
/** Can be moved by hand: subtasks always (they have no date order), top-level tasks in own order. Open subtasks
 *  listed at the top level because their parent task is completed stay where they are. */
function movable(t: Task, group: string) {
  return group === TOP ? manual.value && !t.parent_id : t.parent_id === group
}
function groupItems(group: string) {
  return group === TOP ? tasks.openOf(listId.value) : tasks.subtasksOf(group).filter((x) => !x.completed)
}
/** Saves the new place: behind the nearest real sibling above it (completed or foreign ones don't count) */
async function place(t: Task, order: Task[]) {
  const i = order.findIndex((x) => x.id === t.id)
  const after = order.slice(0, i).reverse().find((x) => x.parent_id === t.parent_id)?.id ?? null
  try {
    await tasks.reorder(t, after)
  } catch (e) {
    notify.fail(e)
  }
}

/** Mouse and pen: the drag starts as soon as the pressed pointer moves a few pixels. Touch: a moving finger
 *  scrolls the list, so the task must be held still for a moment first. */
const DRAG_SLOP = 6
const HOLD_MS = 350
let press: { task: Task; group: string; x: number; y: number; touch: boolean; timer?: number } | null = null
/** The click that ends a drag must not open the task */
let dragEnded = false

function startPress(t: Task, group: string, e: PointerEvent) {
  if (!movable(t, group) || e.button !== 0 || (e.target as Element).closest('button, input')) return
  endPress()
  const touch = e.pointerType === 'touch'
  press = { task: t, group, x: e.clientX, y: e.clientY, touch }
  if (touch) press.timer = window.setTimeout(beginDrag, HOLD_MS)
  // On the window: reordering moves the element in the DOM, which would end a pointer capture
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerCancel)
}
function endPress() {
  if (press?.timer) clearTimeout(press.timer)
  press = null
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerCancel)
}
function beginDrag() {
  if (!press) return
  const { task, group, touch } = press
  drag.value = { task, group, order: groupItems(group).map((x) => x.id), moved: false }
  if (touch) navigator.vibrate?.(20)
}
function onPointerMove(e: PointerEvent) {
  if (press && !drag.value && Math.hypot(e.clientX - press.x, e.clientY - press.y) > DRAG_SLOP) {
    // A finger that moves before the time is up scrolls the list
    if (press.touch) return endPress()
    beginDrag()
  }
  if (drag.value) onDrag(e)
}
function onPointerUp() {
  void endDrag()
}
function onPointerCancel() {
  void endDrag(true)
}
function onDrag(e: PointerEvent) {
  const d = drag.value
  const box = itemsEl.value
  if (!d || !box) return
  // Scroll along near the edges of the list
  const r = box.getBoundingClientRect()
  if (e.clientY < r.top + 32) box.scrollTop -= 12
  else if (e.clientY > r.bottom - 32) box.scrollTop += 12
  // New place: behind all others whose middle lies above the pointer. Measured by the layout (offsetTop),
  // not the screen position, so the items sliding into place don't make the order flip back and forth.
  const others = [...box.querySelectorAll<HTMLElement>(`[data-group="${d.group}"]`)].filter((el) => el.dataset.id !== d.task.id)
  const y = e.clientY - r.top + box.scrollTop
  const index = others.filter((el) => el.offsetTop + el.offsetHeight / 2 < y).length
  const order = others.map((el) => el.dataset.id!)
  order.splice(index, 0, d.task.id)
  if (order.join() !== d.order.join()) {
    d.order = order
    d.moved = true
  }
}
async function endDrag(cancel = false) {
  endPress()
  const d = drag.value
  if (!d) return
  const order = inPreview(d.group, groupItems(d.group))
  drag.value = null
  dragEnded = true
  setTimeout(() => (dragEnded = false))
  if (d.moved && !cancel) await place(d.task, order)
}
onBeforeUnmount(endPress)

function openTask(t: Task) {
  if (!dragEnded) emit('open', t)
}
/** Alt+↑/↓: one place up or down */
async function moveBy(t: Task, group: string, step: number) {
  if (!movable(t, group)) return
  const order = groupItems(group)
  const i = order.findIndex((x) => x.id === t.id)
  const j = i + step
  if (i < 0 || j < 0 || j >= order.length) return
  order.splice(i, 1)
  order.splice(j, 0, t)
  await place(t, order)
}

async function clearCompleted() {
  try {
    const n = await tasks.clearCompleted(listId.value)
    notify.info(tr('tasks.completedDeleted', n))
  } catch (e) {
    notify.fail(e)
  }
}
</script>

<template>
  <div class="panel">
    <div class="d-flex align-center pl-4 pr-1 pt-2">
      <span class="text-title-medium flex-1-1">{{ $t('tasks.title') }}</span>
      <v-menu location="bottom end">
        <template #activator="{ props: p }">
          <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" :title="$t('common.moreActions')" />
        </template>
        <v-list density="compact" rounded="lg">
          <v-list-subheader>{{ $t('tasks.sortBy') }}</v-list-subheader>
          <v-list-item
            :title="$t('tasks.sortManual')" :active="manual" :prepend-icon="manual ? 'mdi-check' : 'mdi-blank'"
            @click="setSortBy('manual')"
          />
          <v-list-item
            :title="$t('tasks.sortDue')" :active="!manual" :prepend-icon="manual ? 'mdi-blank' : 'mdi-check'"
            @click="setSortBy('due')"
          />
          <v-divider class="my-1" />
          <v-list-item
            prepend-icon="mdi-delete-sweep-outline" :title="$t('tasks.deleteCompleted')" :disabled="!completed.length"
            @click="clearCompleted"
          />
        </v-list>
      </v-menu>
      <v-btn icon="mdi-close" size="small" variant="text" :title="$t('common.close')" @click="emit('close')" />
    </div>
    <div class="px-4 pt-2">
      <v-select
        v-model="listId" :items="listItems" :aria-label="$t('task.list')" density="compact" variant="outlined" hide-details
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
        v-model="quickTitle" :placeholder="$t('tasks.add')" :aria-label="$t('task.new')" density="compact"
        variant="solo-filled" flat rounded="lg" hide-details prepend-inner-icon="mdi-plus" maxlength="500"
        bg-color="surface-container-high" :loading="adding" @keydown.enter="quickAdd"
      />
    </div>

    <div ref="itemsEl" class="items" :class="{ sorting: drag }">
      <div v-if="!open.length" class="empty text-body-medium">
        <v-icon icon="mdi-check-all" size="40" color="outline" />
        <div class="mt-2">{{ $t('tasks.noneOpen') }}</div>
      </div>
      <TransitionGroup name="reorder" tag="div">
        <div
          v-for="t in open" :key="t.id" class="block" :class="{ dragging: drag?.task.id === t.id }" :data-group="TOP"
          :data-id="t.id"
        >
          <div
            class="task" role="button" tabindex="0" :aria-keyshortcuts="movable(t, TOP) ? 'Alt+ArrowUp Alt+ArrowDown' : undefined"
            @click="openTask(t)" @keydown.enter.self="emit('open', t)"
            @keydown.alt.up.prevent.self="moveBy(t, TOP, -1)" @keydown.alt.down.prevent.self="moveBy(t, TOP, 1)"
            @pointerdown="startPress(t, TOP, $event)"
            @touchmove="drag && $event.preventDefault()" @contextmenu="drag && $event.preventDefault()"
          >
            <v-btn
              icon size="small" variant="text" class="check" :title="$t('task.markDone')" :color="list?.color"
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
              :title="$t('task.addSubtask')" @click.stop="startSubtask(t)"
            />
          </div>
          <template v-if="!t.parent_id">
            <TransitionGroup name="reorder" tag="div">
              <div
                v-for="s in openSubtasks(t)" :key="s.id" class="task sub" :class="{ dragging: drag?.task.id === s.id }"
                role="button" tabindex="0" :data-group="t.id" :data-id="s.id" aria-keyshortcuts="Alt+ArrowUp Alt+ArrowDown"
                @click="openTask(s)" @keydown.enter.self="emit('open', s)" @keydown.alt.up.prevent.self="moveBy(s, t.id, -1)"
                @keydown.alt.down.prevent.self="moveBy(s, t.id, 1)" @pointerdown="startPress(s, t.id, $event)"
                @touchmove="drag && $event.preventDefault()" @contextmenu="drag && $event.preventDefault()"
              >
                <v-btn
                  icon size="small" variant="text" class="check" :title="$t('task.markDone')" :color="list?.color"
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
            </TransitionGroup>
            <div v-if="subFor === t.id" class="sub-input">
              <v-text-field
                v-model="subTitle" :placeholder="$t('task.addSubtask')" :aria-label="$t('task.newSubtask')" density="compact"
                variant="underlined" hide-details autofocus maxlength="500" :loading="adding"
                @keydown.enter="addSubtask(t)" @keydown.esc="subFor = null" @blur="!subTitle.trim() && (subFor = null)"
              />
            </div>
          </template>
        </div>
      </TransitionGroup>

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
              icon="mdi-check" size="small" variant="text" class="check" :title="$t('task.markOpen')" :color="list?.color"
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
.items { position: relative; flex: 1; overflow-y: auto; padding: 8px 8px 24px; }
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
/* Holding a task picks it up: no text selection or callout menu meanwhile */
.task { user-select: none; -webkit-touch-callout: none; }
.sorting, .sorting .task { cursor: grabbing; }
.sorting .task:hover { background: none; }
.dragging > .task:first-child, .task.dragging {
  transition: background .15s, box-shadow .15s; background: rgb(var(--v-theme-surface-container-highest)); box-shadow: 0 2px 6px rgba(0, 0, 0, .2);
}
.reorder-move { transition: transform .2s ease; }
.reorder-enter-active { transition: opacity .2s ease, transform .2s ease; }
.reorder-enter-from { opacity: 0; transform: translateY(-8px); }
.dragging > .task:first-child, .task.dragging { position: relative; z-index: 1; }
@media (prefers-reduced-motion: reduce) { .reorder-move, .reorder-enter-active { transition: none; } }
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
