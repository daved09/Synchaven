<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { SEED_COLORS } from '@/plugins/theme'
import type { ViewMode } from '@/api/types'
import AdminSection from '@/components/AdminSection.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'
import { useSettingsStore } from '@/stores/settings'
import { disablePush, enablePush, pushState, testPush, type PushState } from '@/utils/push'

const open = defineModel<boolean>({ required: true })
const { settings } = useSettingsStore()
const auth = useAuthStore()
const notify = useNotifyStore()

/** Die Standardansicht gehört zum Benutzerkonto */
function setDefaultView(v: ViewMode) {
  auth.update({ default_view: v }).catch((e) => notify.fail(e))
}

// ---------- Benachrichtigungen ----------
const push = ref<PushState | null>(null)
const pushBusy = ref(false)
watch(open, (v) => v && pushState().then((s) => (push.value = s)), { immediate: true })

const pushHint = computed(() => ({
  unsupported: 'Dieser Browser unterstützt keine Push-Benachrichtigungen.',
  insecure: 'Benachrichtigungen funktionieren nur, wenn Synchaven über HTTPS aufgerufen wird.',
  denied: 'Benachrichtigungen sind für diese Seite im Browser blockiert. Bitte in den Website-Einstellungen erlauben.',
  off: 'Erinnerungen an Termine und fällige Aufgaben erscheinen als Benachrichtigung, auch wenn Synchaven nicht geöffnet ist.',
  on: 'Erinnerungen werden an diesen Browser gesendet. Sie werden pro Termin oder als Standard pro Kalender eingestellt; Aufgaben erinnern zur Fälligkeit (ganztägig um 09:00).',
}[push.value ?? 'off']))

async function togglePush(on: boolean | null) {
  pushBusy.value = true
  try {
    if (on) {
      push.value = await enablePush()
      if (push.value !== 'on') notify.fail(new Error(pushHint.value))
    } else {
      await disablePush()
      push.value = await pushState()
    }
  } catch (e) {
    notify.fail(e)
    push.value = await pushState()
  } finally {
    pushBusy.value = false
  }
}

async function sendTest() {
  pushBusy.value = true
  try {
    await testPush()
    notify.info('Testbenachrichtigung gesendet')
  } catch (e) {
    notify.fail(e)
  } finally {
    pushBusy.value = false
  }
}

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
        <v-select
          :model-value="auth.user?.default_view ?? settings.defaultView" :items="views" label="Standardansicht"
          @update:model-value="setDefaultView"
        />
        <v-select v-model="settings.weekStartsOn" :items="weekStarts" label="Woche beginnt am" />
        <v-select v-model="settings.dayStartHour" :items="hours" label="Tagesansicht scrollt zu" />
        <v-switch v-model="settings.showWeekNumbers" label="Kalenderwochen anzeigen" color="primary" inset hide-details />

        <div class="text-title-small mt-4 mb-1">Benachrichtigungen</div>
        <v-switch
          :model-value="push === 'on'" label="Erinnerungen in diesem Browser" color="primary" inset hide-details
          :disabled="push === null || push === 'unsupported' || push === 'insecure' || push === 'denied'"
          :loading="pushBusy" @update:model-value="togglePush"
        />
        <div class="text-body-small text-medium-emphasis">{{ pushHint }}</div>
        <v-btn
          v-if="push === 'on'" variant="tonal" size="small" prepend-icon="mdi-bell-ring-outline" class="mt-2"
          :disabled="pushBusy" @click="sendTest"
        >
          Testbenachrichtigung senden
        </v-btn>

        <AdminSection v-if="auth.user?.is_admin" />
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
