<script setup lang="ts">
import { ref, watch } from 'vue'
import { t } from '@/i18n'
import { useContactsStore } from '@/stores/contacts'
import { useNotifyStore } from '@/stores/notify'

/** Renames a category on all contacts (merging it into an existing one with the same name) */
const props = defineProps<{ name: string }>()
const emit = defineEmits<{ renamed: [from: string, to: string] }>()
const open = defineModel<boolean>({ required: true })

const contacts = useContactsStore()
const notify = useNotifyStore()

const to = ref('')
const saving = ref(false)
watch(open, (v) => v && (to.value = props.name))

async function save() {
  const name = to.value.trim()
  if (!name || name === props.name) {
    open.value = false
    return
  }
  saving.value = true
  try {
    await contacts.rewriteCategory(props.name, name)
    emit('renamed', props.name, name)
    open.value = false
    notify.info(t('contactsView.categoryRenamed'))
  } catch (e) {
    notify.fail(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog v-model="open" max-width="400">
    <v-card :title="$t('contactsView.renameCategory')" color="surface-container-high">
      <v-card-text>
        <v-text-field v-model="to" :label="$t('common.name')" autofocus hide-details maxlength="100" @keydown.enter="save" />
        <div class="text-body-small text-medium-emphasis mt-3">
          {{ $t('contactsView.renameHint') }}
        </div>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="open = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" :loading="saving" :disabled="!to.trim()" @click="save">{{ $t('contactsView.rename') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
