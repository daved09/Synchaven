import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDisplay } from 'vuetify'
import type { Contact } from '@/api/types'
import { useContactsStore } from '@/stores/contacts'
import { sameCategory } from '@/utils/contact'

/** Filter and selection of the contacts view, kept in the URL (?book=…|?category=…&id=…) so links and "Back" work */
export function useContactRoute() {
  const route = useRoute()
  const router = useRouter()
  const { smAndDown } = useDisplay()
  const contacts = useContactsStore()

  const bookFilter = computed(() => (route.query.book as string | undefined) ?? null)
  const categoryFilter = computed(() => (route.query.category as string | undefined) ?? null)
  const selectedId = computed(() => (route.query.id as string | undefined) ?? null)
  const selected = computed(() => contacts.list.find((c) => c.id === selectedId.value) ?? null)
  const currentBook = computed(() => (bookFilter.value ? contacts.bookById.get(bookFilter.value) ?? null : null))
  /** Where imports go: the address book shown, otherwise the first one */
  const importTarget = computed(() => currentBook.value ?? contacts.books[0] ?? null)

  function select(c: Contact | null) {
    const query = { ...route.query }
    if (c) query.id = c.id
    else delete query.id
    // On phones the details open full screen: "Back" closes them
    if (c && smAndDown.value && !selectedId.value) router.push({ query })
    else router.replace({ query })
  }
  const filterBook = (id: string | null) => router.replace({ query: id ? { book: id } : {} })
  const filterCategory = (name: string) => router.replace({ query: { category: name } })

  /** Whether a contact belongs to the address book or category shown */
  const inFilter = (c: Contact) => (!bookFilter.value || c.addressbook_id === bookFilter.value)
    && (!categoryFilter.value || c.categories.some((x) => sameCategory(x, categoryFilter.value!)))

  return { bookFilter, categoryFilter, selectedId, selected, currentBook, importTarget, select, filterBook, filterCategory, inFilter }
}
