<script setup lang="ts">
import { language, t } from '@/i18n'
import { computed, ref, watch } from 'vue'
import type { Calendar, CalendarInput } from '@/api/types'
import { CALENDAR_COLORS } from '@/plugins/theme'
import { useCalendarsStore } from '@/stores/calendars'
import { useNotifyStore } from '@/stores/notify'
import { fmt } from '@/utils/date'
import { DEFAULT_ALL_DAY, DEFAULT_TIMED } from '@/utils/reminders'
import ReminderEditor from './ReminderEditor.vue'

const open = defineModel<boolean>({ required: true })
/** `subscribe`: a new calendar that is fetched from an address (ignored when editing) */
const props = defineProps<{ calendar: Calendar | null; subscribe?: boolean }>()
const calendars = useCalendarsStore()
const notify = useNotifyStore()

const name = ref('')
const color = ref(CALENDAR_COLORS[0])
const description = ref('')
const defaults = ref<number[]>([])
const allDayDefaults = ref<number[]>([])
const sourceUrl = ref('')
const saving = ref(false)
const formRef = ref()

/** Subscription: new one or editing one */
const isSubscription = computed(() => (props.calendar ? props.calendar.kind === 'subscription' : !!props.subscribe))
const title = computed(() => {
  if (props.calendar) return isSubscription.value ? t('subscription.edit') : t('calendar.edit')
  return isSubscription.value ? t('subscription.new') : t('calendar.new')
})

/** Public holiday calendars (Google), in the language of the web UI */
const HOLIDAYS = ['german', 'austrian', 'ch', 'usa', 'uk'] as const
const holidayUrl = (region: string) =>
  `https://calendar.google.com/calendar/ical/${language}.${region}%23holiday%40group.v.calendar.google.com/public/basic.ics`

const lastSync = computed(() => {
  const at = props.calendar?.synced_at
  return at ? fmt(new Date(at), { dateStyle: 'medium', timeStyle: 'short' }) : null
})

watch(open, (v) => {
  if (!v) return
  sourceUrl.value = props.calendar?.source_url ?? ''
  name.value = props.calendar?.name ?? ''
  color.value = props.calendar?.color ?? CALENDAR_COLORS[calendars.list.length % CALENDAR_COLORS.length]
  description.value = props.calendar?.description ?? ''
  // New subscriptions start without reminders – nobody wants one for every public holiday
  defaults.value = props.calendar?.default_reminders ?? (isSubscription.value ? [] : [...DEFAULT_TIMED])
  allDayDefaults.value = props.calendar?.default_all_day_reminders ?? (isSubscription.value ? [] : [...DEFAULT_ALL_DAY])
})

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  saving.value = true
  try {
    const data: CalendarInput = {
      name: name.value,
      color: color.value,
      description: description.value,
      default_reminders: defaults.value,
      default_all_day_reminders: allDayDefaults.value,
    }
    if (isSubscription.value) data.source_url = sourceUrl.value.trim()
    if (props.calendar) await calendars.update(props.calendar.id, { ...data, visible: props.calendar.visible })
    else await calendars.create(data)
    if (props.calendar) notify.info(t('calendar.saved'))
    else notify.info(isSubscription.value ? t('subscription.created') : t('calendar.created'))
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
    <v-card :title="title" color="surface-container-high">
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="save">
          <template v-if="isSubscription">
            <v-text-field
              v-model="sourceUrl" :label="$t('subscription.url')" type="url" autofocus maxlength="2000"
              placeholder="https://… / webcal://…" :hint="$t('subscription.urlHint')" persistent-hint class="mb-3"
              :rules="[(v: string) => !!v.trim() || $t('subscription.urlRequired')]"
            />
            <template v-if="!calendar">
              <div class="text-label-large mb-1">{{ $t('subscription.holidays') }}</div>
              <div class="d-flex flex-wrap ga-2 mb-4">
                <v-chip
                  v-for="region in HOLIDAYS" :key="region" size="small" prepend-icon="mdi-flag-outline"
                  :variant="sourceUrl === holidayUrl(region) ? 'flat' : 'tonal'"
                  :color="sourceUrl === holidayUrl(region) ? 'primary' : undefined"
                  @click="sourceUrl = holidayUrl(region)"
                >
                  {{ $t(`subscription.region.${region}`) }}
                </v-chip>
              </div>
            </template>
            <v-alert
              v-if="calendar?.sync_error" type="warning" variant="tonal" density="compact" class="mb-4"
              :text="$t('subscription.lastError', { error: calendar.sync_error })"
            />
            <div v-if="lastSync" class="text-body-small text-medium-emphasis mb-3">
              {{ $t('subscription.lastSync', { date: lastSync }) }}
            </div>
          </template>
          <v-text-field
            v-model="name" :label="isSubscription && !calendar ? $t('subscription.nameOptional') : $t('common.name')"
            :autofocus="!isSubscription" maxlength="100"
            :rules="[(v: string) => !!v.trim() || (isSubscription && !calendar) || $t('common.nameRequired')]"
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
