<script setup lang="ts">
import { t } from '@/i18n'
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
  if (mins < 60) return t('overview.minutes', { n: mins })
  const h = Math.floor(mins / 60)
  if (h < 48) return t('overview.hours', { n: h })
  return t('overview.days', { n: Math.floor(h / 24) })
}

const tiles = computed(() => {
  const c = data.value?.counts
  if (!c) return []
  return [
    {
      icon: 'mdi-account-multiple-outline', label: t('overview.users'), value: c.users,
      note: c.disabled_users ? t('overview.disabled', { n: c.disabled_users }) : '',
    },
    { icon: 'mdi-calendar-multiple', label: t('overview.calendars'), value: c.calendars, note: '' },
    { icon: 'mdi-calendar-text-outline', label: t('overview.events'), value: c.events, note: t('overview.withoutBirthdays') },
    { icon: 'mdi-book-account-outline', label: t('overview.addressBooks'), value: c.addressbooks, note: '' },
    { icon: 'mdi-account-box-multiple-outline', label: t('overview.contacts'), value: c.contacts, note: '' },
    { icon: 'mdi-checkbox-marked-circle-outline', label: t('overview.tasks'), value: c.tasks, note: '' },
    { icon: 'mdi-bell-outline', label: t('overview.pushBrowsers'), value: c.push_subscriptions, note: '' },
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
          {{ $t('overview.runningSince', { duration: since(data.started_at) }) }} ·
          {{ $t('overview.database', { size: formatBytes(data.database_size) }) }}
        </v-card-subtitle>
        <template #append>
          <v-btn icon="mdi-refresh" variant="text" :title="$t('common.refresh')" :loading="loading" @click="load" />
        </template>
      </v-card-item>
    </v-card>

    <div class="tiles mt-4">
      <v-card v-for="tile in tiles" :key="tile.label" variant="flat" color="surface-container-low" rounded="xl" class="pa-4">
        <v-icon :icon="tile.icon" color="primary" class="mb-2" />
        <div class="text-headline-small">{{ nf.format(tile.value) }}</div>
        <div class="text-body-medium">{{ tile.label }}</div>
        <div v-if="tile.note" class="text-body-small text-medium-emphasis">{{ tile.note }}</div>
      </v-card>
    </div>

    <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2 mt-4">
      <v-card-item :title="$t('overview.config')">
        <v-card-subtitle class="text-wrap">{{ $t('overview.configHint') }}</v-card-subtitle>
      </v-card-item>
      <v-list density="compact" bg-color="transparent">
        <v-list-item :title="$t('overview.bind')" :subtitle="data.config.bind">
          <template #append><code>BIND_ADDR</code></template>
        </v-list-item>
        <v-list-item :title="$t('overview.dataDir')" :subtitle="data.config.data_dir">
          <template #append><code>DATA_DIR</code></template>
        </v-list-item>
        <v-list-item :title="$t('overview.corsOrigins')" :subtitle="data.config.cors_origins.join(', ') || '–'">
          <template #append><code>CORS_ORIGINS</code></template>
        </v-list-item>
        <v-list-item :title="$t('overview.vapidSubject')" :subtitle="data.config.vapid_subject">
          <template #append><code>VAPID_SUBJECT</code></template>
        </v-list-item>
      </v-list>
      <v-alert
        v-if="data.config.vapid_subject.endsWith('@example.org')" type="info" variant="tonal" density="compact"
        class="ma-2"
      >
        <i18n-t keypath="overview.vapidHint"><template #variable><code>VAPID_SUBJECT</code></template></i18n-t>
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
