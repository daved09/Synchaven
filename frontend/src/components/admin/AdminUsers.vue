<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { AdminUser } from '@/api/types'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import AdminUserDialog from '@/components/admin/AdminUserDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'
import { LOCALE } from '@/utils/date'

const auth = useAuthStore()
const notify = useNotifyStore()
const confirmRef = ref<InstanceType<typeof ConfirmDialog>>()

const users = ref<AdminUser[]>([])
const loading = ref(false)
const search = ref('')
const dialogOpen = ref(false)
const editing = ref<AdminUser | null>(null)
const busyId = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    users.value = await api.adminUsers()
  } catch (e) {
    notify.fail(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  const q = search.value?.trim().toLowerCase()
  if (!q) return users.value
  return users.value.filter((u) => u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q))
})

const isOwn = (u: AdminUser) => u.id === auth.user?.id

function initials(u: AdminUser) {
  return (u.name || u.email).split(/[\s@.]+/).filter(Boolean).slice(0, 2).map((s) => s[0].toUpperCase()).join('')
}

const dateFmt = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium' })
const dateTimeFmt = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'short' })

function stats(u: AdminUser) {
  const parts = [
    `${u.calendars} Kalender`,
    `${u.events} ${u.events === 1 ? 'Termin' : 'Termine'}`,
    `${u.contacts} ${u.contacts === 1 ? 'Kontakt' : 'Kontakte'}`,
    `${u.tasks} ${u.tasks === 1 ? 'Aufgabe' : 'Aufgaben'}`,
  ]
  return parts.join(' · ')
}

function replace(u: AdminUser) {
  const i = users.value.findIndex((x) => x.id === u.id)
  if (i >= 0) users.value[i] = u
  else users.value.push(u)
  // Eigene Änderungen (Name, E-Mail) sofort im Kontomenü zeigen
  if (isOwn(u)) auth.fetchMe().catch(() => {})
}

function newUser() {
  editing.value = null
  dialogOpen.value = true
}
function edit(u: AdminUser) {
  editing.value = u
  dialogOpen.value = true
}

async function run(u: AdminUser, action: () => Promise<AdminUser | void>, message: string) {
  busyId.value = u.id
  try {
    const r = await action()
    if (r) replace(r)
    notify.info(message)
  } catch (e) {
    notify.fail(e)
  } finally {
    busyId.value = null
  }
}

async function toggleDisabled(u: AdminUser) {
  if (!u.disabled) {
    const ok = await confirmRef.value!.ask(
      'Konto sperren?',
      `${u.name} (${u.email}) wird sofort abgemeldet und kann sich nicht mehr anmelden oder synchronisieren, bis `
        + 'du das Konto wieder entsperrst. Die Daten bleiben erhalten.',
      'Sperren',
    )
    if (!ok) return
  }
  await run(u, () => api.updateAdminUser(u.id, { disabled: !u.disabled }), u.disabled ? 'Konto entsperrt' : 'Konto gesperrt')
}

async function logoutEverywhere(u: AdminUser) {
  const ok = await confirmRef.value!.ask(
    'Überall abmelden?',
    `${u.name} muss sich in der Weboberfläche und der App neu anmelden. CardDAV-Clients wie DAVx⁵ nutzen das Passwort `
      + 'und bleiben verbunden – dafür das Passwort ändern.',
    'Abmelden', 'primary',
  )
  if (ok) await run(u, () => api.logoutAdminUser(u.id), `${u.name} wurde überall abgemeldet`)
}

async function remove(u: AdminUser) {
  const ok = await confirmRef.value!.ask(
    'Konto löschen?',
    `${u.name} (${u.email}) wird mit allen Kalendern, Terminen, Kontakten und Aufgaben gelöscht `
      + `(${stats(u)}). Das lässt sich nicht rückgängig machen.`,
  )
  if (!ok) return
  busyId.value = u.id
  try {
    await api.deleteAdminUser(u.id)
    users.value = users.value.filter((x) => x.id !== u.id)
    notify.info('Konto gelöscht')
  } catch (e) {
    notify.fail(e)
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <div class="d-flex align-center ga-2 mb-3">
    <v-text-field
      v-model="search" placeholder="Benutzer suchen" density="compact" variant="solo-filled" flat rounded="xl"
      hide-details clearable prepend-inner-icon="mdi-magnify" bg-color="surface-container-high" class="flex-grow-1"
    />
    <v-btn color="primary" variant="flat" prepend-icon="mdi-account-plus-outline" @click="newUser">Neues Konto</v-btn>
  </div>

  <v-card variant="flat" color="surface-container-low" rounded="xl">
    <v-progress-linear v-if="loading" indeterminate color="primary" />
    <v-list bg-color="transparent" lines="three">
      <template v-for="(u, i) in filtered" :key="u.id">
        <v-divider v-if="i" inset />
        <v-list-item :class="{ disabled: u.disabled }" @click="edit(u)">
          <template #prepend>
            <v-avatar :color="u.disabled ? 'surface-variant' : 'primary-container'" size="40">
              <v-icon v-if="u.disabled" icon="mdi-account-cancel-outline" />
              <span v-else class="text-label-large">{{ initials(u) }}</span>
            </v-avatar>
          </template>
          <v-list-item-title class="d-flex align-center flex-wrap ga-1">
            <span class="mr-1">{{ u.name }}</span>
            <v-chip v-if="isOwn(u)" size="x-small" variant="tonal">Du</v-chip>
            <v-chip v-if="u.is_admin" size="x-small" color="primary" variant="tonal" prepend-icon="mdi-shield-account-outline">
              Administrator
            </v-chip>
            <v-chip v-if="u.disabled" size="x-small" color="error" variant="tonal" prepend-icon="mdi-lock-outline">Gesperrt</v-chip>
          </v-list-item-title>
          <v-list-item-subtitle>{{ u.email }} · {{ stats(u) }}</v-list-item-subtitle>
          <v-list-item-subtitle>
            Seit {{ dateFmt.format(new Date(u.created_at)) }} ·
            {{ u.last_login_at ? `zuletzt angemeldet ${dateTimeFmt.format(new Date(u.last_login_at))}` : 'noch nicht angemeldet' }}
            <template v-if="u.devices"> · Benachrichtigungen in {{ u.devices }} {{ u.devices === 1 ? 'Browser' : 'Browsern' }}</template>
          </v-list-item-subtitle>
          <template #append>
            <v-menu location="bottom end">
              <template #activator="{ props: p }">
                <v-btn v-bind="p" icon="mdi-dots-vertical" variant="text" :loading="busyId === u.id" title="Aktionen" @click.stop />
              </template>
              <v-list density="compact" rounded="lg">
                <v-list-item prepend-icon="mdi-pencil-outline" title="Bearbeiten" @click="edit(u)" />
                <template v-if="!isOwn(u)">
                  <v-list-item prepend-icon="mdi-logout" title="Überall abmelden" @click="logoutEverywhere(u)" />
                  <v-list-item
                    :prepend-icon="u.disabled ? 'mdi-lock-open-variant-outline' : 'mdi-lock-outline'"
                    :title="u.disabled ? 'Entsperren' : 'Sperren'" @click="toggleDisabled(u)"
                  />
                  <v-divider />
                  <v-list-item prepend-icon="mdi-delete-outline" title="Löschen" base-color="error" @click="remove(u)" />
                </template>
              </v-list>
            </v-menu>
          </template>
        </v-list-item>
      </template>
      <v-list-item v-if="!loading && !filtered.length" class="text-medium-emphasis text-center">Keine Benutzer gefunden</v-list-item>
    </v-list>
  </v-card>

  <AdminUserDialog v-model="dialogOpen" :user="editing" :own-id="auth.user?.id" @saved="replace" />
  <ConfirmDialog ref="confirmRef" />
</template>

<style scoped>
.disabled :deep(.v-list-item-title > span) { text-decoration: line-through; opacity: .7; }
</style>
