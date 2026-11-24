<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ALL_DAY_PRESETS, MAX_REMINDERS, TIMED_PRESETS, allDayMinutes, describeReminder, isValidReminder, sortReminders,
} from '@/utils/reminders'

/** List of reminders (minutes before the start) */
const list = defineModel<number[]>({ required: true })
const props = defineProps<{ allDay: boolean; disabled?: boolean }>()

const menu = ref(false)
const custom = ref(false)
const amount = ref(1)
const unit = ref(60)
const daysBefore = ref(1)
const at = ref('09:00')

watch(menu, (v) => v && (custom.value = false))

const presets = computed(() =>
  (props.allDay ? ALL_DAY_PRESETS : TIMED_PRESETS)
    .filter((m) => !list.value.includes(m))
    .map((m) => ({ value: m, title: describeReminder(m, props.allDay) })),
)
const units = [
  { title: 'Minuten', value: 1 },
  { title: 'Stunden', value: 60 },
  { title: 'Tage', value: 1440 },
  { title: 'Wochen', value: 10080 },
]

const customMinutes = computed(() => {
  if (props.allDay) {
    const [h, m] = at.value.split(':').map(Number)
    return allDayMinutes(Number(daysBefore.value), h || 0, m || 0)
  }
  return Number(amount.value) * unit.value
})
const customValid = computed(() => isValidReminder(customMinutes.value, props.allDay))

function add(m: number) {
  list.value = sortReminders([...list.value, m])
  menu.value = false
}
function remove(m: number) {
  list.value = list.value.filter((x) => x !== m)
}
</script>

<template>
  <div>
    <div v-for="m in list" :key="m" class="d-flex align-center item">
      <span class="text-body-large flex-1-1">{{ describeReminder(m, allDay) }}</span>
      <v-btn
        v-if="!disabled" icon="mdi-close" size="small" variant="text" density="comfortable"
        :aria-label="`Erinnerung „${describeReminder(m, allDay)}“ entfernen`" @click="remove(m)"
      />
    </div>
    <v-menu v-if="!disabled && list.length < MAX_REMINDERS" v-model="menu" :close-on-content-click="false" location="bottom start">
      <template #activator="{ props: p }">
        <v-btn v-bind="p" variant="text" size="small" prepend-icon="mdi-plus" class="text-none add-btn">
          Erinnerung hinzufügen
        </v-btn>
      </template>
      <v-card color="surface-container-highest" min-width="260">
        <v-list v-if="!custom" density="compact">
          <v-list-item v-for="p in presets" :key="p.value" :title="p.title" @click="add(p.value)" />
          <v-divider class="my-1" />
          <v-list-item title="Benutzerdefiniert …" prepend-icon="mdi-tune-variant" @click="custom = true" />
        </v-list>
        <div v-else class="pa-4">
          <div v-if="allDay" class="d-flex align-center ga-2">
            <v-text-field
              v-model.number="daysBefore" type="number" min="0" max="28" density="compact" hide-details
              aria-label="Tage vorher" style="max-width: 80px"
            />
            <span class="text-body-large">Tage vorher um</span>
            <v-text-field v-model="at" type="time" density="compact" hide-details aria-label="Uhrzeit" style="max-width: 120px" />
          </div>
          <div v-else class="d-flex align-center ga-2">
            <v-text-field
              v-model.number="amount" type="number" min="0" density="compact" hide-details aria-label="Anzahl"
              style="max-width: 80px"
            />
            <v-select v-model="unit" :items="units" density="compact" hide-details aria-label="Einheit" style="max-width: 130px" />
            <span class="text-body-large">vorher</span>
          </div>
          <div v-if="!customValid" class="text-body-small text-error mt-2">Höchstens 4 Wochen vorher</div>
          <div class="d-flex justify-end ga-2 mt-3">
            <v-btn variant="text" @click="custom = false">Zurück</v-btn>
            <v-btn color="primary" variant="flat" :disabled="!customValid" @click="add(customMinutes)">Hinzufügen</v-btn>
          </div>
        </div>
      </v-card>
    </v-menu>
  </div>
</template>

<style scoped>
.item { min-height: 36px; }
.add-btn { margin-left: -12px; }
</style>
