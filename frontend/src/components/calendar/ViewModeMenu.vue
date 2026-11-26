<script setup lang="ts">
import { computed } from 'vue'
import { useDisplay } from 'vuetify'
import { t } from '@/i18n'
import type { ViewMode } from '@/stores/settings'
import { useViewStore } from '@/stores/view'

/** Dropdown for switching between month, week, day and list */
const { mdAndUp } = useDisplay()
const view = useViewStore()

const modes: { value: ViewMode; title: string; icon: string; key: string }[] = [
  { value: 'month', title: t('views.month'), icon: 'mdi-calendar-month-outline', key: 'M' },
  { value: 'week', title: t('views.week'), icon: 'mdi-calendar-week-outline', key: 'W' },
  { value: 'day', title: t('views.day'), icon: 'mdi-calendar-today-outline', key: 'T' },
  { value: 'list', title: t('views.list'), icon: 'mdi-format-list-bulleted', key: 'L' },
]
const current = computed(() => modes.find((m) => m.value === view.mode)!)
</script>

<template>
  <v-menu location="bottom end">
    <template #activator="{ props: p }">
      <v-btn
        v-if="mdAndUp" v-bind="p" variant="outlined" rounded="xl" class="mx-2" :prepend-icon="current.icon"
        append-icon="mdi-menu-down" :title="current.title"
      >
        {{ current.title }}
      </v-btn>
      <v-btn v-else v-bind="p" :icon="current.icon" :title="current.title" />
    </template>
    <v-list density="compact" rounded="lg">
      <v-list-item
        v-for="m in modes" :key="m.value" :prepend-icon="m.icon" :title="m.title" :active="view.mode === m.value"
        @click="view.select(m.value)"
      >
        <template #append>
          <span class="text-body-small text-medium-emphasis ml-4">{{ m.key }}</span>
        </template>
      </v-list-item>
    </v-list>
  </v-menu>
</template>
