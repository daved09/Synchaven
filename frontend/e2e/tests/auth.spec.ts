import { adminToken, expect, registerUser, test } from '../support/fixtures'

test.describe('authentication', () => {
  test('protected pages redirect to the sign-in page', async ({ page }) => {
    await page.goto('/contacts')
    await expect(page).toHaveURL(/\/login\?redirect=(%2F|\/)contacts$/)
    await expect(page.getByText('Continue to your calendar')).toBeVisible()
  })

  test('register a new account through the form', async ({ page }) => {
    const email = `register-${Date.now()}@e2e.test`
    await page.goto('/login')
    await page.getByRole('link', { name: 'Register' }).click()
    await expect(page).toHaveURL(/\/register/)

    await page.getByLabel('Name').fill('Registered Person')
    await page.getByLabel('Email').fill(email)
    await page.getByLabel('Password', { exact: true }).fill('short')
    await page.getByRole('button', { name: 'Register' }).click()
    await expect(page.getByText('at least 8 characters', { exact: false })).toBeVisible()

    await page.getByLabel('Password', { exact: true }).fill('long-enough-password')
    await page.getByRole('button', { name: 'Register' }).click()
    await expect(page).toHaveURL(/\/$/)

    await page.getByTitle('Account').click()
    await expect(page.getByText(email)).toBeVisible()
  })

  test('sign in, sign out and back to the page asked for', async ({ page, request }) => {
    const user = await registerUser(request, { name: 'Sign-in Person' })

    await page.goto('/contacts')
    await expect(page).toHaveURL(/\/login/)
    await page.getByLabel('Email').fill(user.email)
    await page.getByLabel('Password', { exact: true }).fill('wrong-password')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.locator('.v-alert')).toBeVisible()

    await page.getByLabel('Password', { exact: true }).fill(user.password)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page).toHaveURL(/\/contacts$/)

    await page.getByTitle('Account').click()
    await page.getByText('Sign out').click()
    await expect(page).toHaveURL(/\/login/)
    expect(await page.evaluate(() => localStorage.getItem('synchaven.token'))).toBeNull()
  })

  test('a session ended by an administrator sends you back to sign in', async ({ page, user, request }) => {
    await page.goto('/')
    await expect(page.getByRole('button', { name: /Create/ })).toBeVisible()

    const admin = await adminToken(request)
    const res = await request.post(`/api/admin/users/${user.id}/logout`, { headers: { Authorization: `Bearer ${admin}` } })
    expect(res.ok(), await res.text()).toBeTruthy()

    await page.reload()
    await expect(page).toHaveURL(/\/login/)
  })
})
