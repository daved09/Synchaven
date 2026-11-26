<script setup lang="ts">
import { ref, watch } from 'vue'
import { t } from '@/i18n'
import type { Calendar } from '@/api/types'
import CalendarDialog from '@/components/CalendarDialog.vue'
import { useCalendarsStore } from '@/stores/calendars'
import { useConfirmStore } from '@/stores/confirm'
import { useEventsStore } from '@/stores/events'
import { useNotifyStore } from '@/stores/notify'
import { pickFiles } from '@/utils/files'

/** "My calendars" in the sidebar: visibility, editing, subscriptions, ICS import/export, deleting */
const calendars = useCalendarsStore()
const events = useEventsStore()
const notify = useNotifyStore()
const confirm = useConfirmStore()

const dialogOpen = ref(false)
const editing = ref<Calendar | null>(null)
const subscribing = ref(false)
watch(dialogOpen, (v) => !v && events.reload())

function open(cal: Calendar | null, subscribe = false) {
  editing.value = cal
  subscribing.value = subscribe
  dialogOpen.value = true
}
async function refresh(cal: Calendar) {
  try {
    const c = await calendars.refresh(cal.id)
    await events.reload()
    if (c.sync_error) notify.fail(new Error(t('subscription.refreshFailed', { error: c.sync_error })))
    else notify.info(t('subscription.refreshed'))
  } catch (e) {
    notify.fail(e)
  }
}
async function toggle(cal: Calendar) {
  try {
    await calendars.toggleVisible(cal)
  } catch (e) {
    notify.fail(e)
  }
}
async function remove(cal: Calendar) {
  if (!(await confirm.ask(t('calendarView.deleteCalendarTitle'), t('calendarView.deleteCalendarText', { name: cal.name })))) return
  try {
    await calendars.remove(cal.id)
    await events.reload()
    notify.info(t('calendarView.calendarDeleted'))
  } catch (e) {
    notify.fail(e)
  }
}
async function exportIcs(cal: Calendar) {
  try {
    await calendars.exportIcs(cal)
  } catch (e) {
    notify.fail(e)
  }
}
async function importIcs(cal: Calendar) {
  const [file] = await pickFiles('.ics,text/calendar')
  if (!file) return
  try {
    const r = await calendars.importIcs(cal, file)
    await events.reload()
    notify.info(t('calendarView.imported', r.imported) + (r.skipped.length ? ', ' + t('calendarView.importNotes', r.skipped.length) : ''))
  } catch (e) {
    notify.fail(e)
  }
}
</script>

<template>
  <v-list nav density="comfortable" class="pt-0" bg-color="transparent">
    <div class="d-flex align-center pl-4 pr-2">
      <v-list-subheader class="flex-grow-1 px-0">{{ $t('calendarView.myCalendars') }}</v-list-subheader>
      <v-menu location="end">
        <template #activator="{ props: p }">
          <v-btn v-bind="p" icon="mdi-plus" size="small" variant="text" :title="$t('calendar.add')" />
        </template>
        <v-list density="compact" rounded="lg">
          <v-list-item prepend-icon="mdi-calendar-plus-outline" :title="$t('calendar.new')" @click="open(null)" />
          <v-list-item prepend-icon="mdi-calendar-sync-outline" :title="$t('subscription.new')" @click="open(null, true)" />
        </v-list>
      </v-menu>
    </div>
    <v-list-item v-for="cal in calendars.list" :key="cal.id" rounded="xl" class="menu-item" @click="toggle(cal)">
      <template #prepend>
        <v-checkbox-btn :model-value="cal.visible" :color="cal.color" :base-color="cal.color" density="compact" class="mr-1" @click.stop="toggle(cal)" />
      </template>
      <v-list-item-title class="d-flex align-center ga-1">
        <span class="text-truncate">{{ cal.name }}</span>
        <v-icon v-if="cal.sync_error" icon="mdi-alert-circle-outline" color="warning" size="16" :title="cal.sync_error" />
      </v-list-item-title>
      <template #append>
        <v-menu location="end">
          <template #activator="{ props: p }">
            <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" class="item-menu" @click.stop />
          </template>
          <v-list density="compact" rounded="lg">
            <v-list-item prepend-icon="mdi-pencil-outline" :title="$t('common.edit')" @click="open(cal)" />
            <v-list-item
              v-if="cal.kind === 'subscription'" prepend-icon="mdi-refresh" :title="$t('subscription.refresh')"
              @click="refresh(cal)"
            />
            <v-list-item prepend-icon="mdi-download-outline" :title="$t('calendarView.exportIcs')" @click="exportIcs(cal)" />
            <template v-if="cal.kind !== 'birthdays'">
              <v-list-item
                v-if="cal.kind === 'default'" prepend-icon="mdi-upload-outline" :title="$t('calendarView.importIcs')"
                @click="importIcs(cal)"
              />
              <v-divider />
              <v-list-item prepend-icon="mdi-delete-outline" :title="$t('common.delete')" base-color="error" @click="remove(cal)" />
            </template>
          </v-list>
        </v-menu>
      </template>
    </v-list-item>
  </v-list>
  <CalendarDialog v-model="dialogOpen" :calendar="editing" :subscribe="subscribing" />
</template>
