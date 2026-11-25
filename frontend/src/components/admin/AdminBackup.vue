<script setup lang="ts">
import { t } from '@/i18n'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { DbImportReport } from '@/api/types'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { useNotifyStore } from '@/stores/notify'

const notify = useNotifyStore()
const confirmRef = ref<InstanceType<typeof ConfirmDialog>>()
const fileInput = ref<HTMLInputElement>()
const busy = ref<'export' | 'import' | null>(null)
const report = ref<DbImportReport | null>(null)

async function exportDb() {
  busy.value = 'export'
  try {
    const json = await api.adminExport()
    const url = URL.createObjectURL(new Blob([json], { type: 'application/json' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `synchaven-export-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    notify.fail(e)
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
          :disabled="!!busy" @click="exportDb"
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
      <code class="cli">docker exec synchaven synchaven export &gt; synchaven-export.json</code>
    </v-card-text>
  </v-card>

  <ConfirmDialog ref="confirmRef" />
</template>

<style scoped>
.cli {
  display: block; padding: 10px 14px; border-radius: 12px; font-size: 13px; overflow-x: auto; white-space: nowrap;
  background: rgb(var(--v-theme-surface-container-highest));
}
</style>
