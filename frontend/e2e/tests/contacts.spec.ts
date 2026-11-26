import { expect, test, waitForCalendar } from '../support/fixtures'

const VCARD = [
  'BEGIN:VCARD', 'VERSION:3.0', 'N:Lovelace;Ada;;;', 'FN:Ada Lovelace', 'TEL;TYPE=CELL:+49 170 1234567',
  'EMAIL:ada@example.org', 'BDAY:1815-12-10', 'END:VCARD',
  'BEGIN:VCARD', 'VERSION:3.0', 'N:Turing;Alan;;;', 'FN:Alan Turing', 'ORG:Bletchley Park', 'END:VCARD', '',
].join('\r\n')

test.describe('contacts', () => {
  test('create, search, edit and delete a contact', async ({ page, user }) => {
    void user
    await page.goto('/contacts')
    await expect(page.getByText('No contacts yet')).toBeVisible()
    await page.getByRole('button', { name: 'Create contact' }).click()

    const dialog = page.getByRole('dialog').filter({ hasText: 'New contact' })
    // A contact needs at least a name, company, email or phone number
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('Please enter a name, company, email address or phone number')).toBeVisible()

    await dialog.getByLabel('First name').fill('Grace')
    await dialog.getByLabel('Last name').fill('Hopper')
    await dialog.getByLabel('Phone').first().fill('+1 555 0100')
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('Contact created')).toBeVisible()

    const main = page.getByRole('main')
    await expect(main.getByText('Grace Hopper').first()).toBeVisible()
    await expect(main.getByText('+1 555 0100').first()).toBeVisible()

    await main.getByTitle('Edit').click()
    const editor = page.getByRole('dialog').filter({ hasText: 'Edit contact' })
    await editor.getByLabel('Last name').fill('Murray Hopper')
    await editor.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('Contact saved')).toBeVisible()
    await expect(main.getByText('Grace Murray Hopper').first()).toBeVisible()

    const listEntry = main.locator('.v-list-item', { hasText: 'Grace Murray Hopper' })
    await page.getByPlaceholder('Search contacts').fill('nobody')
    await expect(listEntry).toHaveCount(0)
    await page.getByPlaceholder('Search contacts').fill('grace')
    await listEntry.click()

    await main.getByTitle('Delete').click()
    await page.getByRole('dialog').filter({ hasText: 'Delete contact?' }).getByRole('button', { name: 'Delete' }).click()
    await expect(page.getByText('Contact deleted')).toBeVisible()
    await page.getByPlaceholder('Search contacts').fill('')
    await expect(page.getByText('No contacts yet')).toBeVisible()
  })

  test('import a vCard file and see the birthday in the calendar', async ({ page, user }) => {
    void user
    await page.goto('/contacts')
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: 'Import .vcf' }).click()
    await (await chooser).setFiles({ name: 'contacts.vcf', mimeType: 'text/vcard', buffer: Buffer.from(VCARD) })

    await expect(page.getByText(/Import into .*2 new/)).toBeVisible()
    const main = page.getByRole('main')
    await expect(main.getByText('Ada Lovelace')).toBeVisible()
    await expect(main.getByText('Alan Turing')).toBeVisible()

    // Importing the same file again creates no duplicates
    const again = page.waitForEvent('filechooser')
    await page.getByTitle('Import contacts (.vcf)').click()
    await (await again).setFiles({ name: 'contacts.vcf', mimeType: 'text/vcard', buffer: Buffer.from(VCARD) })
    await expect(page.getByText(/unchanged/)).toBeVisible()
    await expect(main.getByText('Ada Lovelace')).toHaveCount(1)

    // Birthday calendar: search for the generated event in the schedule view
    await page.getByTitle('Calendar').first().click()
    await waitForCalendar(page)
    await page.locator('body').press('/')
    await page.getByPlaceholder(/Search/).fill('Ada Lovelace')
    await expect(page.getByText(/Ada Lovelace/).first()).toBeVisible()
  })
})
