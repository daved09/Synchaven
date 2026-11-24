import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { AddressBook, AddressBookInput, Contact, ContactInput } from '@/api/types'

function download(text: string, name: string) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/vcard' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `${name.replace(/[^\p{L}\p{N}_-]+/gu, '_')}.vcf`
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export const useContactsStore = defineStore('contacts', () => {
  const books = ref<AddressBook[]>([])
  const list = ref<Contact[]>([])
  const loading = ref(false)
  /** Photos are only returned for single queries and are cached here. */
  const photos = ref(new Map<string, string>())

  const bookById = computed(() => new Map(books.value.map((b) => [b.id, b])))
  const sorted = computed(() =>
    [...list.value].sort((a, b) => a.full_name.localeCompare(b.full_name, 'de', { sensitivity: 'base' })),
  )
  /** All categories with the number of contacts, alphabetical */
  const categories = computed(() => {
    const counts = new Map<string, { name: string; count: number }>()
    for (const c of list.value) {
      for (const name of c.categories ?? []) {
        const key = name.toLowerCase()
        const entry = counts.get(key)
        if (entry) entry.count++
        else counts.set(key, { name, count: 1 })
      }
    }
    return [...counts.values()].sort((a, b) => a.name.localeCompare(b.name, 'de', { sensitivity: 'base' }))
  })

  async function load() {
    loading.value = true
    try {
      const [b, c] = await Promise.all([api.addressBooks(), api.contacts()])
      books.value = b
      list.value = c
    } finally {
      loading.value = false
    }
  }
  async function reloadBooks() {
    books.value = await api.addressBooks()
  }

  function put(c: Contact) {
    if (c.photo) photos.value.set(c.id, c.photo)
    else photos.value.delete(c.id)
    const { photo: _, ...rest } = c
    const i = list.value.findIndex((x) => x.id === c.id)
    if (i >= 0) list.value[i] = rest
    else list.value.push(rest)
  }
  async function fetchPhoto(id: string) {
    if (photos.value.has(id)) return photos.value.get(id)
    const c = await api.contact(id)
    if (c.photo) photos.value.set(id, c.photo)
    return c.photo
  }

  async function create(data: ContactInput) {
    const c = await api.createContact(data)
    put(c)
    await reloadBooks()
    return c
  }
  async function update(id: string, data: ContactInput) {
    const c = await api.updateContact(id, data)
    put(c)
    await reloadBooks()
    return c
  }
  async function remove(id: string) {
    await api.deleteContact(id)
    list.value = list.value.filter((c) => c.id !== id)
    photos.value.delete(id)
    await reloadBooks()
  }

  /** Renames a category in all contacts (`newName` = null: removes it). */
  async function rewriteCategory(name: string, newName: string | null) {
    const r = newName === null ? await api.deleteContactCategory(name) : await api.renameContactCategory(name, newName)
    list.value = await api.contacts()
    return r.updated
  }

  async function createBook(data: AddressBookInput) {
    const b = await api.createAddressBook(data)
    books.value.push(b)
    return b
  }
  async function updateBook(id: string, data: AddressBookInput) {
    const b = await api.updateAddressBook(id, data)
    books.value = books.value.map((x) => (x.id === id ? b : x))
  }
  async function removeBook(id: string) {
    await api.deleteAddressBook(id)
    await load()
  }
  async function exportBook(book: AddressBook) {
    download(await api.exportAddressBook(book.id), book.name)
  }
  async function importFile(book: AddressBook, file: File) {
    const r = await api.importAddressBook(book.id, await file.text())
    photos.value.clear()
    await load()
    return r
  }

  function reset() {
    books.value = []
    list.value = []
    photos.value.clear()
  }

  return {
    books, list, loading, photos, bookById, sorted, categories,
    load, fetchPhoto, create, update, remove, rewriteCategory, createBook, updateBook, removeBook, exportBook, importFile, reset,
  }
})
