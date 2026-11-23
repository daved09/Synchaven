<script setup lang="ts">
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
    'Datenbank ersetzen?',
    `Alle Daten aller Benutzer werden gelöscht und durch den Inhalt von „${file.name}“ ersetzt – auch Konten und `
      + 'Passwörter. Das lässt sich nicht rückgängig machen. Gibt es dein Konto im Export nicht, wirst du abgemeldet.',
    'Ersetzen',
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

/** Alle geladenen Daten sind veraltet – am einfachsten die App neu laden */
function reload() {
  location.reload()
}
</script>

<template>
  <div class="text-title-small mt-6 mb-1">Verwaltung</div>
  <div class="text-body-small text-medium-emphasis mb-2">
    Die ganze Datenbank mit allen Benutzern als JSON sichern, z. B. vor einem Update. Der Import ersetzt alle
    vorhandenen Daten.
  </div>
  <div class="d-flex flex-wrap ga-2">
    <v-btn
      variant="tonal" size="small" prepend-icon="mdi-database-export-outline" :loading="busy === 'export'"
      :disabled="!!busy" @click="exportDb"
    >
      Datenbank exportieren
    </v-btn>
    <v-btn
      variant="tonal" size="small" color="error" prepend-icon="mdi-database-import-outline"
      :loading="busy === 'import'" :disabled="!!busy" @click="pickFile"
    >
      Datenbank importieren
    </v-btn>
  </div>
  <input ref="fileInput" type="file" accept=".json,application/json" hidden @change="onFile" />

  <v-alert v-if="report" type="success" variant="tonal" class="mt-3" density="compact">
    <div>Import abgeschlossen: {{ report.summary.join(', ') }}</div>
    <div v-for="w in report.warnings" :key="w" class="text-body-small mt-1">⚠ {{ w }}</div>
    <v-btn size="small" variant="text" class="mt-1" prepend-icon="mdi-refresh" @click="reload">Neu laden</v-btn>
  </v-alert>

  <ConfirmDialog ref="confirmRef" />
</template>
