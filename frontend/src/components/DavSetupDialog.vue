<script setup lang="ts">
import { t } from '@/i18n'
import { computed, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'
import SecurityDialog from '@/components/SecurityDialog.vue'

const open = defineModel<boolean>({ required: true })
const auth = useAuthStore()
const notify = useNotifyStore()
const securityOpen = ref(false)

const url = computed(() => `${window.location.origin}/dav/`)
const insecure = window.location.protocol !== 'https:' && !['localhost', '127.0.0.1'].includes(window.location.hostname)

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    notify.info(t('dav.copied'))
  } catch {
    notify.fail(new Error(t('dav.copyFailed')))
  }
}
</script>

<template>
  <v-dialog v-model="open" max-width="560" scrollable>
    <v-card :title="$t('dav.title')" color="surface-container-high">
      <v-card-text>
        <p class="text-body-medium mb-4">
          {{ $t('dav.intro') }}
        </p>

        <v-text-field :model-value="url" :label="$t('dav.baseUrl')" readonly variant="outlined" class="mb-1">
          <template #append-inner>
            <v-btn icon="mdi-content-copy" size="small" variant="text" :title="$t('dav.copy')" @click="copy(url)" />
          </template>
        </v-text-field>
        <v-text-field :model-value="auth.user?.email" :label="$t('dav.username')" readonly variant="outlined" class="mb-1">
          <template #append-inner>
            <v-btn icon="mdi-content-copy" size="small" variant="text" :title="$t('dav.copy')" @click="copy(auth.user?.email ?? '')" />
          </template>
        </v-text-field>
        <v-text-field
          :model-value="auth.user?.totp_enabled ? $t('dav.appPassword') : $t('dav.yourPassword')" :label="$t('common.password')"
          readonly variant="outlined" disabled class="mb-1"
        />
        <div class="d-flex align-center ga-2 mb-4">
          <span class="text-body-small text-medium-emphasis flex-grow-1">
            {{ auth.user?.totp_enabled ? $t('dav.appPasswordRequired') : $t('dav.appPasswordHint') }}
          </span>
          <v-btn variant="tonal" size="small" prepend-icon="mdi-key-outline" @click="securityOpen = true">{{ $t('dav.appPasswords') }}</v-btn>
        </div>

        <v-alert v-if="insecure" type="warning" variant="tonal" density="compact" class="mb-4">
          {{ $t('dav.insecure') }}
        </v-alert>

        <div class="text-title-small mb-2">{{ $t('dav.howTo') }}</div>
        <ol class="text-body-medium steps">
          <i18n-t keypath="dav.step1" tag="li"><template #plus><b>+</b></template></i18n-t>
          <i18n-t keypath="dav.step2" tag="li"><template #option><b>{{ $t('dav.step2Option') }}</b></template></i18n-t>
          <i18n-t keypath="dav.step3" tag="li"><template #login><b>{{ $t('dav.step3Login') }}</b></template></i18n-t>
          <i18n-t keypath="dav.step4" tag="li"><template #create><b>{{ $t('dav.step4Create') }}</b></template></i18n-t>
          <i18n-t keypath="dav.step5" tag="li"><template #tab><b>CardDAV</b></template></i18n-t>
        </ol>
        <p class="text-body-small text-medium-emphasis mt-3">
          {{ $t('dav.otherClients') }}
        </p>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">{{ $t('common.done') }}</v-btn>
      </template>
    </v-card>
  </v-dialog>
  <SecurityDialog v-model="securityOpen" />
</template>

<style scoped>
.steps { padding-left: 20px; }
.steps li { margin-bottom: 4px; }
</style>
