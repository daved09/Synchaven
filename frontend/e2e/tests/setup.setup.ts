import { expect, test } from '@playwright/test'
import { ADMIN, ADMIN_STATE, SETUP_TOKEN } from '../support/constants.mjs'

// Runs once on the empty database before all other tests (project "setup").
test('initial setup creates the administrator account', async ({ page }) => {
  await page.goto('/')
  // No account yet: the sign-in page switches to the initial setup
  await expect(page).toHaveURL(/\/register/)
  await expect(page.getByText('Initial setup')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Sign in' })).toHaveCount(0)

  await page.getByLabel('Setup token').fill('wrong-token-0123456789')
  await page.getByLabel('Name').fill(ADMIN.name)
  await page.getByLabel('Email').fill(ADMIN.email)
  await page.getByLabel('Password', { exact: true }).fill(ADMIN.password)
  await page.getByRole('button', { name: 'Register' }).click()
  await expect(page.locator('.v-alert')).toBeVisible()
  await expect(page).toHaveURL(/\/register/)

  await page.getByLabel('Setup token').fill(SETUP_TOKEN)
  await page.getByRole('button', { name: 'Register' }).click()

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('button', { name: /Create/ })).toBeVisible()

  await page.context().storageState({ path: ADMIN_STATE })
})
