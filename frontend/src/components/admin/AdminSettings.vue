<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { AdminSettings, AdminSettingsValues } from '@/api/types'
import { useNotifyStore } from '@/stores/notify'

const notify = useNotifyStore()
const settings = ref<AdminSettings | null>(null)
const ttl = ref<number | string>(30)
const saving = ref<keyof AdminSettingsValues | null>(null)

const ttlPresets = [
  { title: '1 Tag', value: 1 },
  { title: '7 Tage', value: 7 },
  { title: '30 Tage', value: 30 },
  { title: '90 Tage', value: 90 },
  { title: '1 Jahr', value: 365 },
]

onMounted(async () => {
  try {
    settings.value = await api.adminSettings()
    ttl.value = settings.value.token_ttl_days
  } catch (e) {
    notify.fail(e)
  }
})

async function save(data: Partial<AdminSettingsValues>) {
  saving.value = Object.keys(data)[0] as keyof AdminSettingsValues
  try {
    settings.value = await api.updateAdminSettings(data)
    ttl.value = settings.value.token_ttl_days
    notify.info('Einstellung gespeichert')
  } catch (e) {
    notify.fail(e)
  } finally {
    saving.value = null
  }
}

const ttlValid = computed(() => {
  const n = Number(ttl.value)
  return Number.isInteger(n) && n >= 1 && n <= 3650
})
const ttlChanged = computed(() => ttlValid.value && Number(ttl.value) !== settings.value?.token_ttl_days)

const yesNo = (b: boolean) => (b ? 'erlaubt' : 'geschlossen')
</script>

<template>
  <div v-if="!settings" class="d-flex justify-center pa-8"><v-progress-circular indeterminate color="primary" /></div>
  <template v-else>
    <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2">
      <v-card-item title="Registrierung" />
      <v-card-text>
        <v-switch
          :model-value="settings.allow_registration" label="Neue Konten dürfen sich registrieren" color="primary" inset
          hide-details :loading="saving === 'allow_registration'" :disabled="!!saving"
          @update:model-value="(v) => save({ allow_registration: !!v })"
        />
        <div class="text-body-small text-medium-emphasis">
          Ist die Registrierung geschlossen, legst du neue Konten unter <em>Benutzer</em> an. Für den Betrieb im
          Internet empfiehlt es sich, sie nach dem Anlegen der eigenen Konten zu schließen.
          Standard aus <code>ALLOW_REGISTRATION</code>: {{ yesNo(settings.defaults.allow_registration) }}.
        </div>
      </v-card-text>
    </v-card>

    <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2 mt-4">
      <v-card-item title="Gültigkeit der Anmeldung" />
      <v-card-text>
        <div class="d-flex align-start ga-2">
          <v-combobox
            v-model="ttl" :items="ttlPresets" :return-object="false" label="Tage" type="number" min="1" max="3650"
            :rules="[() => ttlValid || '1 bis 3650 Tage']" class="ttl" @keydown.enter="ttlChanged && save({ token_ttl_days: Number(ttl) })"
          />
          <v-btn
            color="primary" variant="flat" class="mt-2" :disabled="!ttlChanged || !!saving"
            :loading="saving === 'token_ttl_days'" @click="save({ token_ttl_days: Number(ttl) })"
          >
            Speichern
          </v-btn>
        </div>
        <div class="text-body-small text-medium-emphasis">
          So lange bleiben Weboberfläche und App angemeldet; die App verlängert die Anmeldung beim Synchronisieren.
          Gilt für neue Anmeldungen. Standard aus <code>TOKEN_TTL_DAYS</code>: {{ settings.defaults.token_ttl_days }} Tage.
        </div>
      </v-card-text>
    </v-card>
  </template>
</template>

<style scoped>
.ttl { max-width: 200px; }
</style>
