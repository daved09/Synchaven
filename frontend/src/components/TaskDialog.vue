<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useDisplay } from 'vuetify'
import type { Task, TaskInput } from '@/api/types'
import { useNotifyStore } from '@/stores/notify'
import { taskDueDate, useTasksStore } from '@/stores/tasks'
import { combine, dayKey, timeKey } from '@/utils/date'

export interface TaskPreset {
  /** Due date; if not given, the task has no date */
  due?: Date
  allDay?: boolean
  listId?: string
}

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ task: Task | null; preset?: TaskPreset | null }>()

const { xs } = useDisplay()
const tasks = useTasksStore()
const notify = useNotifyStore()

const formRef = ref()
const saving = ref(false)
const title = ref('')
const notes = ref('')
const listId = ref('')
const date = ref('')
const time = ref('')

watch(open, async (v) => {
  if (!v) return
  const t = props.task
  const p = props.preset ?? {}
  const due = t ? taskDueDate(t) : p.due ?? null
  const allDay = t ? t.all_day : p.allDay ?? true
  title.value = t?.title ?? ''
  notes.value = t?.notes ?? ''
  listId.value = t?.list_id ?? p.listId ?? tasks.lists[0]?.id ?? ''
  date.value = due ? dayKey(due) : ''
  time.value = due && !allDay ? timeKey(due) : ''
  await nextTick()
  formRef.value?.resetValidation()
})

const listItems = computed(() => tasks.lists.map((l) => ({ title: l.name, value: l.id, color: l.color })))
/** Hint when the list change affects subtasks */
const listHint = computed(() => {
  const t = props.task
  if (!t || listId.value === t.list_id) return ''
  if (t.parent_id) return 'In einer anderen Liste wird die Unteraufgabe zur Hauptaufgabe.'
  const n = tasks.subtasksOf(t.id).length
  return n === 0 ? '' : n === 1 ? 'Die Unteraufgabe wird mit verschoben.' : `Die ${n} Unteraufgaben werden mit verschoben.`
})

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  const allDay = !date.value || !time.value
  const input: TaskInput = {
    list_id: listId.value,
    title: title.value.trim(),
    notes: notes.value,
    // All-day like events: midnight UTC of the day
    due: !date.value ? null : allDay ? `${date.value}T00:00:00Z` : combine(date.value, time.value).toISOString(),
    all_day: allDay,
  }
  saving.value = true
  try {
    if (props.task) await tasks.update(props.task.id, input)
    else await tasks.create(input)
    notify.info(props.task ? 'Aufgabe gespeichert' : 'Aufgabe erstellt')
    open.value = false
  } catch (e) {
    notify.fail(e)
  } finally {
    saving.value = false
  }
}
function setToday() {
  date.value = dayKey(new Date())
}
</script>

<template>
  <v-dialog v-model="open" :fullscreen="xs" max-width="520" persistent scrollable @keydown.esc="open = false">
    <v-card color="surface-container-high">
      <div class="d-flex align-center pl-6 pr-2 pt-2">
        <span class="text-title-medium flex-1-1">{{ task ? 'Aufgabe bearbeiten' : 'Neue Aufgabe' }}</span>
        <v-btn icon="mdi-close" variant="text" size="small" aria-label="Schließen" @click="open = false" />
      </div>
      <v-card-text class="pt-2 pb-2">
        <v-form ref="formRef" @submit.prevent="save">
          <div class="row">
            <v-icon icon="mdi-text-short" class="row-icon" />
            <v-text-field
              v-model="title" placeholder="Titel hinzufügen" aria-label="Titel" autofocus maxlength="500"
              density="compact" hide-details="auto" :rules="[(v: string) => !!v.trim() || 'Bitte einen Titel eingeben']"
            />
          </div>

          <div class="row">
            <v-icon icon="mdi-clock-outline" class="row-icon" />
            <div class="d-flex flex-column ga-2">
              <div class="d-flex ga-2 align-center">
                <v-text-field
                  v-model="date" type="date" aria-label="Fällig am" density="compact" hide-details class="flex-1-1"
                  clearable @click:clear="time = ''"
                />
                <v-text-field
                  v-if="date" v-model="time" type="time" aria-label="Uhrzeit (optional)" density="compact" hide-details
                  class="time" clearable
                />
              </div>
              <div class="text-body-small text-medium-emphasis">
                <template v-if="!date">
                  Ohne Datum erscheint die Aufgabe nur in der Aufgabenliste.
                  <v-btn variant="text" size="small" density="compact" class="text-none" @click="setToday">Heute</v-btn>
                </template>
                <template v-else-if="!time">Ganztägig – ohne Uhrzeit</template>
              </div>
            </div>
          </div>

          <div class="row">
            <v-icon icon="mdi-format-list-checks" class="row-icon" />
            <v-select
              v-model="listId" :items="listItems" aria-label="Liste" density="compact" :hint="listHint"
              :persistent-hint="!!listHint" :hide-details="!listHint"
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
          </div>

          <div class="row">
            <v-icon icon="mdi-text" class="row-icon" />
            <v-textarea
              v-model="notes" placeholder="Beschreibung hinzufügen" aria-label="Beschreibung" density="compact" hide-details
              rows="3" auto-grow max-rows="10"
            />
          </div>
        </v-form>
      </v-card-text>
      <v-card-actions class="px-6 pb-4">
        <v-btn variant="outlined" @click="open = false">Abbrechen</v-btn>
        <v-spacer />
        <v-btn color="primary" variant="flat" :loading="saving" @click="save">Speichern</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.dot { display: inline-block; width: 12px; height: 12px; border-radius: 6px; flex: 0 0 12px; }
.row { display: grid; grid-template-columns: 24px minmax(0, 1fr); column-gap: 16px; align-items: start; margin-bottom: 12px; }
.row-icon { margin-top: 8px; opacity: .7; }
.time { flex: 0 0 140px; }
</style>
