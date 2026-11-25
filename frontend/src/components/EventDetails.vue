<script setup lang="ts">
import { computed } from 'vue'
import type { ViewEvent } from '@/api/types'
import { useCalendarsStore } from '@/stores/calendars'
import { describeSpan } from '@/utils/date'
import { describeRRule } from '@/utils/rrule'
import { describeReminder, sortReminders } from '@/utils/reminders'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ event: ViewEvent | null }>()
const emit = defineEmits<{ edit: [ev: ViewEvent]; delete: [ev: ViewEvent, scope: 'one' | 'all'] }>()
const calendars = useCalendarsStore()

const calendar = computed(() => (props.event ? calendars.byId.get(props.event.calendar_id) : undefined))
const recurrence = computed(() => {
  const e = props.event
  return e?.rrule ? describeRRule(e.rrule, e.startDate) : ''
})
/** When notifications are sent: the event's own reminders or the calendar default */
const reminders = computed(() => {
  const e = props.event
  if (!e) return []
  const list = e.reminders ?? (e.all_day ? calendar.value?.default_all_day_reminders : calendar.value?.default_reminders) ?? []
  return sortReminders(list).map((m) => describeReminder(m, e.all_day))
})
</script>

<template>
  <v-dialog v-model="open" max-width="480">
    <v-card v-if="event" color="surface-container-high" class="pb-2">
      <div class="d-flex justify-end pa-2 ga-1">
        <v-btn
          v-if="event.contact_id" icon="mdi-account-outline" variant="text" :title="$t('event.openContact')"
          :to="{ name: 'contacts', query: { id: event.contact_id } }"
        />
        <template v-if="!event.read_only">
          <v-btn icon="mdi-pencil-outline" variant="text" :title="$t('common.edit')" @click="emit('edit', event)" />
          <v-menu v-if="event.recurring" location="bottom end">
            <template #activator="{ props: p }">
              <v-btn v-bind="p" icon="mdi-delete-outline" variant="text" :title="$t('common.delete')" />
            </template>
            <v-list density="compact" rounded="lg">
              <v-list-item prepend-icon="mdi-calendar-remove-outline" :title="$t('event.onlyThis')" @click="emit('delete', event, 'one')" />
              <v-list-item prepend-icon="mdi-delete-sweep-outline" :title="$t('event.wholeSeries')" @click="emit('delete', event, 'all')" />
            </v-list>
          </v-menu>
          <v-btn v-else icon="mdi-delete-outline" variant="text" :title="$t('common.delete')" @click="emit('delete', event, 'all')" />
        </template>
        <v-btn icon="mdi-close" variant="text" :title="$t('common.close')" @click="open = false" />
      </div>
      <div class="px-6">
        <div class="d-flex ga-4 align-start">
          <span class="swatch mt-2" :style="{ background: event.color }" />
          <div class="flex-grow-1" style="min-width: 0">
            <div class="text-headline-small title">{{ event.title }}</div>
            <div class="text-body-medium text-medium-emphasis mt-1">
              {{ describeSpan(event.startDate, event.endDate, event.all_day) }}
            </div>
            <div v-if="recurrence" class="text-body-medium text-medium-emphasis">{{ recurrence }}</div>
          </div>
        </div>
        <div v-if="event.location" class="d-flex ga-4 mt-4 align-start">
          <v-icon icon="mdi-map-marker-outline" class="text-medium-emphasis" />
          <a
            class="text-body-large link"
            :href="`https://www.openstreetmap.org/search?query=${encodeURIComponent(event.location)}`"
            target="_blank" rel="noopener noreferrer"
          >{{ event.location }}</a>
        </div>
        <div v-if="event.description" class="d-flex ga-4 mt-4 align-start">
          <v-icon icon="mdi-text" class="text-medium-emphasis" />
          <div class="text-body-large description">{{ event.description }}</div>
        </div>
        <div v-if="reminders.length" class="d-flex ga-4 mt-4 align-start" :title="$t('event.notification')">
          <v-icon icon="mdi-bell-outline" class="text-medium-emphasis" />
          <div class="text-body-large">
            <div v-for="r in reminders" :key="r">{{ r }}</div>
          </div>
        </div>
        <div v-if="calendar" class="d-flex ga-4 mt-4 align-center">
          <v-icon icon="mdi-calendar-outline" class="text-medium-emphasis" />
          <span class="text-body-large">{{ calendar.name }}</span>
        </div>
      </div>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.swatch { width: 16px; height: 16px; border-radius: 4px; flex: 0 0 16px; }
.title { word-break: break-word; }
.description { white-space: pre-wrap; word-break: break-word; }
.link { color: inherit; text-decoration: underline; text-decoration-color: rgba(var(--v-theme-on-surface), .3); }
</style>
