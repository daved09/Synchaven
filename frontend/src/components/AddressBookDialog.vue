<script setup lang="ts">
import { t } from '@/i18n'
import { ref, watch } from 'vue'
import type { AddressBook } from '@/api/types'
import { useContactsStore } from '@/stores/contacts'
import { useNotifyStore } from '@/stores/notify'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ book: AddressBook | null }>()
const contacts = useContactsStore()
const notify = useNotifyStore()

const name = ref('')
const description = ref('')
const saving = ref(false)
const formRef = ref()

watch(open, (v) => {
  if (!v) return
  name.value = props.book?.name ?? ''
  description.value = props.book?.description ?? ''
})

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  saving.value = true
  try {
    const data = { name: name.value, description: description.value }
    if (props.book) await contacts.updateBook(props.book.id, data)
    else await contacts.createBook(data)
    notify.info(props.book ? t('addressBook.saved') : t('addressBook.created'))
    open.value = false
  } catch (e) {
    notify.fail(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog v-model="open" max-width="480">
    <v-card :title="book ? $t('addressBook.edit') : $t('addressBook.new')" color="surface-container-high">
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="save">
          <v-text-field
            v-model="name" :label="$t('common.name')" autofocus maxlength="100"
            :rules="[(v: string) => !!v.trim() || $t('common.nameRequired')]"
          />
          <v-textarea v-model="description" :label="$t('common.descriptionOptional')" rows="2" auto-grow />
        </v-form>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" variant="flat" :loading="saving" @click="save">{{ $t('common.save') }}</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>
