import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test'
import { ADMIN, ADMIN_STATE } from './constants.mjs'

export interface TestUser {
  id: string
  email: string
  password: string
  name: string
  token: string
}

let counter = 0
function uniqueEmail(prefix = 'user') {
  return `${prefix}-${Date.now().toString(36)}-${process.pid}-${counter++}@e2e.test`
}

/** Registers a fresh account through the API (registration is open on the test server). */
export async function registerUser(
  request: APIRequestContext,
  opts: { name?: string; language?: 'de' | 'en'; prefix?: string } = {},
): Promise<TestUser> {
  const user = { email: uniqueEmail(opts.prefix), password: 'test-password-123', name: opts.name ?? 'Test User' }
  const res = await request.post('/api/auth/register', { data: { ...user, language: opts.language ?? 'en' } })
  expect(res.ok(), await res.text()).toBeTruthy()
  const body = await res.json()
  return { ...user, id: body.user.id, token: body.token }
}

/** Signs the administrator in through the API and returns the token. */
export async function adminToken(request: APIRequestContext): Promise<string> {
  const res = await request.post('/api/auth/login', { data: { email: ADMIN.email, password: ADMIN.password } })
  expect(res.ok(), await res.text()).toBeTruthy()
  return (await res.json()).token
}

/** Makes the page start signed in as `user` (the web UI keeps its token in localStorage). */
export async function signInAs(page: Page, user: Pick<TestUser, 'token'>) {
  await page.addInitScript((token) => {
    if (!localStorage.getItem('synchaven.token')) localStorage.setItem('synchaven.token', token)
  }, user.token)
}

type Fixtures = {
  /** A fresh account for this test, already signed in on `page` */
  user: TestUser
  /** API calls as `user` */
  api: APIRequestContext
}

export const test = base.extend<Fixtures>({
  user: async ({ page, request }, use) => {
    const user = await registerUser(request)
    await signInAs(page, user)
    await use(user)
  },
  api: async ({ playwright, baseURL, user }, use) => {
    const ctx = await playwright.request.newContext({
      baseURL,
      extraHTTPHeaders: { Authorization: `Bearer ${user.token}`, 'Accept-Language': 'en' },
    })
    await use(ctx)
    await ctx.dispose()
  },
})

export { expect, ADMIN_STATE }

/** Today's date (YYYY-MM-DD) in the browser time zone of the tests (Europe/Berlin), shifted by `days`. */
export function berlinDate(days = 0): string {
  const d = new Date(Date.now() + days * 86_400_000)
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(d)
}

/** ID of the first entry of a list endpoint (optionally the first one matching `where`). */
export async function firstId(
  api: APIRequestContext, path: string, where: (item: Record<string, unknown>) => boolean = () => true,
): Promise<string> {
  const res = await api.get(path)
  expect(res.ok(), await res.text()).toBeTruthy()
  return (await res.json()).find(where).id
}

/** Creates a timed event today 09:00–10:00 UTC (or on `date`) through the API. */
export async function createEvent(api: APIRequestContext, title: string, opts: { date?: string; location?: string } = {}) {
  const date = opts.date ?? berlinDate()
  const res = await api.post('/api/events', {
    data: {
      calendar_id: await firstId(api, '/api/calendars', (c) => c.kind === 'default'),
      title,
      location: opts.location ?? '',
      start: `${date}T09:00:00Z`,
      end: `${date}T10:00:00Z`,
      timezone: 'Europe/Berlin',
      all_day: false,
    },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  return res.json()
}

/** Opens the calendar and waits until the calendar list has loaded. */
export async function openCalendar(page: Page) {
  await page.goto('/')
  await waitForCalendar(page)
}

export async function waitForCalendar(page: Page) {
  await expect(page).toHaveURL(/\/(\?.*)?$/)
  await expect(page.locator('.v-navigation-drawer').getByText('Personal', { exact: true })).toBeVisible()
}
