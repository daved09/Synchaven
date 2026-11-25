<script setup lang="ts">
import { t } from '@/i18n'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { LogEntry, LogLevel } from '@/api/types'
import { LOCALE } from '@/utils/date'
import { useNotifyStore } from '@/stores/notify'

const POLL_MS = 3000

const notify = useNotifyStore()
const entries = ref<LogEntry[]>([])
const capacity = ref(2000)
const loaded = ref(false)
const loading = ref(false)
const live = ref(true)
/** Last automatic refresh failed (e.g. server restarting) */
const unreachable = ref(false)
const search = ref('')
const minLevel = ref<LogLevel>('TRACE')
const box = ref<HTMLElement | null>(null)
/** Server start of the loaded entries */
let startedAt = ''

const severity: Record<LogLevel, number> = { ERROR: 4, WARN: 3, INFO: 2, DEBUG: 1, TRACE: 0 }
const levelColor: Record<LogLevel, string> = {
  ERROR: 'error', WARN: 'warning', INFO: 'info', DEBUG: 'medium-emphasis', TRACE: 'medium-emphasis',
}
const levelFilters: { value: LogLevel; title: string }[] = [
  { value: 'TRACE', title: t('logs.levels.all') },
  { value: 'INFO', title: t('logs.levels.info') },
  { value: 'WARN', title: t('logs.levels.warnings') },
  { value: 'ERROR', title: t('logs.levels.errors') },
]

const lastId = () => entries.value.at(-1)?.id ?? 0

/** Scrolled to the end: new entries keep the view at the bottom */
function atBottom(): boolean {
  const el = box.value
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 24
}
function scrollToBottom() {
  if (box.value) box.value.scrollTop = box.value.scrollHeight
}

async function load(full = false, poll = false) {
  if (loading.value) return
  loading.value = true
  try {
    let res = await api.adminLogs(full ? 0 : lastId())
    // Server restarted in the meantime: ids start again, load everything
    const restarted = !full && res.started_at !== startedAt
    if (restarted) res = await api.adminLogs(0)
    startedAt = res.started_at
    capacity.value = res.capacity
    const stick = atBottom()
    if (full || restarted) {
      entries.value = res.entries
    } else if (res.entries.length) {
      entries.value = [...entries.value, ...res.entries].slice(-capacity.value)
    }
    loaded.value = true
    unreachable.value = false
    if (stick) {
      await nextTick()
      scrollToBottom()
    }
  } catch (e) {
    // While polling keep trying, e.g. during a server restart
    if (poll) unreachable.value = true
    else notify.fail(e)
  } finally {
    loading.value = false
  }
}

let timer: ReturnType<typeof setInterval> | undefined
function updateTimer() {
  clearInterval(timer)
  timer = live.value ? setInterval(() => { if (document.visibilityState === 'visible') load(false, true) }, POLL_MS) : undefined
}
watch(live, (v) => {
  updateTimer()
  if (v) load()
})
onMounted(async () => {
  await load(true)
  updateTimer()
})
onBeforeUnmount(() => clearInterval(timer))

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  const min = severity[minLevel.value]
  return entries.value.filter((e) =>
    severity[e.level] >= min && (!q || e.message.toLowerCase().includes(q) || e.target.toLowerCase().includes(q)))
})

const counts = computed(() => ({
  errors: entries.value.filter((e) => e.level === 'ERROR').length,
  warnings: entries.value.filter((e) => e.level === 'WARN').length,
}))

// Filter changes: show the newest matching entries
watch([search, minLevel], async () => {
  await nextTick()
  scrollToBottom()
})

const timeFmt = new Intl.DateTimeFormat(LOCALE, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
const dateFmt = new Intl.DateTimeFormat(LOCALE, { day: '2-digit', month: '2-digit' })
const today = new Date().toDateString()
function formatTime(iso: string): string {
  const d = new Date(iso)
  return d.toDateString() === today ? timeFmt.format(d) : `${dateFmt.format(d)} ${timeFmt.format(d)}`
}
/** "synchaven::routes::admin" → "routes::admin" */
function shortTarget(t: string): string {
  return t.replace(/^synchaven::/, '')
}

function download() {
  const text = entries.value
    .map((e) => `${e.time} ${e.level.padEnd(5)} ${e.target}: ${e.message}`)
    .join('\n')
  const url = URL.createObjectURL(new Blob([text + '\n'], { type: 'text/plain' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `synchaven-${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.log`
  a.click()
  URL.revokeObjectURL(url)
}

async function clear() {
  try {
    await api.clearAdminLogs()
    entries.value = []
    notify.info(t('logs.cleared'))
  } catch (e) {
    notify.fail(e)
  }
}
</script>

<template>
  <v-card variant="flat" color="surface-container-low" rounded="xl" class="pa-2">
    <v-card-item>
      <v-card-title>{{ $t('admin.tabs.logs') }}</v-card-title>
      <v-card-subtitle class="text-wrap">
        <i18n-t keypath="logs.subtitle">
          <template #count>{{ capacity.toLocaleString(LOCALE) }}</template>
          <template #variable><code>RUST_LOG</code></template>
        </i18n-t><template v-if="counts.errors || counts.warnings"> ·
          <span v-if="counts.errors" class="text-error">{{ $t('logs.errorCount', counts.errors) }}</span>
          <template v-if="counts.errors && counts.warnings">, </template>
          <span v-if="counts.warnings" class="text-warning">{{ $t('logs.warningCount', counts.warnings) }}</span>
        </template>
      </v-card-subtitle>
      <template #append>
        <v-btn icon="mdi-refresh" variant="text" :title="$t('common.refresh')" :loading="loading && !live" @click="load()" />
        <v-btn icon="mdi-download-outline" variant="text" :title="$t('common.download')" :disabled="!entries.length" @click="download" />
        <v-btn icon="mdi-delete-sweep-outline" variant="text" :title="$t('logs.clear')" :disabled="!entries.length" @click="clear" />
      </template>
    </v-card-item>

    <div class="d-flex flex-wrap align-center ga-3 px-4 pb-3">
      <v-btn-toggle v-model="minLevel" mandatory density="compact" variant="outlined" divided rounded="lg" color="primary" class="levels">
        <v-btn v-for="f in levelFilters" :key="f.value" :value="f.value" size="small">{{ f.title }}</v-btn>
      </v-btn-toggle>
      <v-text-field
        v-model="search" :placeholder="$t('common.search')" prepend-inner-icon="mdi-magnify" density="compact" variant="outlined"
        hide-details clearable class="search"
      />
      <v-switch v-model="live" label="Live" color="primary" inset hide-details density="compact" class="flex-grow-0" />
    </div>

    <v-alert
      v-if="unreachable && live" type="warning" variant="tonal" density="compact" class="mx-2 mb-2"
      :text="$t('logs.unreachable')"
    />
    <div v-if="!loaded" class="d-flex justify-center pa-8"><v-progress-circular indeterminate color="primary" /></div>
    <div v-else ref="box" class="log mx-2 mb-2">
      <div v-if="!filtered.length" class="pa-6 text-center text-medium-emphasis">
        {{ entries.length ? $t('logs.noMatches') : $t('logs.empty') }}
      </div>
      <div v-for="e in filtered" :key="e.id" class="line" :class="{ problem: severity[e.level] >= 3 }">
        <span class="time">{{ formatTime(e.time) }}</span>
        <span class="level" :class="`text-${levelColor[e.level]}`">{{ e.level }}</span>
        <span class="target" :title="e.target">{{ shortTarget(e.target) }}</span>
        <span class="msg">{{ e.message }}</span>
      </div>
    </div>
  </v-card>
</template>

<style scoped>
.levels { max-width: 100%; overflow-x: auto; }
.levels .v-btn { padding: 0 10px; }
.search { min-width: 160px; max-width: 320px; }
code { font-size: 12px; opacity: .7; }
.log {
  height: 60vh; min-height: 240px; overflow: auto;
  background: rgb(var(--v-theme-surface)); border-radius: 12px; padding: 6px 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; line-height: 1.55;
}
.line {
  display: grid; grid-template-columns: auto 5ch minmax(0, 14ch) 1fr; gap: 10px;
  padding: 1px 12px;
}
.line:hover { background: rgba(var(--v-theme-on-surface), .05); }
.line.problem { background: rgba(var(--v-theme-error), .06); }
.time { opacity: .6; white-space: nowrap; }
.level { font-weight: 600; }
.target { opacity: .6; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.msg { white-space: pre-wrap; word-break: break-word; }
@media (max-width: 600px) {
  .line { grid-template-columns: auto 5ch 1fr; }
  .target { display: none; }
}
</style>
