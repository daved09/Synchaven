<script setup lang="ts">
import { SEED_COLORS } from '@/plugins/theme'
import { useSettingsStore } from '@/stores/settings'

const open = defineModel<boolean>({ required: true })
const { settings } = useSettingsStore()

const weekStarts = [
  { title: 'Montag', value: 1 },
  { title: 'Sonntag', value: 0 },
  { title: 'Samstag', value: 6 },
]
const views = [
  { title: 'Monat', value: 'month' },
  { title: 'Woche', value: 'week' },
  { title: 'Tag', value: 'day' },
  { title: 'Terminliste', value: 'list' },
]
const hours = Array.from({ length: 13 }, (_, h) => ({ title: `${String(h).padStart(2, '0')}:00`, value: h }))
</script>

<template>
  <v-dialog v-model="open" max-width="520">
    <v-card title="Einstellungen" color="surface-container-high">
      <v-card-text>
        <div class="text-title-small mb-2">Erscheinungsbild</div>
        <v-btn-toggle v-model="settings.themeMode" mandatory divided variant="outlined" rounded="xl" density="comfortable" class="mb-4">
          <v-btn value="system" prepend-icon="mdi-theme-light-dark">System</v-btn>
          <v-btn value="light" prepend-icon="mdi-white-balance-sunny">Hell</v-btn>
          <v-btn value="dark" prepend-icon="mdi-weather-night">Dunkel</v-btn>
        </v-btn-toggle>

        <div class="text-title-small mb-2">Designfarbe</div>
        <div class="d-flex flex-wrap ga-2 mb-6 align-center">
          <button
            v-for="c in SEED_COLORS" :key="c" class="swatch" :style="{ background: c }" :aria-label="`Designfarbe ${c}`"
            @click="settings.seedColor = c"
          >
            <v-icon v-if="settings.seedColor.toUpperCase() === c" icon="mdi-check" color="white" size="20" />
          </button>
          <v-menu :close-on-content-click="false">
            <template #activator="{ props: p }">
              <v-btn v-bind="p" icon size="36" variant="tonal" title="Eigene Farbe"><v-icon icon="mdi-palette-outline" /></v-btn>
            </template>
            <v-color-picker v-model="settings.seedColor" mode="hex" :modes="['hex']" />
          </v-menu>
        </div>

        <div class="text-title-small mb-2">Kalender</div>
        <v-select v-model="settings.defaultView" :items="views" label="Standardansicht" />
        <v-select v-model="settings.weekStartsOn" :items="weekStarts" label="Woche beginnt am" />
        <v-select v-model="settings.dayStartHour" :items="hours" label="Tagesansicht scrollt zu" />
        <v-switch v-model="settings.showWeekNumbers" label="Kalenderwochen anzeigen" color="primary" inset hide-details />
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">Fertig</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.swatch {
  width: 36px; height: 36px; border-radius: 18px; display: inline-flex; align-items: center; justify-content: center;
  transition: transform .1s;
}
.swatch:hover { transform: scale(1.1); }
</style>
