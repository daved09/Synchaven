<script setup lang="ts">
import { t } from '@/i18n'
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { AppPassword, TotpSetup, TotpStatus } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useConfirmStore } from '@/stores/confirm'
import { useNotifyStore } from '@/stores/notify'
import { fmt } from '@/utils/date'

const open = defineModel<boolean>({ required: true })
const auth = useAuthStore()
const notify = useNotifyStore()
const confirm = useConfirmStore()

/**
 * What the dialog shows: the overview, or one step of a change
 * - setup: password → scan the QR code and confirm a code → recovery codes
 * - disable / regenerate: password and code
 * - appPassword: name and password → the new app password
 */
type Mode = 'overview' | 'setupPassword' | 'setupScan' | 'recoveryCodes' | 'disable' | 'regenerate' | 'appPassword' | 'appPasswordCreated'
const mode = ref<Mode>('overview')
const status = ref<TotpStatus | null>(null)
const appPasswords = ref<AppPassword[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')

const currentPassword = ref('')
const code = ref('')
const setup = ref<TotpSetup | null>(null)
const recoveryCodes = ref<string[]>([])
const appPasswordName = ref('')
const createdAppPassword = ref<AppPassword | null>(null)
const formRef = ref()

async function load() {
  loading.value = true
  try {
    ;[status.value, appPasswords.value] = await Promise.all([api.totpStatus(), api.appPasswords()])
  } catch (e) {
    notify.fail(e)
  } finally {
    loading.value = false
  }
}

watch(open, (v) => {
  if (!v) return
  go('overview')
  load()
})

function go(m: Mode) {
  mode.value = m
  error.value = ''
  currentPassword.value = ''
  code.value = ''
  if (m === 'overview') {
    setup.value = null
    recoveryCodes.value = []
    createdAppPassword.value = null
  }
  if (m === 'appPassword') appPasswordName.value = ''
}

/** Runs a step; errors appear in the dialog, not as a snackbar, so the input stays visible */
async function step(fn: () => Promise<void>) {
  if (formRef.value) {
    const { valid } = await formRef.value.validate()
    if (!valid) return
  }
  busy.value = true
  error.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('errors.generic')
    code.value = ''
  } finally {
    busy.value = false
  }
}

const startSetup = () => step(async () => {
  setup.value = await api.totpSetup(currentPassword.value)
  mode.value = 'setupScan'
  error.value = ''
})

const confirmSetup = () => step(async () => {
  recoveryCodes.value = (await api.totpEnable(code.value)).recovery_codes
  mode.value = 'recoveryCodes'
  await Promise.all([auth.fetchMe(), load()])
  notify.info(t('security.enabled'))
})

const disable = () => step(async () => {
  await api.totpDisable(currentPassword.value, code.value)
  await Promise.all([auth.fetchMe(), load()])
  go('overview')
  notify.info(t('security.disabled'))
})

const regenerate = () => step(async () => {
  recoveryCodes.value = (await api.totpRecoveryCodes(currentPassword.value, code.value)).recovery_codes
  mode.value = 'recoveryCodes'
  await load()
})

const createAppPassword = () => step(async () => {
  createdAppPassword.value = await api.createAppPassword(appPasswordName.value, currentPassword.value)
  mode.value = 'appPasswordCreated'
  await load()
})

async function deleteAppPassword(p: AppPassword) {
  if (!(await confirm.ask(t('security.revokeTitle'), t('security.revokeText', { name: p.name }), t('security.revoke')))) return
  try {
    await api.deleteAppPassword(p.id)
    appPasswords.value = appPasswords.value.filter((x) => x.id !== p.id)
    notify.info(t('security.revoked'))
  } catch (e) {
    notify.fail(e)
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    notify.info(t('dav.copied'))
  } catch {
    notify.fail(new Error(t('dav.copyFailed')))
  }
}

function downloadCodes() {
  const text = `${t('security.recoveryFileHeader', { email: auth.user?.email ?? '' })}\n\n${recoveryCodes.value.join('\n')}\n`
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' }))
  a.download = 'synchaven-recovery-codes.txt'
  a.click()
  URL.revokeObjectURL(a.href)
}

/** Secret in groups of four, easier to type */
const groupedSecret = computed(() => setup.value?.secret.match(/.{1,4}/g)?.join(' ') ?? '')
const date = (iso: string) => fmt(new Date(iso), { dateStyle: 'medium' })
const required = (v: string) => !!v?.trim() || t('common.required')

const title = computed(() => {
  switch (mode.value) {
    case 'setupPassword':
    case 'setupScan':
      return t('security.setupTitle')
    case 'recoveryCodes':
      return t('security.recoveryCodes')
    case 'disable':
      return t('security.disableTitle')
    case 'regenerate':
      return t('security.regenerateTitle')
    case 'appPassword':
    case 'appPasswordCreated':
      return t('security.newAppPassword')
    default:
      return t('security.title')
  }
})
</script>

<template>
  <v-dialog v-model="open" max-width="560" scrollable :persistent="mode === 'recoveryCodes' || mode === 'appPasswordCreated'">
    <v-card :title="title" color="surface-container-high">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <v-card-text>
        <!-- Overview -->
        <template v-if="mode === 'overview'">
          <div class="d-flex align-center mb-1">
            <div class="text-title-medium">{{ $t('security.twoFactor') }}</div>
            <v-spacer />
            <v-chip v-if="status" size="small" variant="tonal" :color="status.enabled ? 'primary' : undefined"
              :prepend-icon="status.enabled ? 'mdi-shield-check-outline' : 'mdi-shield-off-outline'">
              {{ status.enabled ? $t('security.on') : $t('security.off') }}
            </v-chip>
          </div>
          <p class="text-body-medium text-medium-emphasis mb-3">
            {{ status?.enabled ? $t('security.twoFactorIntroOn') : $t('security.twoFactorIntro') }}
          </p>
          <template v-if="status?.enabled">
            <p class="text-body-medium mb-3">
              {{ $t('security.recoveryCodesLeft', { n: status.recovery_codes_left }, status.recovery_codes_left) }}
            </p>
            <div class="d-flex flex-wrap ga-2 mb-6">
              <v-btn variant="tonal" prepend-icon="mdi-refresh" @click="go('regenerate')">{{ $t('security.regenerate') }}</v-btn>
              <v-btn variant="text" color="error" @click="go('disable')">{{ $t('security.disable') }}</v-btn>
            </div>
          </template>
          <v-btn v-else-if="status" color="primary" variant="flat" prepend-icon="mdi-shield-key-outline" class="mb-6"
            @click="go('setupPassword')">
            {{ $t('security.setup') }}
          </v-btn>

          <v-divider class="mb-4" />
          <div class="d-flex align-center mb-1">
            <div class="text-title-medium">{{ $t('security.appPasswords') }}</div>
            <v-spacer />
            <v-btn variant="tonal" size="small" prepend-icon="mdi-plus" @click="go('appPassword')">{{ $t('common.create') }}</v-btn>
          </div>
          <p class="text-body-medium text-medium-emphasis mb-2">
            {{ status?.enabled ? $t('security.appPasswordsIntroTotp') : $t('security.appPasswordsIntro') }}
          </p>
          <v-list v-if="appPasswords.length" bg-color="transparent" density="compact">
            <v-list-item v-for="p in appPasswords" :key="p.id" prepend-icon="mdi-key-outline" :title="p.name">
              <v-list-item-subtitle>
                {{ $t('security.createdOn', { date: date(p.created_at) }) }} ·
                {{ p.last_used_at ? $t('security.lastUsed', { date: date(p.last_used_at) }) : $t('security.neverUsed') }}
              </v-list-item-subtitle>
              <template #append>
                <v-btn icon="mdi-delete-outline" variant="text" size="small" :title="$t('security.revoke')" @click="deleteAppPassword(p)" />
              </template>
            </v-list-item>
          </v-list>
          <p v-else-if="!loading" class="text-body-small text-medium-emphasis">{{ $t('security.noAppPasswords') }}</p>
        </template>

        <!-- Setup, step 1: password -->
        <v-form v-else-if="mode === 'setupPassword'" ref="formRef" @submit.prevent="startSetup">
          <p class="text-body-medium mb-4">{{ $t('security.setupIntro') }}</p>
          <v-text-field
            v-model="currentPassword" :label="$t('profile.currentPassword')" type="password" autocomplete="current-password"
            prepend-inner-icon="mdi-lock-outline" :rules="[required]" autofocus
          />
          <button type="submit" hidden />
        </v-form>

        <!-- Setup, step 2: scan and confirm -->
        <v-form v-else-if="mode === 'setupScan' && setup" ref="formRef" @submit.prevent="confirmSetup">
          <p class="text-body-medium mb-3">{{ $t('security.scan') }}</p>
          <div class="d-flex justify-center mb-3">
            <img :src="setup.qr_code" :alt="$t('security.qrAlt')" width="200" height="200" class="qr">
          </div>
          <p class="text-body-small text-medium-emphasis mb-1">{{ $t('security.manualEntry') }}</p>
          <v-text-field :model-value="groupedSecret" readonly variant="outlined" density="compact" class="secret mb-1">
            <template #append-inner>
              <v-btn icon="mdi-content-copy" size="small" variant="text" :title="$t('dav.copy')" @click="copy(setup.secret)" />
            </template>
          </v-text-field>
          <v-btn :href="setup.otpauth_uri" variant="text" size="small" prepend-icon="mdi-open-in-app" class="mb-4 open-app">
            {{ $t('security.openInApp') }}
          </v-btn>
          <v-text-field
            v-model="code" :label="$t('auth.code')" autocomplete="one-time-code" inputmode="numeric" maxlength="7"
            prepend-inner-icon="mdi-shield-key-outline" :rules="[required]" :hint="$t('security.confirmHint')" persistent-hint autofocus
          />
          <button type="submit" hidden />
        </v-form>

        <!-- Recovery codes (after setup or regenerating) -->
        <template v-else-if="mode === 'recoveryCodes'">
          <v-alert type="warning" variant="tonal" density="compact" class="mb-4">{{ $t('security.recoveryCodesWarning') }}</v-alert>
          <div class="codes mb-4">
            <code v-for="c in recoveryCodes" :key="c">{{ c }}</code>
          </div>
          <div class="d-flex flex-wrap ga-2">
            <v-btn variant="tonal" prepend-icon="mdi-content-copy" @click="copy(recoveryCodes.join('\n'))">{{ $t('dav.copy') }}</v-btn>
            <v-btn variant="tonal" prepend-icon="mdi-download" @click="downloadCodes">{{ $t('common.download') }}</v-btn>
          </div>
        </template>

        <!-- Disable / new recovery codes: password and code -->
        <v-form v-else-if="mode === 'disable' || mode === 'regenerate'" ref="formRef" @submit.prevent="mode === 'disable' ? disable() : regenerate()">
          <p class="text-body-medium mb-4">
            {{ mode === 'disable' ? $t('security.disableText') : $t('security.regenerateText') }}
          </p>
          <v-text-field
            v-model="currentPassword" :label="$t('profile.currentPassword')" type="password" autocomplete="current-password"
            prepend-inner-icon="mdi-lock-outline" :rules="[required]" autofocus
          />
          <v-text-field
            v-model="code" :label="$t('auth.code')" autocomplete="one-time-code" prepend-inner-icon="mdi-shield-key-outline"
            :rules="[required]" :hint="$t('auth.codeHint')" persistent-hint
          />
          <button type="submit" hidden />
        </v-form>

        <!-- New app password -->
        <v-form v-else-if="mode === 'appPassword'" ref="formRef" @submit.prevent="createAppPassword">
          <p class="text-body-medium mb-4">{{ $t('security.newAppPasswordIntro') }}</p>
          <v-text-field
            v-model="appPasswordName" :label="$t('common.name')" :placeholder="$t('security.appPasswordNamePlaceholder')"
            prepend-inner-icon="mdi-cellphone" :rules="[required]" maxlength="100" autofocus
          />
          <v-text-field
            v-model="currentPassword" :label="$t('profile.currentPassword')" type="password" autocomplete="current-password"
            prepend-inner-icon="mdi-lock-outline" :rules="[required]"
          />
          <button type="submit" hidden />
        </v-form>

        <template v-else-if="mode === 'appPasswordCreated' && createdAppPassword">
          <p class="text-body-medium mb-4">{{ $t('security.appPasswordCreated', { name: createdAppPassword.name }) }}</p>
          <v-text-field :model-value="createdAppPassword.password" readonly variant="outlined" class="secret">
            <template #append-inner>
              <v-btn icon="mdi-content-copy" size="small" variant="text" :title="$t('dav.copy')" @click="copy(createdAppPassword.password ?? '')" />
            </template>
          </v-text-field>
          <v-alert type="info" variant="tonal" density="compact">{{ $t('security.appPasswordOnce') }}</v-alert>
        </template>

        <v-alert v-if="error" type="error" variant="tonal" density="compact" class="mt-4">{{ error }}</v-alert>
      </v-card-text>

      <template #actions>
        <template v-if="mode === 'overview'">
          <v-spacer />
          <v-btn @click="open = false">{{ $t('common.close') }}</v-btn>
        </template>
        <template v-else-if="mode === 'recoveryCodes' || mode === 'appPasswordCreated'">
          <v-spacer />
          <v-btn color="primary" variant="flat" @click="go('overview')">{{ $t('common.done') }}</v-btn>
        </template>
        <template v-else>
          <v-spacer />
          <v-btn @click="go('overview')">{{ $t('common.cancel') }}</v-btn>
          <v-btn v-if="mode === 'setupPassword'" color="primary" variant="flat" :loading="busy" @click="startSetup">{{ $t('common.next') }}</v-btn>
          <v-btn v-else-if="mode === 'setupScan'" color="primary" variant="flat" :loading="busy" @click="confirmSetup">{{ $t('security.activate') }}</v-btn>
          <v-btn v-else-if="mode === 'disable'" color="error" variant="flat" :loading="busy" @click="disable">{{ $t('security.disable') }}</v-btn>
          <v-btn v-else-if="mode === 'regenerate'" color="primary" variant="flat" :loading="busy" @click="regenerate">{{ $t('security.regenerate') }}</v-btn>
          <v-btn v-else-if="mode === 'appPassword'" color="primary" variant="flat" :loading="busy" @click="createAppPassword">{{ $t('common.create') }}</v-btn>
        </template>
      </template>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.qr { border-radius: 12px; background: #fff; padding: 8px; box-sizing: content-box; }
.secret :deep(input) { font-family: ui-monospace, monospace; letter-spacing: .05em; }
.codes { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px 16px; }
.codes code { font-family: ui-monospace, monospace; font-size: 1rem; text-align: center; padding: 6px; border-radius: 8px;
  background: rgb(var(--v-theme-surface-container-highest)); }
/* The link opens an authenticator app – only phones have one */
@media (hover: hover) and (pointer: fine) { .open-app { display: none; } }
</style>
