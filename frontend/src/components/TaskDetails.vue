<script setup lang="ts">
import { t as tr } from '@/i18n'
import { computed, ref, watch } from 'vue'
import type { Task } from '@/api/types'
import { useNotifyStore } from '@/stores/notify'
import { taskDueDate, useTasksStore } from '@/stores/tasks'
import { fmt, fmtTime } from '@/utils/date'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ taskId: string | null }>()
const emit = defineEmits<{ edit: [task: Task]; delete: [task: Task]; toggle: [task: Task]; open: [task: Task] }>()
const tasks = useTasksStore()
const notify = useNotifyStore()

// Always show the current state from the store (e.g. after checking off)
const task = computed(() => tasks.tasks.find((t) => t.id === props.taskId) ?? null)
const list = computed(() => (task.value ? tasks.listById.get(task.value.list_id) : undefined))
const due = computed(() => {
  const t = task.value
  const d = t && taskDueDate(t)
  if (!t || !d) return tr('task.noDate')
  const day = fmt(d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  return t.all_day ? day : `${day} · ${fmtTime(d)}`
})
const parent = computed(() =>
  task.value?.parent_id ? tasks.tasks.find((t) => t.id === task.value!.parent_id) ?? null : null,
)
const subtasks = computed(() => (task.value && !task.value.parent_id ? tasks.subtasksOf(task.value.id) : []))

const subTitle = ref('')
const adding = ref(false)
watch(() => props.taskId, () => (subTitle.value = ''))

async function addSubtask() {
  const title = subTitle.value.trim()
  if (!title || !task.value) return
  adding.value = true
  try {
    await tasks.createSubtask(task.value, title)
    subTitle.value = ''
  } catch (e) {
    notify.fail(e)
  } finally {
    adding.value = false
  }
}
async function toggleSub(s: Task) {
  try {
    await tasks.setCompleted(s, !s.completed)
  } catch (e) {
    notify.fail(e)
  }
}
function subDue(s: Task) {
  const d = taskDueDate(s)
  if (!d) return ''
  const day = fmt(d, { weekday: 'short', day: 'numeric', month: 'short' })
  return s.all_day ? day : `${day}, ${fmtTime(d)}`
}

const completedAt = computed(() => {
  const c = task.value?.completed_at
  return c ? fmt(new Date(c), { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''
})
</script>

<template>
  <v-dialog v-model="open" max-width="480">
    <v-card v-if="task" color="surface-container-high" class="pb-2">
      <div class="d-flex justify-end pa-2 ga-1">
        <v-btn icon="mdi-pencil-outline" variant="text" :title="$t('common.edit')" @click="emit('edit', task)" />
        <v-btn icon="mdi-delete-outline" variant="text" :title="$t('common.delete')" @click="emit('delete', task)" />
        <v-btn icon="mdi-close" variant="text" :title="$t('common.close')" @click="open = false" />
      </div>
      <div class="px-6">
        <div class="d-flex ga-4 align-start">
          <v-icon
            :icon="task.completed ? 'mdi-check-circle' : 'mdi-checkbox-blank-circle-outline'"
            :color="list?.color" class="mt-1"
          />
          <div class="flex-grow-1" style="min-width: 0">
            <button v-if="parent" class="parent text-body-medium text-medium-emphasis" @click="emit('open', parent)">
              <v-icon icon="mdi-subdirectory-arrow-right" size="16" /> {{ $t('task.subtaskOf', { title: parent.title }) }}
            </button>
            <div class="text-headline-small title" :class="{ done: task.completed }">{{ task.title }}</div>
            <div class="text-body-medium text-medium-emphasis mt-1">{{ due }}</div>
            <div v-if="task.completed" class="text-body-medium text-medium-emphasis">{{ $t('task.completedOn', { date: completedAt }) }}</div>
          </div>
        </div>
        <div v-if="task.notes" class="d-flex ga-4 mt-4 align-start">
          <v-icon icon="mdi-text" class="text-medium-emphasis" />
          <div class="text-body-large notes">{{ task.notes }}</div>
        </div>
        <div v-if="!task.parent_id" class="d-flex ga-4 mt-4 align-start">
          <v-icon icon="mdi-file-tree-outline" class="text-medium-emphasis mt-2" />
          <div class="flex-grow-1" style="min-width: 0">
            <div
              v-for="s in subtasks" :key="s.id" class="sub" role="button" tabindex="0"
              @click="emit('open', s)" @keydown.enter="emit('open', s)"
            >
              <v-btn
                icon size="small" variant="text" density="comfortable" :color="list?.color"
                :title="s.completed ? $t('task.markOpen') : $t('task.markDone')" @click.stop="toggleSub(s)"
              >
                <v-icon :icon="s.completed ? 'mdi-check-circle' : 'mdi-checkbox-blank-circle-outline'" />
              </v-btn>
              <span class="text-body-large sub-title" :class="{ done: s.completed }">{{ s.title }}</span>
              <span v-if="s.due" class="text-body-small text-medium-emphasis ml-auto pl-2">{{ subDue(s) }}</span>
            </div>
            <v-text-field
              v-model="subTitle" :placeholder="$t('task.addSubtask')" :aria-label="$t('task.newSubtask')" density="compact"
              variant="underlined" hide-details maxlength="500" prepend-inner-icon="mdi-plus" :loading="adding"
              @keydown.enter="addSubtask"
            />
          </div>
        </div>
        <div v-if="list" class="d-flex ga-4 mt-4 align-center">
          <v-icon icon="mdi-format-list-checks" class="text-medium-emphasis" />
          <span class="text-body-large">{{ list.name }}</span>
        </div>
        <div class="d-flex justify-end mt-4 mb-2">
          <v-btn
            color="primary" :variant="task.completed ? 'outlined' : 'flat'"
            :prepend-icon="task.completed ? 'mdi-undo' : 'mdi-check'" @click="emit('toggle', task)"
          >
            {{ task.completed ? $t('task.markOpen') : $t('task.markDone') }}
          </v-btn>
        </div>
      </div>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.title { word-break: break-word; }
.title.done { text-decoration: line-through; opacity: .7; }
.notes { white-space: pre-wrap; word-break: break-word; }
.parent { display: inline-flex; align-items: center; gap: 4px; text-align: left; margin-bottom: 2px; }
.parent:hover { text-decoration: underline; }
.sub { display: flex; align-items: center; gap: 4px; margin-left: -8px; border-radius: 8px; cursor: pointer; }
.sub:hover { background: rgba(var(--v-theme-on-surface), .06); }
.sub-title { min-width: 0; word-break: break-word; }
.sub-title.done { text-decoration: line-through; opacity: .6; }
</style>
