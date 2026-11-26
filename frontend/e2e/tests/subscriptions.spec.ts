import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { berlinDate, expect, openCalendar, test } from '../support/fixtures'

/** A calendar file on a local web server; `title` can be changed to check refreshing. */
let server: Server
let feedUrl = ''
let title = 'Founders Day'

function feed() {
  const day = berlinDate().replaceAll('-', '')
  const next = berlinDate(1).replaceAll('-', '')
  return [
    'BEGIN:VCALENDAR', 'VERSION:2.0', 'X-WR-CALNAME:E2E Holidays',
    'BEGIN:VEVENT', 'UID:founders@e2e.test', `DTSTART;VALUE=DATE:${day}`, `DTEND;VALUE=DATE:${next}`,
    `SUMMARY:${title}`, 'END:VEVENT', 'END:VCALENDAR', '',
  ].join('\r\n')
}

test.beforeAll(async () => {
  server = createServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/calendar' })
    res.end(feed())
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  feedUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}/holidays.ics`
})
test.afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())))

test.describe('calendar subscriptions', () => {
  test('subscribe, show read-only events, refresh and unsubscribe', async ({ page, user }) => {
    void user
    title = 'Founders Day'
    await openCalendar(page)
    const drawer = page.locator('.v-navigation-drawer')

    await page.getByTitle('Add calendar').click()
    await page.locator('.v-menu').getByText('Subscribe to calendar').click()
    const dialog = page.getByRole('dialog').filter({ hasText: 'Subscribe to calendar' })
    // An address is required, an invalid one is reported by the server
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(dialog.getByText('Please enter an address')).toBeVisible()
    await dialog.getByLabel('Calendar address (.ics)').fill('ftp://example.org/x.ics')
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('Please enter a valid address')).toBeVisible()

    await dialog.getByLabel('Calendar address (.ics)').fill(feedUrl)
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByText('Calendar subscribed')).toBeVisible()
    // The name comes from the file
    await expect(drawer.getByText('E2E Holidays', { exact: true })).toBeVisible()

    // The event is shown, but cannot be edited
    await page.getByRole('button', { name: 'Founders Day' }).click()
    const details = page.getByRole('dialog').filter({ hasText: 'Founders Day' })
    await expect(details).toBeVisible()
    await expect(details.getByTitle('Edit')).toHaveCount(0)
    await page.keyboard.press('Escape')

    // Refreshing picks up changes of the file
    title = 'Founders Day (observed)'
    const item = drawer.locator('.v-list-item').filter({ hasText: 'E2E Holidays' })
    await item.hover()
    await item.getByRole('button').last().click()
    await expect(page.locator('.v-menu').getByText('Import (.ics)')).toHaveCount(0)
    await page.locator('.v-menu').getByText('Update now').click()
    await expect(page.getByText('Calendar updated')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Founders Day (observed)' })).toBeVisible()

    // Deleting ends the subscription
    await item.hover()
    await item.getByRole('button').last().click()
    await page.locator('.v-menu').getByText('Delete').click()
    await page.getByRole('dialog').filter({ hasText: 'Delete calendar?' }).getByRole('button', { name: 'Delete' }).click()
    await expect(page.getByText('Calendar deleted')).toBeVisible()
    await expect(drawer.getByText('E2E Holidays', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Founders Day (observed)' })).toHaveCount(0)
  })

  test('events of a subscription cannot be changed through the API', async ({ api }) => {
    const res = await api.post('/api/calendars', { data: { name: '', source_url: feedUrl } })
    expect(res.status(), await res.text()).toBe(201)
    const cal = await res.json()
    expect(cal).toMatchObject({ kind: 'subscription', name: 'E2E Holidays', default_all_day_reminders: [] })

    const events = await (await api.get(`/api/calendars/${cal.id}/events`)).json()
    expect(events).toHaveLength(1)
    expect(events[0].read_only).toBe(true)
    expect((await api.delete(`/api/events/${events[0].id}`)).status()).toBe(400)
    const imported = await api.post(`/api/calendars/${cal.id}/import`, { data: feed(), headers: { 'Content-Type': 'text/calendar' } })
    expect(imported.status()).toBe(400)
  })
})
