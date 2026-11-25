<script setup lang="ts">
import { t } from '@/i18n'
import { computed, ref, watch } from 'vue'
import { useDisplay } from 'vuetify'
import type { Contact, ContactData } from '@/api/types'
import TypedListEditor from '@/components/TypedListEditor.vue'
import { useContactsStore } from '@/stores/contacts'
import { useNotifyStore } from '@/stores/notify'
import {
  birthdayFromInput, birthdayToInput, derivedName, emptyAddress, ownName, emptyContact, OTHER_TYPES, PHONE_TYPES,
  sameCategory,
} from '@/utils/contact'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ contactId: string | null; bookId: string | null; category?: string | null }>()
const emit = defineEmits<{ saved: [contact: Contact] }>()

const { xs } = useDisplay()
const contacts = useContactsStore()
const notify = useNotifyStore()

const formRef = ref()
const saving = ref(false)
const form = ref<ContactData>(emptyContact())
const bookId = ref('')
/**
 * Name of a card without name parts (e.g. imported with only FN) – kept as long as no name parts
 * are entered. Otherwise the server always builds the name from the name parts.
 */
const keptName = ref('')
const showMore = ref(false)
const birthdayText = ref('')
let initialBirthdayText = ''
let initialBirthday = ''

watch(open, (v) => {
  if (!v) return
  formRef.value?.resetValidation()
  const c = props.contactId ? contacts.list.find((x) => x.id === props.contactId) : undefined
  if (c) {
    const copy: ContactData = JSON.parse(JSON.stringify(c))
    form.value = { ...emptyContact(), ...copy }
    bookId.value = c.addressbook_id
    keptName.value = ownName(c) ? '' : c.full_name
  } else {
    form.value = {
      ...emptyContact(), phones: [{ type: 'mobile', value: '' }], emails: [{ type: 'home', value: '' }],
      categories: props.category ? [props.category] : [],
    }
    bookId.value = props.bookId ?? contacts.books[0]?.id ?? ''
    keptName.value = ''
  }
  const f = form.value
  showMore.value = !!(f.prefix || f.additional_names || f.suffix || f.nickname)
  initialBirthday = f.birthday
  birthdayText.value = initialBirthdayText = birthdayToInput(f.birthday)
})

const autoName = computed(() => derivedName(form.value))
const bookItems = computed(() => contacts.books.map((b) => ({ title: b.name, value: b.id })))
const categoryItems = computed(() => contacts.categories.map((c) => c.name))

/** Remove empty and duplicate entries (case-insensitive); keep the existing spelling. */
function setCategories(list: string[]) {
  const out: string[] = []
  for (const raw of list) {
    const name = raw.trim().replace(/\s+/g, ' ')
    const known = categoryItems.value.find((c) => sameCategory(c, name))
    if (name && !out.some((c) => sameCategory(c, name))) out.push(known ?? name)
  }
  form.value.categories = out
}

const birthdayRule = (v: string) =>
  v === initialBirthdayText || birthdayFromInput(v) !== null || t('contactDialog.birthdayFormat')

function addAddress() {
  form.value.addresses.push(emptyAddress(form.value.addresses.length ? 'work' : 'home'))
}

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  if (!keptName.value && !autoName.value) {
    notify.fail(new Error(t('contactDialog.nameRequired')))
    return
  }
  const birthday = birthdayText.value === initialBirthdayText ? initialBirthday : birthdayFromInput(birthdayText.value) ?? ''
  const input = { ...form.value, full_name: keptName.value, birthday, addressbook_id: bookId.value }
  saving.value = true
  try {
    const c = props.contactId ? await contacts.update(props.contactId, input) : await contacts.create(input)
    notify.info(props.contactId ? t('contact.saved') : t('contact.created'))
    emit('saved', c)
    open.value = false
  } catch (e) {
    notify.fail(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog v-model="open" :fullscreen="xs" max-width="680" persistent scrollable @keydown.esc="open = false">
    <v-card color="surface-container-high">
      <v-toolbar color="transparent" density="comfortable">
        <v-btn icon="mdi-close" :title="$t('common.close')" @click="open = false" />
        <v-toolbar-title>{{ contactId ? $t('contact.edit') : $t('contact.new') }}</v-toolbar-title>
        <v-btn color="primary" variant="flat" class="mr-3" :loading="saving" @click="save">{{ $t('common.save') }}</v-btn>
      </v-toolbar>
      <v-card-text class="pt-2">
        <v-form ref="formRef" @submit.prevent="save">
          <!-- Name -->
          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-account-outline" class="mt-4 field-icon" />
            <div class="flex-1-1">
              <div class="d-flex ga-3 flex-wrap">
                <v-text-field v-model="form.given_name" :label="$t('contact.givenName')" autofocus class="flex-1-1 min-field" autocomplete="off" />
                <v-text-field v-model="form.family_name" :label="$t('contact.familyName')" class="flex-1-1 min-field" autocomplete="off" />
              </div>
              <v-expand-transition>
                <div v-if="showMore">
                  <div class="d-flex ga-3 flex-wrap">
                    <v-text-field v-model="form.prefix" :label="$t('contact.prefix')" class="flex-1-1 min-field" />
                    <v-text-field v-model="form.additional_names" :label="$t('contact.additionalNames')" class="flex-1-1 min-field" />
                  </div>
                  <div class="d-flex ga-3 flex-wrap">
                    <v-text-field v-model="form.suffix" :label="$t('contact.suffix')" class="flex-1-1 min-field" />
                    <v-text-field v-model="form.nickname" :label="$t('contact.nickname')" class="flex-1-1 min-field" />
                  </div>
                </div>
              </v-expand-transition>
              <v-btn
                variant="text" size="small" class="mb-2 ml-n2" :prepend-icon="showMore ? 'mdi-chevron-up' : 'mdi-chevron-down'"
                @click="showMore = !showMore"
              >
                {{ showMore ? $t('contactDialog.fewerNameFields') : $t('contactDialog.moreNameFields') }}
              </v-btn>
            </div>
          </div>

          <!-- Company -->
          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-domain" class="mt-4 field-icon" />
            <div class="flex-1-1">
              <v-text-field v-model="form.organization" :label="$t('contact.organization')" />
              <div class="d-flex ga-3 flex-wrap">
                <v-text-field v-model="form.title" :label="$t('contact.jobTitle')" class="flex-1-1 min-field" />
                <v-text-field v-model="form.department" :label="$t('contact.department')" class="flex-1-1 min-field" />
              </div>
            </div>
          </div>

          <TypedListEditor
            v-model="form.phones" :label="$t('contact.phone')" icon="mdi-phone-outline" :types="PHONE_TYPES" input-type="tel"
            :add-text="$t('contactDialog.addPhone')" default-type="mobile"
          />
          <TypedListEditor
            v-model="form.emails" :label="$t('common.email')" icon="mdi-email-outline" :types="OTHER_TYPES" input-type="email"
            :add-text="$t('contactDialog.addEmail')" default-type="home"
          />

          <!-- Addresses -->
          <div v-for="(a, i) in form.addresses" :key="i" class="d-flex ga-2 align-start">
            <v-icon icon="mdi-map-marker-outline" class="mt-4 field-icon" :class="{ invisible: i > 0 }" />
            <div class="flex-1-1">
              <div class="d-flex ga-2 align-start">
                <v-text-field v-model="a.street" :label="$t('contact.street')" class="flex-1-1" />
                <v-combobox
                  :model-value="a.type" :items="OTHER_TYPES" item-title="title" item-value="value" :return-object="false"
                  :label="$t('contact.type')" class="type-field" @update:model-value="(t: string | null) => (a.type = (t ?? '').trim())"
                />
                <v-btn icon="mdi-close" variant="text" size="small" class="mt-2" :title="$t('contactDialog.removeAddress')" @click="form.addresses.splice(i, 1)" />
              </div>
              <div class="d-flex ga-3 address-row">
                <v-text-field v-model="a.postal_code" :label="$t('contact.postalCode')" class="plz" />
                <v-text-field v-model="a.locality" :label="$t('contact.locality')" class="flex-1-1" />
              </div>
              <div class="d-flex ga-3 flex-wrap address-row">
                <v-text-field v-model="a.region" :label="$t('contact.region')" class="flex-1-1 min-field" />
                <v-text-field v-model="a.country" :label="$t('contact.country')" class="flex-1-1 min-field" />
              </div>
              <v-text-field v-if="a.extended || a.po_box" v-model="a.extended" :label="$t('contact.extended')" />
            </div>
          </div>
          <v-btn variant="text" prepend-icon="mdi-plus" class="ml-8 mb-2" size="small" @click="addAddress">{{ $t('contactDialog.addAddress') }}</v-btn>

          <TypedListEditor
            v-model="form.urls" :label="$t('contact.website')" icon="mdi-web" :types="OTHER_TYPES" input-type="url"
            :add-text="$t('contactDialog.addWebsite')" default-type="home"
          />

          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-cake-variant-outline" class="mt-4 field-icon" />
            <v-text-field
              v-model="birthdayText" :label="$t('contact.birthday')" :placeholder="$t('contactDialog.birthdayPlaceholder')" :hint="$t('contactDialog.birthdayHint')" class="flex-1-1"
              :rules="[birthdayRule]"
            />
          </div>
          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-text" class="mt-4 field-icon" />
            <v-textarea v-model="form.note" :label="$t('contact.note')" rows="2" auto-grow class="flex-1-1" />
          </div>
          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-label-outline" class="mt-4 field-icon" />
            <v-combobox
              :model-value="form.categories" :items="categoryItems" :label="$t('contact.categories')" multiple chips closable-chips
              :hint="$t('contactDialog.categoriesHint')" class="flex-1-1" :delimiters="[',']"
              @update:model-value="setCategories"
            />
          </div>
          <div v-if="bookItems.length > 1" class="d-flex ga-2 align-start">
            <v-icon icon="mdi-book-account-outline" class="mt-4 field-icon" />
            <v-select v-model="bookId" :items="bookItems" :label="$t('contact.addressBook')" class="flex-1-1" />
          </div>
        </v-form>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.field-icon { opacity: .7; }
.invisible { visibility: hidden; }
.min-field { min-width: 180px; }
.type-field { flex: 0 0 170px; max-width: 170px; }
.plz { flex: 0 0 110px; max-width: 110px; }
.address-row { margin-right: 44px; }
@media (max-width: 599px) {
  .type-field { flex-basis: 115px; max-width: 115px; }
  .address-row { margin-right: 0; }
}
</style>
