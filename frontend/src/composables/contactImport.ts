import { t as tr } from '@/i18n'
import type { AddressBook } from '@/api/types'
import { useContactsStore } from '@/stores/contacts'
import { useNotifyStore } from '@/stores/notify'
import { pickFiles } from '@/utils/files'

const VCARD_ACCEPT = '.vcf,.vcard,text/vcard,text/x-vcard'
export const isVcard = (f: File) => /\.vcf$|\.vcard$/i.test(f.name) || f.type.includes('vcard')

/** Imports .vcf files into an address book and reports the totals */
export function useContactImport() {
  const contacts = useContactsStore()
  const notify = useNotifyStore()

  async function importFiles(files: File[], book: AddressBook | null) {
    if (!book || !files.length) return
    try {
      let created = 0
      let updated = 0
      let unchanged = 0
      let skipped = 0
      for (const f of files) {
        const r = await contacts.importFile(book, f)
        created += r.created
        updated += r.updated
        unchanged += r.unchanged
        skipped += r.skipped.length
      }
      const parts = [
        tr('contactsView.importNew', { n: created }),
        updated ? tr('contactsView.importUpdated', { n: updated }) : '',
        unchanged ? tr('contactsView.importUnchanged', { n: unchanged }) : '',
        skipped ? tr('contactsView.importSkipped', { n: skipped }) : '',
      ]
      notify.info(tr('contactsView.importResult', { name: book.name, parts: parts.filter(Boolean).join(', ') }))
    } catch (e) {
      notify.fail(e)
    }
  }
  /** Lets the user choose files, then imports them */
  async function pickAndImport(book: AddressBook | null) {
    if (!book) return
    await importFiles(await pickFiles(VCARD_ACCEPT, true), book)
  }

  return { importFiles, pickAndImport }
}
