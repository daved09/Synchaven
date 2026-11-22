<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Calendar } from '@/api/types'
import { CALENDAR_COLORS } from '@/plugins/theme'
import { useCalendarsStore } from '@/stores/calendars'
import { useNotifyStore } from '@/stores/notify'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ calendar: Calendar | null }>()
const calendars = useCalendarsStore()
const notify = useNotifyStore()

const name = ref('')
const color = ref(CALENDAR_COLORS[0])
const description = ref('')
const saving = ref(false)
const formRef = ref()

watch(open, (v) => {
  if (!v) return
  name.value = props.calendar?.name ?? ''
  color.value = props.calendar?.color ?? CALENDAR_COLORS[calendars.list.length % CALENDAR_COLORS.length]
  description.value = props.calendar?.description ?? ''
})

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  saving.value = true
  try {
    const data = { name: name.value, color: color.value, description: description.value }
    if (props.calendar) await calendars.update(props.calendar.id, { ...data, visible: props.calendar.visible })
    else await calendars.create(data)
    notify.info(props.calendar ? 'Kalender gespeichert' : 'Kalender erstellt')
    open.value = false
  } catch (e) {
    notify.fail(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog v-model="open" max-width="480">
    <v-card :title="calendar ? 'Kalender bearbeiten' : 'Neuer Kalender'" color="surface-container-high">
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="save">
          <v-text-field
            v-model="name" label="Name" autofocus maxlength="100"
            :rules="[(v: string) => !!v.trim() || 'Bitte einen Namen eingeben']"
          />
          <div class="text-label-large mb-2">Farbe</div>
          <div class="d-flex flex-wrap ga-2 mb-4 align-center">
            <button
              v-for="c in CALENDAR_COLORS" :key="c" type="button" class="swatch" :style="{ background: c }"
              :aria-label="`Farbe ${c}`" @click="color = c"
            >
              <v-icon v-if="color.toUpperCase() === c" icon="mdi-check" color="white" size="18" />
            </button>
            <v-menu :close-on-content-click="false">
              <template #activator="{ props: p }">
                <v-btn v-bind="p" icon size="32" variant="tonal" title="Eigene Farbe">
                  <v-icon icon="mdi-palette-outline" size="18" />
                </v-btn>
              </template>
              <v-color-picker v-model="color" mode="hex" :modes="['hex']" />
            </v-menu>
          </div>
          <v-textarea v-model="description" label="Beschreibung (optional)" rows="2" auto-grow />
        </v-form>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">Abbrechen</v-btn>
        <v-btn color="primary" variant="flat" :loading="saving" @click="save">Speichern</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.swatch {
  width: 32px; height: 32px; border-radius: 16px; display: inline-flex; align-items: center; justify-content: center;
  transition: transform .1s;
}
.swatch:hover { transform: scale(1.1); }
</style>
