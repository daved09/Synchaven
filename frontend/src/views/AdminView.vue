<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDisplay } from 'vuetify'
import AccountMenu from '@/components/AccountMenu.vue'
import AdminBackup from '@/components/admin/AdminBackup.vue'
import AdminLogs from '@/components/admin/AdminLogs.vue'
import AdminOverview from '@/components/admin/AdminOverview.vue'
import AdminSettings from '@/components/admin/AdminSettings.vue'
import AdminUsers from '@/components/admin/AdminUsers.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'

const route = useRoute()
const router = useRouter()
const { smAndDown } = useDisplay()
const auth = useAuthStore()
const notify = useNotifyStore()

const tabs = [
  { value: 'overview', title: 'Übersicht', icon: 'mdi-view-dashboard-outline' },
  { value: 'users', title: 'Benutzer', icon: 'mdi-account-multiple-outline' },
  { value: 'settings', title: 'Einstellungen', icon: 'mdi-tune-variant' },
  { value: 'backup', title: 'Datensicherung', icon: 'mdi-database-outline' },
  { value: 'logs', title: 'Protokoll', icon: 'mdi-text-box-search-outline' },
] as const
type Tab = (typeof tabs)[number]['value']

// Tab in the URL (?tab=…), so reloading and "Back" work
const tab = computed<Tab>({
  get: () => (tabs.some((t) => t.value === route.query.tab) ? route.query.tab as Tab : 'overview'),
  set: (v) => router.replace({ query: v === 'overview' ? {} : { tab: v } }),
})

onMounted(async () => {
  try {
    await auth.fetchMe()
  } catch {
    return
  }
  if (!auth.user?.is_admin) {
    notify.fail(new Error('Die Verwaltung ist nur für Administratoren'))
    router.replace({ name: 'calendar' })
  }
})

function back() {
  if (window.history.state?.back) router.back()
  else router.push({ name: 'calendar' })
}
</script>

<template>
  <v-app-bar flat color="surface" density="comfortable">
    <v-btn icon="mdi-arrow-left" title="Zurück" @click="back" />
    <div class="d-flex align-center ml-1">
      <v-icon icon="mdi-shield-account-outline" color="primary" size="28" class="mr-2" />
      <span class="text-title-large">Verwaltung</span>
    </div>
    <v-spacer />
    <v-btn icon="mdi-calendar-month-outline" title="Kalender" :to="{ name: 'calendar' }" />
    <v-btn icon="mdi-account-box-multiple-outline" title="Kontakte" :to="{ name: 'contacts' }" />
    <AccountMenu />
  </v-app-bar>

  <v-main class="main">
    <div v-if="auth.user?.is_admin" class="content">
      <v-tabs v-model="tab" color="primary" :show-arrows="smAndDown" class="mb-4" :grow="smAndDown">
        <v-tab v-for="t in tabs" :key="t.value" :value="t.value" :prepend-icon="smAndDown ? undefined : t.icon">
          <v-icon v-if="smAndDown" :icon="t.icon" class="mr-1" size="18" />{{ t.title }}
        </v-tab>
      </v-tabs>
      <AdminOverview v-if="tab === 'overview'" />
      <AdminUsers v-else-if="tab === 'users'" />
      <AdminSettings v-else-if="tab === 'settings'" />
      <AdminBackup v-else-if="tab === 'backup'" />
      <AdminLogs v-else-if="tab === 'logs'" />
    </div>
    <div v-else class="d-flex justify-center pa-8"><v-progress-circular indeterminate color="primary" /></div>
  </v-main>
</template>

<style scoped>
.main { background: rgb(var(--v-theme-surface)); }
.content { max-width: 960px; margin: 0 auto; padding: 8px 16px 32px; }
</style>
