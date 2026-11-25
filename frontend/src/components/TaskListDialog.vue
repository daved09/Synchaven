<script setup lang="ts">
import { t } from '@/i18n'
import { ref, watch } from 'vue'
import type { TaskList } from '@/api/types'
import { CALENDAR_COLORS } from '@/plugins/theme'
import { useNotifyStore } from '@/stores/notify'
import { useTasksStore } from '@/stores/tasks'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ list: TaskList | null }>()
const tasks = useTasksStore()
const notify = useNotifyStore()

const name = ref('')
const color = ref(CALENDAR_COLORS[1])
const saving = ref(false)
const formRef = ref()

watch(open, (v) => {
  if (!v) return
  name.value = props.list?.name ?? ''
  color.value = props.list?.color ?? CALENDAR_COLORS[(tasks.lists.length + 1) % CALENDAR_COLORS.length]
})

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  saving.value = true
  try {
    if (props.list) await tasks.updateList(props.list.id, { name: name.value, color: color.value, visible: props.list.visible })
    else await tasks.createList({ name: name.value, color: color.value })
    notify.info(props.list ? t('taskList.saved') : t('taskList.created'))
    open.value = false
  } catch (e) {
    notify.fail(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog v-model="open" max-width="440">
    <v-card :title="list ? $t('taskList.edit') : $t('taskList.new')" color="surface-container-high">
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="save">
          <v-text-field
            v-model="name" :label="$t('common.name')" autofocus maxlength="100"
            :rules="[(v: string) => !!v.trim() || $t('common.nameRequired')]"
          />
          <div class="text-label-large mb-2">{{ $t('common.color') }}</div>
          <div class="d-flex flex-wrap ga-2 align-center">
            <button
              v-for="c in CALENDAR_COLORS" :key="c" type="button" class="swatch" :style="{ background: c }"
              :aria-label="$t('common.colorNamed', { color: c })" @click="color = c"
            >
              <v-icon v-if="color.toUpperCase() === c" icon="mdi-check" color="white" size="18" />
            </button>
          </div>
        </v-form>
      </v-card-text>
      <v-card-actions class="px-6 pb-4">
        <v-btn variant="outlined" @click="open = false">{{ $t('common.cancel') }}</v-btn>
        <v-spacer />
        <v-btn color="primary" variant="flat" :loading="saving" @click="save">{{ $t('common.save') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.swatch {
  width: 32px; height: 32px; border-radius: 16px; display: inline-flex; align-items: center; justify-content: center;
}
</style>
