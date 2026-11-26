<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import ProfileDialog from '@/components/ProfileDialog.vue'
import SecurityDialog from '@/components/SecurityDialog.vue'
import SettingsDialog from '@/components/SettingsDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useCalendarsStore } from '@/stores/calendars'
import { useContactsStore } from '@/stores/contacts'
import { useEventsStore } from '@/stores/events'
import { useTasksStore } from '@/stores/tasks'
import { disablePush } from '@/utils/push'

const router = useRouter()
const auth = useAuthStore()
const calendars = useCalendarsStore()
const events = useEventsStore()
const contacts = useContactsStore()
const tasks = useTasksStore()

const profileOpen = ref(false)
const settingsOpen = ref(false)
const securityOpen = ref(false)

const initials = computed(() =>
  (auth.user?.name || auth.user?.email || '?').split(/[\s@.]+/).filter(Boolean).slice(0, 2).map((s) => s[0].toUpperCase()).join(''),
)

async function logout() {
  // This browser should no longer receive the account's reminders
  await Promise.race([disablePush(), new Promise((r) => setTimeout(r, 2000))])
  auth.logout()
  calendars.reset()
  events.reset()
  contacts.reset()
  tasks.reset()
  router.replace({ name: 'login' })
}
</script>

<template>
  <v-menu location="bottom end">
    <template #activator="{ props: p }">
      <v-btn v-bind="p" icon class="mr-2" :title="$t('account.title')">
        <v-avatar color="primary" size="34"><span class="text-label-large">{{ initials }}</span></v-avatar>
      </v-btn>
    </template>
    <v-card min-width="260" color="surface-container-high">
      <v-card-item :title="auth.user?.name" :subtitle="auth.user?.email" />
      <v-divider />
      <v-list density="compact" bg-color="transparent">
        <v-list-item prepend-icon="mdi-account-outline" :title="$t('profile.title')" @click="profileOpen = true" />
        <v-list-item prepend-icon="mdi-shield-lock-outline" :title="$t('security.title')" @click="securityOpen = true" />
        <v-list-item prepend-icon="mdi-cog-outline" :title="$t('settings.title')" @click="settingsOpen = true" />
        <v-list-item
          v-if="auth.user?.is_admin" prepend-icon="mdi-shield-account-outline" :title="$t('admin.title')" :to="{ name: 'admin' }"
        />
        <v-list-item prepend-icon="mdi-logout" :title="$t('account.signOut')" @click="logout" />
      </v-list>
    </v-card>
  </v-menu>
  <ProfileDialog v-model="profileOpen" />
  <SettingsDialog v-model="settingsOpen" />
  <SecurityDialog v-model="securityOpen" />
</template>
