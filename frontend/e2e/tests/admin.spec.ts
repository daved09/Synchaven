import { ADMIN_STATE, expect, registerUser, test } from '../support/fixtures'

test.describe('administration', () => {
  test.describe('as administrator', () => {
    test.use({ storageState: ADMIN_STATE })

    test('create an account that can then sign in', async ({ page, browser }) => {
      const email = `created-${Date.now()}@e2e.test`
      await page.goto('/')
      await page.getByTitle('Account').click()
      await page.getByText('Administration').click()
      await expect(page).toHaveURL(/\/admin/)

      await page.getByRole('tab', { name: 'Users' }).click()
      await page.getByRole('button', { name: 'New account' }).click()
      const dialog = page.getByRole('dialog').filter({ hasText: 'New account' })
      await dialog.getByLabel('Name').fill('Created By Admin')
      await dialog.getByLabel('Email').fill(email)
      await dialog.getByLabel('Password').fill('created-password-1')
      await dialog.getByRole('button', { name: 'Create' }).click()
      await expect(page.getByText(`Account ${email} created`)).toBeVisible()
      await expect(page.locator('.v-list-item', { hasText: email })).toBeVisible()

      // The new account signs in in a separate browser context
      const ctx = await browser.newContext({ storageState: undefined })
      const other = await ctx.newPage()
      await other.goto('/login')
      await other.getByLabel('Email').fill(email)
      await other.getByLabel('Password', { exact: true }).fill('created-password-1')
      await other.getByRole('button', { name: 'Sign in' }).click()
      await expect(other).toHaveURL(/\/$/)
      await ctx.close()
    })

    test('the overview shows the server version', async ({ page, request }) => {
      const info = await (await request.get('/api/info')).json()
      await page.goto('/admin')
      await expect(page.getByRole('tab', { name: 'Overview' })).toBeVisible()
      await expect(page.getByText(info.version).first()).toBeVisible()
    })
  })

  test('regular accounts are sent back to the calendar', async ({ page, user }) => {
    void user
    await page.goto('/')
    await page.getByTitle('Account').click()
    await expect(page.getByText('Sign out')).toBeVisible()
    await expect(page.getByText('Administration')).toHaveCount(0)

    await page.goto('/admin')
    await expect(page.getByText('Administration is for administrators only')).toBeVisible()
    await expect(page).toHaveURL(/\/$/)
  })

  test('the API rejects admin calls from regular accounts', async ({ request }) => {
    const user = await registerUser(request)
    const res = await request.get('/api/admin/users', { headers: { Authorization: `Bearer ${user.token}` } })
    expect(res.status()).toBe(403)
  })
})
