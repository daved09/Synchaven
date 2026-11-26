import { expect, test } from '../support/fixtures'

test.describe('languages', () => {
  test.describe('German browser', () => {
    test.use({ locale: 'de-DE' })

    test('the sign-in page follows the browser language', async ({ page }) => {
      await page.goto('/login')
      await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible()
      await expect(page.locator('html')).toHaveAttribute('lang', 'de')
    })
  })

  test('the language can be switched on the registration page', async ({ page }) => {
    await page.goto('/register')
    await page.getByLabel('Name').fill('Sprachwechsel')
    await page.locator('.v-select').click()
    await page.getByRole('option', { name: 'Deutsch' }).click()

    // The page reloads in German and keeps what was typed
    await expect(page.getByRole('button', { name: 'Registrieren' })).toBeVisible()
    await expect(page.getByLabel('Name')).toHaveValue('Sprachwechsel')
  })
})
