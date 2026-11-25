<script setup lang="ts">
import { t } from '@/i18n'
import { ref, watch } from 'vue'
import type { Calendar } from '@/api/types'
import { CALENDAR_COLORS } from '@/plugins/theme'
import { useCalendarsStore } from '@/stores/calendars'
import { useNotifyStore } from '@/stores/notify'
import { DEFAULT_ALL_DAY, DEFAULT_TIMED } from '@/utils/reminders'
import ReminderEditor from './ReminderEditor.vue'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ calendar: Calendar | null }>()
const calendars = useCalendarsStore()
const notify = useNotifyStore()

const name = ref('')
const color = ref(CALENDAR_COLORS[0])
const description = ref('')
const defaults = ref<number[]>([])
const allDayDefaults = ref<number[]>([])
const saving = ref(false)
const formRef = ref()

watch(open, (v) => {
  if (!v) return
  name.value = props.calendar?.name ?? ''
  color.value = props.calendar?.color ?? CALENDAR_COLORS[calendars.list.length % CALENDAR_COLORS.length]
  description.value = props.calendar?.description ?? ''
  defaults.value = props.calendar?.default_reminders ?? [...DEFAULT_TIMED]
  allDayDefaults.value = props.calendar?.default_all_day_reminders ?? [...DEFAULT_ALL_DAY]
})

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  saving.value = true
  try {
    const data = {
      name: name.value,
      color: color.value,
      description: description.value,
      default_reminders: defaults.value,
      default_all_day_reminders: allDayDefaults.value,
    }
    if (props.calendar) await calendars.update(props.calendar.id, { ...data, visible: props.calendar.visible })
    else await calendars.create(data)
    notify.info(props.calendar ? t('calendar.saved') : t('calendar.created'))
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
    <v-card :title="calendar ? $t('calendar.edit') : $t('calendar.new')" color="surface-container-high">
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="save">
          <v-text-field
            v-model="name" :label="$t('common.name')" autofocus maxlength="100"
            :rules="[(v: string) => !!v.trim() || $t('common.nameRequired')]"
          />
          <div class="text-label-large mb-2">{{ $t('common.color') }}</div>
          <div class="d-flex flex-wrap ga-2 mb-4 align-center">
            <button
              v-for="c in CALENDAR_COLORS" :key="c" type="button" class="swatch" :style="{ background: c }"
              :aria-label="$t('common.colorNamed', { color: c })" @click="color = c"
            >
              <v-icon v-if="color.toUpperCase() === c" icon="mdi-check" color="white" size="18" />
            </button>
            <v-menu :close-on-content-click="false">
              <template #activator="{ props: p }">
                <v-btn v-bind="p" icon size="32" variant="tonal" :title="$t('settings.customColor')">
                  <v-icon icon="mdi-palette-outline" size="18" />
                </v-btn>
              </template>
              <v-color-picker v-model="color" mode="hex" :modes="['hex']" />
            </v-menu>
          </div>
          <v-textarea v-model="description" :label="$t('common.descriptionOptional')" rows="2" auto-grow />
          <div class="text-label-large mt-2 d-flex align-center ga-2">
            <v-icon icon="mdi-bell-outline" size="18" /> {{ $t('calendar.defaultReminders') }}
          </div>
          <div class="text-body-small text-medium-emphasis mb-2">
            {{ $t('calendar.defaultRemindersHint') }}
          </div>
          <template v-if="calendar?.kind !== 'birthdays'">
            <div class="text-body-medium mt-1">{{ $t('calendar.timedEvents') }}</div>
            <ReminderEditor v-model="defaults" :all-day="false" class="mb-2" />
            <div class="text-body-medium mt-1">{{ $t('calendar.allDayEvents') }}</div>
          </template>
          <ReminderEditor v-model="allDayDefaults" :all-day="true" />
        </v-form>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" variant="flat" :loading="saving" @click="save">{{ $t('common.save') }}</v-btn>
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
