<script setup lang="ts">
import { t } from '@/i18n'
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { AdminSettings, AdminSettingsValues } from '@/api/types'
import { useNotifyStore } from '@/stores/notify'

const notify = useNotifyStore()
const settings = ref<AdminSettings | null>(null)
const ttl = ref<number | string>(30)
const saving = ref<keyof AdminSettingsValues | null>(null)

const ttlPresets = [
  ...[1, 7, 30, 90].map((value) => ({ title: t('adminSettings.daysValue', value), value })),
  { title: t('adminSettings.oneYear'), value: 365 },
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
    notify.info(t('adminSettings.saved'))
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

const yesNo = (b: boolean) => (b ? t('adminSettings.open') : t('adminSettings.closed'))
</script>

<template>
  <div v-if="!settings" class="d-flex justify-center pa-8"><v-progress-circular indeterminate color="primary" /></div>
  <template v-else>
    <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2">
      <v-card-item :title="$t('adminSettings.registration')" />
      <v-card-text>
        <v-switch
          :model-value="settings.allow_registration" :label="$t('adminSettings.allowRegistration')" color="primary" inset
          hide-details :loading="saving === 'allow_registration'" :disabled="!!saving"
          @update:model-value="(v) => save({ allow_registration: !!v })"
        />
        <div class="text-body-small text-medium-emphasis">
          <i18n-t keypath="adminSettings.registrationHint">
            <template #users><em>{{ $t('admin.tabs.users') }}</em></template>
          </i18n-t>
          <i18n-t keypath="adminSettings.default">
            <template #variable><code>ALLOW_REGISTRATION</code></template>
            <template #value>{{ yesNo(settings.defaults.allow_registration) }}</template>
          </i18n-t>
        </div>
      </v-card-text>
    </v-card>

    <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2 mt-4">
      <v-card-item :title="$t('adminSettings.tokenTtl')" />
      <v-card-text>
        <div class="d-flex align-start ga-2">
          <v-combobox
            v-model="ttl" :items="ttlPresets" :return-object="false" :label="$t('adminSettings.days')" type="number" min="1" max="3650"
            :rules="[() => ttlValid || $t('adminSettings.ttlRange')]" class="ttl" @keydown.enter="ttlChanged && save({ token_ttl_days: Number(ttl) })"
          />
          <v-btn
            color="primary" variant="flat" class="mt-2" :disabled="!ttlChanged || !!saving"
            :loading="saving === 'token_ttl_days'" @click="save({ token_ttl_days: Number(ttl) })"
          >
            {{ $t('common.save') }}
          </v-btn>
        </div>
        <div class="text-body-small text-medium-emphasis">
          {{ $t('adminSettings.tokenTtlHint') }}
          <i18n-t keypath="adminSettings.default">
            <template #variable><code>TOKEN_TTL_DAYS</code></template>
            <template #value>{{ $t('adminSettings.daysValue', settings.defaults.token_ttl_days) }}</template>
          </i18n-t>
        </div>
      </v-card-text>
    </v-card>
  </template>
</template>

<style scoped>
.ttl { max-width: 200px; }
</style>
