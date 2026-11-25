<script setup lang="ts">
import { t } from '@/i18n'
import { ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { DbImportReport } from '@/api/types'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'

const notify = useNotifyStore()
const auth = useAuthStore()
const confirmRef = ref<InstanceType<typeof ConfirmDialog>>()
const fileInput = ref<HTMLInputElement>()
const busy = ref<'export' | 'import' | null>(null)
const report = ref<DbImportReport | null>(null)

/** The export contains every password hash and two-factor secret: it needs the password (and code) again */
const exportOpen = ref(false)
const exportPassword = ref('')
const exportCode = ref('')
const exportError = ref('')
const exportForm = ref()
const required = (v: string) => !!v.trim() || t('common.required')

function askExport() {
  exportPassword.value = ''
  exportCode.value = ''
  exportError.value = ''
  exportOpen.value = true
}

async function exportDb() {
  if (!(await exportForm.value?.validate())?.valid) return
  busy.value = 'export'
  exportError.value = ''
  try {
    const json = await api.adminExport(exportPassword.value, auth.user?.totp_enabled ? exportCode.value.trim() : undefined)
    exportOpen.value = false
    const url = URL.createObjectURL(new Blob([json], { type: 'application/json' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `synchaven-export-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    // Wrong password or code: show it in the dialog, so it can be corrected right there
    if (e instanceof ApiError && e.status === 401) exportError.value = e.message
    else notify.fail(e)
  } finally {
    busy.value = null
  }
}

function pickFile() {
  fileInput.value!.value = ''
  fileInput.value!.click()
}

async function onFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  const ok = await confirmRef.value!.ask(
    t('backup.replaceTitle'),
    t('backup.replaceText', { file: file.name }),
    t('backup.replace'),
  )
  if (!ok) return
  busy.value = 'import'
  try {
    report.value = await api.adminImport(await file.text())
  } catch (err) {
    notify.fail(err)
  } finally {
    busy.value = null
  }
}

/** All loaded data is outdated – simplest to reload the app */
function reload() {
  location.reload()
}
</script>

<template>
  <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2">
    <v-card-item :title="$t('backup.title')">
      <v-card-subtitle class="text-wrap">
        {{ $t('backup.intro') }}
      </v-card-subtitle>
    </v-card-item>
    <v-card-text>
      <div class="d-flex flex-wrap ga-2">
        <v-btn
          variant="tonal" prepend-icon="mdi-database-export-outline" :loading="busy === 'export'"
          :disabled="!!busy" @click="askExport"
        >
          {{ $t('backup.export') }}
        </v-btn>
        <v-btn
          variant="tonal" color="error" prepend-icon="mdi-database-import-outline"
          :loading="busy === 'import'" :disabled="!!busy" @click="pickFile"
        >
          {{ $t('backup.import') }}
        </v-btn>
      </div>
      <input ref="fileInput" type="file" accept=".json,application/json" hidden @change="onFile" />

      <v-alert v-if="report" type="success" variant="tonal" class="mt-4" density="compact">
        <div>{{ $t('backup.done', { summary: report.summary.join(', ') }) }}</div>
        <div v-for="w in report.warnings" :key="w" class="text-body-small mt-1">⚠ {{ w }}</div>
        <v-btn size="small" variant="text" class="mt-1" prepend-icon="mdi-refresh" @click="reload">{{ $t('backup.reload') }}</v-btn>
      </v-alert>
    </v-card-text>
  </v-card>

  <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2 mt-4">
    <v-card-item :title="$t('backup.cliTitle')">
      <v-card-subtitle class="text-wrap">{{ $t('backup.cliHint') }}</v-card-subtitle>
    </v-card-item>
    <v-card-text>
      <code class="cli">(umask 077 &amp;&amp; docker exec synchaven synchaven export &gt; synchaven-export.json)</code>
    </v-card-text>
  </v-card>

  <v-dialog v-model="exportOpen" max-width="420">
    <v-card :title="$t('backup.exportConfirmTitle')" color="surface-container-high">
      <v-card-text>
        <p class="text-body-medium mb-4">{{ $t('backup.exportConfirmText') }}</p>
        <v-form ref="exportForm" @submit.prevent="exportDb">
          <v-text-field
            v-model="exportPassword" :label="$t('profile.currentPassword')" type="password" autocomplete="current-password"
            prepend-inner-icon="mdi-lock-outline" :rules="[required]" autofocus
          />
          <v-text-field
            v-if="auth.user?.totp_enabled"
            v-model="exportCode" :label="$t('auth.code')" autocomplete="one-time-code" prepend-inner-icon="mdi-shield-key-outline"
            :rules="[required]" :hint="$t('auth.codeHint')" persistent-hint
          />
          <v-alert v-if="exportError" type="error" variant="tonal" density="compact" class="mt-2">{{ exportError }}</v-alert>
          <button type="submit" hidden />
        </v-form>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn :disabled="busy === 'export'" @click="exportOpen = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" :loading="busy === 'export'" @click="exportDb">{{ $t('backup.export') }}</v-btn>
      </template>
    </v-card>
  </v-dialog>

  <ConfirmDialog ref="confirmRef" />
</template>

<style scoped>
.cli {
  display: block; padding: 10px 14px; border-radius: 12px; font-size: 13px; overflow-x: auto; white-space: nowrap;
  background: rgb(var(--v-theme-surface-container-highest));
}
</style>
