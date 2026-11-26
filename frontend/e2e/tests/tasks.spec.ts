import { expect, openCalendar, test } from '../support/fixtures'

test.describe('tasks', () => {
  test('quick entry, subtask, complete and reopen', async ({ page, user }) => {
    void user
    await openCalendar(page)
    await page.getByTitle('Tasks', { exact: true }).click()
    const panel = page.locator('.v-navigation-drawer--right')
    await expect(panel.getByText('No open tasks')).toBeVisible()

    await panel.getByLabel('New task').fill('Buy milk')
    await panel.getByLabel('New task').press('Enter')
    await expect(panel.getByText('Buy milk')).toBeVisible()

    // Subtask
    await panel.locator('.task', { hasText: 'Buy milk' }).hover()
    await panel.getByTitle('Add subtask').first().click()
    await panel.getByLabel('New subtask').fill('Oat milk')
    await panel.getByLabel('New subtask').press('Enter')
    await expect(panel.getByText('Oat milk')).toBeVisible()

    // Completing the parent completes the subtask too
    await panel.locator('.task', { hasText: 'Buy milk' }).getByTitle('Mark as done').first().click()
    await expect(panel.getByText('No open tasks')).toBeVisible()

    await panel.locator('.completed-head').click()
    await expect(panel.getByTitle('Mark as open')).toHaveCount(2)
    await panel.locator('.task', { hasText: 'Buy milk' }).getByTitle('Mark as open').first().click()
    await expect(panel.getByText('No open tasks')).toBeHidden()
    await expect(panel.locator('.task', { hasText: 'Buy milk' }).getByTitle('Mark as done').first()).toBeVisible()
  })

  test('a task with a due date appears in the calendar', async ({ page, user }) => {
    void user
    await openCalendar(page)
    await page.getByRole('button', { name: /Create/ }).click()
    await page.locator('.v-menu').getByText('Task', { exact: true }).click()

    const dialog = page.getByRole('dialog').filter({ hasText: 'New task' })
    await dialog.getByLabel('Title').fill('Pay rent')
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('Task created')).toBeVisible()

    const item = page.locator('.item', { hasText: 'Pay rent' })
    await expect(item).toBeVisible()
    await item.getByTitle('Mark as done').click()
    await expect(page.getByText('Task completed')).toBeVisible()
    await expect(item.getByTitle('Mark as open')).toBeVisible()
  })
})
