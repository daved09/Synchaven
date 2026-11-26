<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { t as tr } from '@/i18n'
import { useDisplay } from 'vuetify'
import type { Contact } from '@/api/types'
import AccountMenu from '@/components/AccountMenu.vue'
import ContactDetails from '@/components/ContactDetails.vue'
import ContactDialog from '@/components/ContactDialog.vue'
import DavSetupDialog from '@/components/DavSetupDialog.vue'
import ContactList from '@/components/contacts/ContactList.vue'
import ContactsSidebar from '@/components/contacts/ContactsSidebar.vue'
import { isVcard, useContactImport } from '@/composables/contactImport'
import { useContactRoute } from '@/composables/contactRoute'
import { useShortcuts } from '@/composables/shortcuts'
import { useAuthStore } from '@/stores/auth'
import { useConfirmStore } from '@/stores/confirm'
import { useContactsStore } from '@/stores/contacts'
import { useNotifyStore } from '@/stores/notify'
import { displayName, matches } from '@/utils/contact'

const { mdAndUp, lgAndUp, smAndDown } = useDisplay()
const auth = useAuthStore()
const contacts = useContactsStore()
const notify = useNotifyStore()
const confirm = useConfirmStore()
const {
  bookFilter, categoryFilter, selectedId, selected, currentBook, importTarget, select, filterBook, inFilter,
} = useContactRoute()
const { importFiles, pickAndImport } = useContactImport()

const drawer = ref(mdAndUp.value)
const search = ref('')
const searchField = ref<HTMLElement & { focus: () => void }>()
const editorOpen = ref(false)
const editId = ref<string | null>(null)
const davOpen = ref(false)
const dragOver = ref(false)

const filtered = computed(() => contacts.sorted.filter((c) => inFilter(c) && matches(c, search.value)))
const mobileDetails = computed({
  get: () => smAndDown.value && !!selected.value,
  set: (v) => !v && select(null),
})

onMounted(async () => {
  try {
    await Promise.all([auth.fetchMe(), contacts.load()])
  } catch (e) {
    notify.fail(e)
  }
})

// Remove a deleted or unknown selection
watch(() => [selectedId.value, contacts.list.length], () => {
  if (selectedId.value && !contacts.loading && contacts.list.length && !selected.value) select(null)
})

function onNavigate() {
  if (smAndDown.value) drawer.value = false
}

// --- Contacts ---
function newContact() {
  editId.value = null
  editorOpen.value = true
}
function editContact(c: Contact) {
  editId.value = c.id
  editorOpen.value = true
}
function onSaved(c: Contact) {
  // Show all contacts if the saved one is not in the current filter
  if (!inFilter(c)) filterBook(null)
  setTimeout(() => select(c))
}
async function deleteContact(c: Contact) {
  if (!(await confirm.ask(tr('contactsView.deleteContactTitle'), tr('contactsView.deleteContactText', { name: displayName(c) })))) return
  try {
    await contacts.remove(c.id)
    select(null)
    notify.info(tr('contactsView.contactDeleted'))
  } catch (e) {
    notify.fail(e)
  }
}

// --- Drag & drop import ---
function onDrop(e: DragEvent) {
  dragOver.value = false
  const files = [...(e.dataTransfer?.files ?? [])].filter(isVcard)
  if (!files.length) {
    notify.fail(new Error(tr('contactsView.dropVcf')))
    return
  }
  importFiles(files, importTarget.value)
}

useShortcuts({
  c: newContact,
  n: newContact,
  '/': () => searchField.value?.focus(),
  e: () => (selected.value ? editContact(selected.value) : false),
})
</script>

<template>
  <v-navigation-drawer
    v-model="drawer" :temporary="smAndDown" :mobile="smAndDown" width="300" color="surface-container-low" border="0"
  >
    <div class="pa-4 pb-2">
      <v-btn
        color="primary-container" size="x-large" rounded="lg" class="create-btn" prepend-icon="mdi-account-plus-outline"
        elevation="1" @click="newContact"
      >
        {{ $t('contact.new') }}
      </v-btn>
    </div>
    <ContactsSidebar @navigate="onNavigate" @dav="davOpen = true" />
  </v-navigation-drawer>

  <v-app-bar flat color="surface" density="comfortable">
    <v-app-bar-nav-icon :title="$t('common.menu')" @click="drawer = !drawer" />
    <div v-if="lgAndUp" class="d-flex align-center mr-6 brand">
      <v-icon icon="mdi-account-box-multiple" color="primary" size="28" class="mr-2" />
      <span class="text-title-large">{{ $t('nav.contacts') }}</span>
    </div>
    <v-text-field
      ref="searchField" v-model="search" :placeholder="$t('contactsView.search')" density="compact" variant="solo-filled"
      flat rounded="xl" hide-details clearable prepend-inner-icon="mdi-magnify" class="search mx-2"
      bg-color="surface-container-high" @keydown.esc="search = ''"
    />
    <v-progress-circular v-if="contacts.loading" indeterminate size="20" width="2" color="primary" class="mx-2" />
    <v-btn v-if="!smAndDown" icon="mdi-upload-outline" :title="$t('contactsView.importContacts')" @click="pickAndImport(importTarget)" />
    <v-btn icon="mdi-calendar-month-outline" :title="$t('nav.calendar')" :to="{ name: 'calendar' }" />
    <AccountMenu />
  </v-app-bar>

  <v-main class="app-main">
    <div
      class="view-surface contacts-surface d-flex" :class="{ 'drag-over': dragOver }"
      @dragover.prevent="dragOver = true" @dragleave.self="dragOver = false" @drop.prevent="onDrop"
    >
      <div class="list-pane" :class="{ narrow: mdAndUp }">
        <ContactList
          :contacts="filtered" :title="currentBook?.name ?? categoryFilter ?? $t('contactsView.all')"
          :filtered="!!search || !!categoryFilter" :matches="search ? filtered.length : undefined"
          @create="newContact" @import="pickAndImport(importTarget)" @dav="davOpen = true"
        />
      </div>

      <div v-if="mdAndUp" class="detail-pane">
        <ContactDetails
          v-if="selected" :key="selected.id" :contact="selected"
          @edit="editContact(selected)" @delete="deleteContact(selected)"
        />
        <div v-else-if="contacts.list.length" class="empty fill-height d-flex flex-column align-center justify-center text-medium-emphasis">
          <v-icon icon="mdi-card-account-details-outline" size="72" class="mb-3" />
          <div class="text-body-large">{{ $t('contactsView.select') }}</div>
        </div>
      </div>
      <div v-if="dragOver" class="drop-hint">
        <v-icon icon="mdi-file-upload-outline" size="48" class="mb-2" />
        <div class="text-title-medium">{{ $t('contactsView.dropHere') }}</div>
        <div class="text-body-medium">{{ $t('contactsView.dropTarget', { name: importTarget?.name }) }}</div>
      </div>
    </div>
    <v-btn
      v-if="smAndDown" class="fab" color="primary-container" size="x-large" icon="mdi-account-plus-outline" rounded="lg"
      elevation="3" :title="$t('contactsView.createContact')" @click="newContact"
    />
  </v-main>

  <v-dialog v-model="mobileDetails" fullscreen transition="dialog-bottom-transition">
    <v-card v-if="selected" color="surface">
      <ContactDetails
        :contact="selected" closable
        @edit="editContact(selected)" @delete="deleteContact(selected)" @close="mobileDetails = false"
      />
    </v-card>
  </v-dialog>

  <ContactDialog v-model="editorOpen" :contact-id="editId" :book-id="bookFilter" :category="categoryFilter" @saved="onSaved" />
  <DavSetupDialog v-model="davOpen" />
</template>

<style scoped>
.contacts-surface { position: relative; }
.search { max-width: 560px; }
.brand { min-width: 200px; }
.list-pane { display: flex; flex-direction: column; flex: 1 1 auto; min-width: 0; height: 100%; }
.list-pane.narrow { flex: 0 0 380px; max-width: 380px; border-right: 1px solid rgba(var(--v-border-color), var(--v-border-opacity)); }
.detail-pane { flex: 1 1 auto; min-width: 0; height: 100%; overflow-y: auto; }
.empty { max-width: 520px; margin-inline: auto; }
.drop-hint {
  position: absolute; inset: 12px; border: 2px dashed rgb(var(--v-theme-primary)); border-radius: 20px;
  background: rgba(var(--v-theme-primary-container), .85); color: rgb(var(--v-theme-on-primary-container));
  display: flex; flex-direction: column; align-items: center; justify-content: center; pointer-events: none;
}
</style>
