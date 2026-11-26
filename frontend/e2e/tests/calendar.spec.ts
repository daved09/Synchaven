import { createEvent, expect, openCalendar, test } from '../support/fixtures'

test.describe('calendar', () => {
  test('create, edit and delete an event', async ({ page, user }) => {
    void user
    await openCalendar(page)
    await page.getByRole('button', { name: /Create/ }).click()
    await page.getByRole('listitem').filter({ hasText: 'Event' }).first().click()

    const dialog = page.getByRole('dialog').filter({ hasText: 'New event' })
    await expect(dialog).toBeVisible()
    // A title is required
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(dialog.getByText('Please enter a title')).toBeVisible()

    await dialog.getByLabel('Title').fill('Team meeting')
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByText('Event created')).toBeVisible()

    const item = page.getByRole('button', { name: 'Team meeting' })
    await expect(item).toBeVisible()

    // Details → edit
    await item.click()
    const details = page.getByRole('dialog').filter({ hasText: 'Team meeting' })
    await details.getByTitle('Edit').click()
    const editor = page.getByRole('dialog').filter({ hasText: 'Edit event' })
    await editor.getByLabel('Title').fill('Team meeting (moved)')
    await editor.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('Event saved')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Team meeting (moved)' })).toBeVisible()

    // Details → delete with confirmation
    await page.getByRole('button', { name: 'Team meeting (moved)' }).click()
    await page.getByRole('dialog').filter({ hasText: 'Team meeting (moved)' }).getByTitle('Delete').click()
    const confirm = page.getByRole('dialog').filter({ hasText: 'Delete event?' })
    await confirm.getByRole('button', { name: 'Delete' }).click()
    await expect(page.getByText('Event deleted')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Team meeting (moved)' })).toHaveCount(0)
  })

  test('an event dialog opened before the calendars have loaded still saves', async ({ page, user }) => {
    void user
    // Hold back the calendar list so the dialog opens before it arrives
    let release!: () => void
    const held = new Promise<void>((r) => (release = r))
    await page.route('**/api/calendars', async (route) => {
      await held
      await route.continue()
    })
    await page.goto('/')
    await expect(page.getByRole('button', { name: /Create/ })).toBeVisible()
    await page.locator('body').press('c')

    const dialog = page.getByRole('dialog').filter({ hasText: 'New event' })
    await dialog.getByLabel('Title').fill('Early bird')
    release()
    await expect(page.locator('.v-navigation-drawer').getByText('Personal', { exact: true })).toBeVisible()
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('Event created')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Early bird' })).toBeVisible()
  })

  test('switch between month, week, day and schedule views', async ({ page, api }) => {
    await createEvent(api, 'Dentist', { location: 'Main Street 1' })
    await openCalendar(page)
    await expect(page.getByRole('button', { name: 'Dentist' })).toBeVisible()

    for (const [mode, key] of [['Week', 'w'], ['Day', 't'], ['Schedule', 'l'], ['Month', 'm']] as const) {
      await page.locator('body').press(key)
      await expect(page.getByTitle(mode, { exact: true })).toBeVisible()
      await expect(page.getByText('Dentist').first()).toBeVisible()
    }

    // The view menu does the same
    await page.getByTitle('Month', { exact: true }).click()
    await page.locator('.v-menu').getByText('Schedule', { exact: true }).click()
    await expect(page.getByText('Main Street 1')).toBeVisible()
  })

  test('previous, next and today change the shown period', async ({ page, user }) => {
    void user
    await openCalendar(page)
    const title = page.locator('.app-bar .title')
    const current = await title.innerText()

    await page.getByTitle(/^Next/).click()
    await expect(title).not.toHaveText(current)
    await page.getByTitle(/^Previous/).click()
    await page.getByTitle(/^Previous/).click()
    await expect(title).not.toHaveText(current)
    await page.getByTitle('Today (H)').click()
    await expect(title).toHaveText(current)
  })

  test('search finds events by title', async ({ page, api }) => {
    await createEvent(api, 'Yoga class')
    await createEvent(api, 'Car service')
    await openCalendar(page)
    await expect(page.getByRole('button', { name: 'Yoga class' })).toBeVisible()

    await page.locator('body').press('/')
    await page.getByPlaceholder(/Search/).fill('yoga')
    await expect(page.getByText('Yoga class').first()).toBeVisible()
    await expect(page.getByText('Car service')).toHaveCount(0)
  })
})
