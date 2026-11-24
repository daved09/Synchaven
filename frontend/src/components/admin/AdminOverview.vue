<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { AdminOverview } from '@/api/types'
import { LOCALE } from '@/utils/date'
import { useNotifyStore } from '@/stores/notify'

const notify = useNotifyStore()
const data = ref<AdminOverview | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    data.value = await api.adminOverview()
  } catch (e) {
    notify.fail(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
defineExpose({ load })

const nf = new Intl.NumberFormat(LOCALE)

function formatBytes(n: number): string {
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${new Intl.NumberFormat(LOCALE, { maximumFractionDigits: i ? 1 : 0 }).format(n)} ${units[i]}`
}

function since(iso: string): string {
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000)
  if (mins < 60) return `${mins} Min.`
  const h = Math.floor(mins / 60)
  if (h < 48) return `${h} Std.`
  return `${Math.floor(h / 24)} Tagen`
}

const tiles = computed(() => {
  const c = data.value?.counts
  if (!c) return []
  return [
    { icon: 'mdi-account-multiple-outline', label: 'Benutzer', value: c.users, note: c.disabled_users ? `${c.disabled_users} gesperrt` : '' },
    { icon: 'mdi-calendar-multiple', label: 'Kalender', value: c.calendars, note: '' },
    { icon: 'mdi-calendar-text-outline', label: 'Termine', value: c.events, note: 'ohne Geburtstage' },
    { icon: 'mdi-book-account-outline', label: 'Adressbücher', value: c.addressbooks, note: '' },
    { icon: 'mdi-account-box-multiple-outline', label: 'Kontakte', value: c.contacts, note: '' },
    { icon: 'mdi-checkbox-marked-circle-outline', label: 'Aufgaben', value: c.tasks, note: '' },
    { icon: 'mdi-bell-outline', label: 'Browser mit Benachrichtigungen', value: c.push_subscriptions, note: '' },
  ]
})
</script>

<template>
  <div v-if="loading && !data" class="d-flex justify-center pa-8"><v-progress-circular indeterminate color="primary" /></div>
  <template v-else-if="data">
    <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2">
      <v-card-item>
        <template #prepend><v-icon icon="mdi-server" color="primary" size="32" /></template>
        <v-card-title>Synchaven {{ data.version }}</v-card-title>
        <v-card-subtitle>
          Läuft seit {{ since(data.started_at) }} ·
          Datenbank {{ formatBytes(data.database_size) }}
        </v-card-subtitle>
        <template #append>
          <v-btn icon="mdi-refresh" variant="text" title="Aktualisieren" :loading="loading" @click="load" />
        </template>
      </v-card-item>
    </v-card>

    <div class="tiles mt-4">
      <v-card v-for="t in tiles" :key="t.label" variant="flat" color="surface-container-low" rounded="xl" class="pa-4">
        <v-icon :icon="t.icon" color="primary" class="mb-2" />
        <div class="text-headline-small">{{ nf.format(t.value) }}</div>
        <div class="text-body-medium">{{ t.label }}</div>
        <div v-if="t.note" class="text-body-small text-medium-emphasis">{{ t.note }}</div>
      </v-card>
    </div>

    <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2 mt-4">
      <v-card-item title="Serverkonfiguration">
        <v-card-subtitle class="text-wrap">Wird über Umgebungsvariablen gesetzt und gilt nach einem Neustart.</v-card-subtitle>
      </v-card-item>
      <v-list density="compact" bg-color="transparent">
        <v-list-item title="Adresse" :subtitle="data.config.bind">
          <template #append><code>BIND_ADDR</code></template>
        </v-list-item>
        <v-list-item title="Datenverzeichnis" :subtitle="data.config.data_dir">
          <template #append><code>DATA_DIR</code></template>
        </v-list-item>
        <v-list-item title="Erlaubte Origins" :subtitle="data.config.cors_origins.join(', ') || '–'">
          <template #append><code>CORS_ORIGINS</code></template>
        </v-list-item>
        <v-list-item title="Kontakt für Push-Dienste" :subtitle="data.config.vapid_subject">
          <template #append><code>VAPID_SUBJECT</code></template>
        </v-list-item>
      </v-list>
      <v-alert
        v-if="data.config.vapid_subject.endsWith('@example.org')" type="info" variant="tonal" density="compact"
        class="ma-2"
      >
        Einige Push-Dienste verlangen eine echte Kontaktadresse in <code>VAPID_SUBJECT</code>.
      </v-alert>
    </v-card>
  </template>
</template>

<style scoped>
.tiles {
  display: grid; gap: 12px; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
}
code { font-size: 12px; opacity: .7; }
</style>
