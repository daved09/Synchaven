<script setup lang="ts">
import { ref } from 'vue'
import { t } from '@/i18n'
import type { TaskList } from '@/api/types'
import TaskListDialog from '@/components/TaskListDialog.vue'
import { useConfirmStore } from '@/stores/confirm'
import { useNotifyStore } from '@/stores/notify'
import { useTasksStore } from '@/stores/tasks'

/** Task lists in the sidebar: visibility, editing, deleting */
const emit = defineEmits<{ show: [list: TaskList] }>()

const tasks = useTasksStore()
const notify = useNotifyStore()
const confirm = useConfirmStore()

const dialogOpen = ref(false)
const editing = ref<TaskList | null>(null)

function open(l: TaskList | null) {
  editing.value = l
  dialogOpen.value = true
}
async function toggle(l: TaskList) {
  try {
    await tasks.toggleListVisible(l)
  } catch (e) {
    notify.fail(e)
  }
}
async function remove(l: TaskList) {
  if (!(await confirm.ask(t('calendarView.deleteListTitle'), t('calendarView.deleteListText', { name: l.name })))) return
  try {
    await tasks.removeList(l.id)
    notify.info(t('calendarView.listDeleted'))
  } catch (e) {
    notify.fail(e)
  }
}
</script>

<template>
  <v-list nav density="comfortable" class="pt-0" bg-color="transparent">
    <div class="d-flex align-center pl-4 pr-2">
      <v-list-subheader class="flex-grow-1 px-0">{{ $t('tasks.title') }}</v-list-subheader>
      <v-btn icon="mdi-plus" size="small" variant="text" :title="$t('taskList.new')" @click="open(null)" />
    </div>
    <v-list-item v-for="l in tasks.lists" :key="l.id" rounded="xl" class="menu-item" @click="toggle(l)">
      <template #prepend>
        <v-checkbox-btn :model-value="l.visible" :color="l.color" :base-color="l.color" density="compact" class="mr-1" @click.stop="toggle(l)" />
      </template>
      <v-list-item-title>{{ l.name }}</v-list-item-title>
      <template #append>
        <span v-if="tasks.openCount(l.id)" class="text-label-medium text-medium-emphasis mr-1">{{ tasks.openCount(l.id) }}</span>
        <v-menu location="end">
          <template #activator="{ props: p }">
            <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" class="item-menu" @click.stop />
          </template>
          <v-list density="compact" rounded="lg">
            <v-list-item prepend-icon="mdi-format-list-checks" :title="$t('calendarView.showTasks')" @click="emit('show', l)" />
            <v-list-item prepend-icon="mdi-pencil-outline" :title="$t('common.edit')" @click="open(l)" />
            <template v-if="tasks.lists.length > 1">
              <v-divider />
              <v-list-item prepend-icon="mdi-delete-outline" :title="$t('common.delete')" base-color="error" @click="remove(l)" />
            </template>
          </v-list>
        </v-menu>
      </template>
    </v-list-item>
  </v-list>
  <TaskListDialog v-model="dialogOpen" :list="editing" />
</template>
