<script setup lang="ts">
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
 * Name einer Karte ohne Namensteile (z. B. importiert mit nur FN) – bleibt erhalten, solange keine Namensteile
 * eingetragen werden. Sonst bildet der Server den Namen immer aus den Namensteilen.
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

/** Leere und doppelte Einträge (ohne Groß-/Kleinschreibung) entfernen; vorhandene Schreibweise übernehmen. */
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
  v === initialBirthdayText || birthdayFromInput(v) !== null || 'Format: TT.MM.JJJJ oder TT.MM.'

function addAddress() {
  form.value.addresses.push(emptyAddress(form.value.addresses.length ? 'work' : 'home'))
}

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  if (!keptName.value && !autoName.value) {
    notify.fail(new Error('Bitte einen Namen, eine Firma, E-Mail-Adresse oder Telefonnummer angeben'))
    return
  }
  const birthday = birthdayText.value === initialBirthdayText ? initialBirthday : birthdayFromInput(birthdayText.value) ?? ''
  const input = { ...form.value, full_name: keptName.value, birthday, addressbook_id: bookId.value }
  saving.value = true
  try {
    const c = props.contactId ? await contacts.update(props.contactId, input) : await contacts.create(input)
    notify.info(props.contactId ? 'Kontakt gespeichert' : 'Kontakt erstellt')
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
        <v-btn icon="mdi-close" title="Schließen" @click="open = false" />
        <v-toolbar-title>{{ contactId ? 'Kontakt bearbeiten' : 'Neuer Kontakt' }}</v-toolbar-title>
        <v-btn color="primary" variant="flat" class="mr-3" :loading="saving" @click="save">Speichern</v-btn>
      </v-toolbar>
      <v-card-text class="pt-2">
        <v-form ref="formRef" @submit.prevent="save">
          <!-- Name -->
          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-account-outline" class="mt-4 field-icon" />
            <div class="flex-1-1">
              <div class="d-flex ga-3 flex-wrap">
                <v-text-field v-model="form.given_name" label="Vorname" autofocus class="flex-1-1 min-field" autocomplete="off" />
                <v-text-field v-model="form.family_name" label="Nachname" class="flex-1-1 min-field" autocomplete="off" />
              </div>
              <v-expand-transition>
                <div v-if="showMore">
                  <div class="d-flex ga-3 flex-wrap">
                    <v-text-field v-model="form.prefix" label="Titel (z. B. Dr.)" class="flex-1-1 min-field" />
                    <v-text-field v-model="form.additional_names" label="Zweiter Vorname" class="flex-1-1 min-field" />
                  </div>
                  <div class="d-flex ga-3 flex-wrap">
                    <v-text-field v-model="form.suffix" label="Namenszusatz (z. B. jun.)" class="flex-1-1 min-field" />
                    <v-text-field v-model="form.nickname" label="Spitzname" class="flex-1-1 min-field" />
                  </div>
                </div>
              </v-expand-transition>
              <v-btn
                variant="text" size="small" class="mb-2 ml-n2" :prepend-icon="showMore ? 'mdi-chevron-up' : 'mdi-chevron-down'"
                @click="showMore = !showMore"
              >
                {{ showMore ? 'Weniger Namensfelder' : 'Weitere Namensfelder' }}
              </v-btn>
            </div>
          </div>

          <!-- Firma -->
          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-domain" class="mt-4 field-icon" />
            <div class="flex-1-1">
              <v-text-field v-model="form.organization" label="Firma" />
              <div class="d-flex ga-3 flex-wrap">
                <v-text-field v-model="form.title" label="Position" class="flex-1-1 min-field" />
                <v-text-field v-model="form.department" label="Abteilung" class="flex-1-1 min-field" />
              </div>
            </div>
          </div>

          <TypedListEditor
            v-model="form.phones" label="Telefon" icon="mdi-phone-outline" :types="PHONE_TYPES" input-type="tel"
            add-text="Telefonnummer hinzufügen" default-type="mobile"
          />
          <TypedListEditor
            v-model="form.emails" label="E-Mail" icon="mdi-email-outline" :types="OTHER_TYPES" input-type="email"
            add-text="E-Mail-Adresse hinzufügen" default-type="home"
          />

          <!-- Adressen -->
          <div v-for="(a, i) in form.addresses" :key="i" class="d-flex ga-2 align-start">
            <v-icon icon="mdi-map-marker-outline" class="mt-4 field-icon" :class="{ invisible: i > 0 }" />
            <div class="flex-1-1">
              <div class="d-flex ga-2 align-start">
                <v-text-field v-model="a.street" label="Straße und Hausnummer" class="flex-1-1" />
                <v-combobox
                  :model-value="a.type" :items="OTHER_TYPES" item-title="title" item-value="value" :return-object="false"
                  label="Typ" class="type-field" @update:model-value="(t: string | null) => (a.type = (t ?? '').trim())"
                />
                <v-btn icon="mdi-close" variant="text" size="small" class="mt-2" title="Adresse entfernen" @click="form.addresses.splice(i, 1)" />
              </div>
              <div class="d-flex ga-3 address-row">
                <v-text-field v-model="a.postal_code" label="PLZ" class="plz" />
                <v-text-field v-model="a.locality" label="Ort" class="flex-1-1" />
              </div>
              <div class="d-flex ga-3 flex-wrap address-row">
                <v-text-field v-model="a.region" label="Bundesland/Region" class="flex-1-1 min-field" />
                <v-text-field v-model="a.country" label="Land" class="flex-1-1 min-field" />
              </div>
              <v-text-field v-if="a.extended || a.po_box" v-model="a.extended" label="Adresszusatz" />
            </div>
          </div>
          <v-btn variant="text" prepend-icon="mdi-plus" class="ml-8 mb-2" size="small" @click="addAddress">Adresse hinzufügen</v-btn>

          <TypedListEditor
            v-model="form.urls" label="Website" icon="mdi-web" :types="OTHER_TYPES" input-type="url"
            add-text="Website hinzufügen" default-type="home"
          />

          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-cake-variant-outline" class="mt-4 field-icon" />
            <v-text-field
              v-model="birthdayText" label="Geburtstag" placeholder="TT.MM.JJJJ" hint="Ohne Jahr: TT.MM." class="flex-1-1"
              :rules="[birthdayRule]"
            />
          </div>
          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-text" class="mt-4 field-icon" />
            <v-textarea v-model="form.note" label="Notiz" rows="2" auto-grow class="flex-1-1" />
          </div>
          <div class="d-flex ga-2 align-start">
            <v-icon icon="mdi-label-outline" class="mt-4 field-icon" />
            <v-combobox
              :model-value="form.categories" :items="categoryItems" label="Kategorien" multiple chips closable-chips
              hint="Vorhandene auswählen oder neue eingeben (Enter)" class="flex-1-1" :delimiters="[',']"
              @update:model-value="setCategories"
            />
          </div>
          <div v-if="bookItems.length > 1" class="d-flex ga-2 align-start">
            <v-icon icon="mdi-book-account-outline" class="mt-4 field-icon" />
            <v-select v-model="bookId" :items="bookItems" label="Adressbuch" class="flex-1-1" />
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
