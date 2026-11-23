<script setup lang="ts">
import { computed } from 'vue'
import type { Task } from '@/api/types'
import { taskDueDate, useTasksStore } from '@/stores/tasks'
import { fmt, fmtTime } from '@/utils/date'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ taskId: string | null }>()
const emit = defineEmits<{ edit: [task: Task]; delete: [task: Task]; toggle: [task: Task] }>()
const tasks = useTasksStore()

// Immer den aktuellen Stand aus dem Store zeigen (z. B. nach dem Abhaken)
const task = computed(() => tasks.tasks.find((t) => t.id === props.taskId) ?? null)
const list = computed(() => (task.value ? tasks.listById.get(task.value.list_id) : undefined))
const due = computed(() => {
  const t = task.value
  const d = t && taskDueDate(t)
  if (!t || !d) return 'Ohne Datum'
  const day = fmt(d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  return t.all_day ? day : `${day} · ${fmtTime(d)}`
})
const completedAt = computed(() => {
  const c = task.value?.completed_at
  return c ? fmt(new Date(c), { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''
})
</script>

<template>
  <v-dialog v-model="open" max-width="480">
    <v-card v-if="task" color="surface-container-high" class="pb-2">
      <div class="d-flex justify-end pa-2 ga-1">
        <v-btn icon="mdi-pencil-outline" variant="text" title="Bearbeiten" @click="emit('edit', task)" />
        <v-btn icon="mdi-delete-outline" variant="text" title="Löschen" @click="emit('delete', task)" />
        <v-btn icon="mdi-close" variant="text" title="Schließen" @click="open = false" />
      </div>
      <div class="px-6">
        <div class="d-flex ga-4 align-start">
          <v-icon
            :icon="task.completed ? 'mdi-check-circle' : 'mdi-checkbox-blank-circle-outline'"
            :color="list?.color" class="mt-1"
          />
          <div class="flex-grow-1" style="min-width: 0">
            <div class="text-headline-small title" :class="{ done: task.completed }">{{ task.title }}</div>
            <div class="text-body-medium text-medium-emphasis mt-1">{{ due }}</div>
            <div v-if="task.completed" class="text-body-medium text-medium-emphasis">Erledigt am {{ completedAt }}</div>
          </div>
        </div>
        <div v-if="task.notes" class="d-flex ga-4 mt-4 align-start">
          <v-icon icon="mdi-text" class="text-medium-emphasis" />
          <div class="text-body-large notes">{{ task.notes }}</div>
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
            {{ task.completed ? 'Als offen markieren' : 'Als erledigt markieren' }}
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
</style>
