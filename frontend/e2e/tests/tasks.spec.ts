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

  test('own order by dragging and Alt+arrow keys, kept after reloading', async ({ page, user }) => {
    void user
    await openCalendar(page)
    await page.getByTitle('Tasks', { exact: true }).click()
    const panel = page.locator('.v-navigation-drawer--right')
    const titles = panel.locator('.block > .task .name')

    // New tasks come first
    for (const title of ['Alpha', 'Bravo', 'Charlie']) {
      await panel.getByLabel('New task').fill(title)
      await panel.getByLabel('New task').press('Enter')
      await expect(panel.locator('.block', { hasText: title })).toBeVisible()
    }
    await expect(titles).toHaveText(['Charlie', 'Bravo', 'Alpha'])

    // Drag Alpha to the top
    const alpha = await panel.locator('.block', { hasText: 'Alpha' }).locator('.name').boundingBox()
    const top = await panel.locator('.block', { hasText: 'Charlie' }).boundingBox()
    await page.mouse.move(alpha!.x + 20, alpha!.y + alpha!.height / 2)
    await page.mouse.down()
    await page.mouse.move(alpha!.x + 20, top!.y + 2, { steps: 10 })
    await page.mouse.up()
    await expect(titles).toHaveText(['Alpha', 'Charlie', 'Bravo'])
    // Dropping it did not open the task
    await expect(page.getByRole('dialog')).toBeHidden()

    // Bravo one up with the keyboard
    await panel.locator('.block > .task', { hasText: 'Bravo' }).focus()
    await page.keyboard.press('Alt+ArrowUp')
    await expect(titles).toHaveText(['Alpha', 'Bravo', 'Charlie'])

    // Subtasks keep their own order too
    await panel.locator('.task', { hasText: 'Alpha' }).first().hover()
    await panel.getByTitle('Add subtask').first().click()
    for (const title of ['One', 'Two']) {
      await panel.getByLabel('New subtask').fill(title)
      await panel.getByLabel('New subtask').press('Enter')
      await expect(panel.locator('.task.sub', { hasText: title })).toBeVisible()
    }
    await panel.getByLabel('New subtask').press('Escape')
    const subs = panel.locator('.task.sub .name')
    await expect(subs).toHaveText(['One', 'Two'])
    await panel.locator('.task.sub', { hasText: 'Two' }).focus()
    await page.keyboard.press('Alt+ArrowUp')
    await expect(subs).toHaveText(['Two', 'One'])

    await page.reload()
    await page.getByTitle('Tasks', { exact: true }).click()
    await expect(titles).toHaveText(['Alpha', 'Bravo', 'Charlie'])
    await expect(subs).toHaveText(['Two', 'One'])

    // Sorted by date there is nothing to drag at the top level
    await panel.getByTitle('More actions').click()
    await page.locator('.v-overlay--active .v-list-item', { hasText: 'Date' }).click()
    await expect(panel.locator('.block > .task[aria-keyshortcuts]')).toHaveCount(0)
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
