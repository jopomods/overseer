import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api.ts'

// Behavior, not layout: run once at desktop width.
test.describe('flows', () => {
  test.skip(({ viewport }) => (viewport?.width ?? 0) !== 1280, 'desktop only')

  test('not logged in redirects to login', async ({ page }) => {
    await mockApi(page, { loggedIn: false })
    await page.goto('/server')
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByTestId('link-command')).toContainText('/ov link ABC-234')
  })

  test('confirming the code in game logs in', async ({ page }) => {
    await mockApi(page, { loggedIn: false, linkStatuses: ['pending', 'confirmed'] })
    await page.goto('/login')
    await expect(page.getByTestId('link-command')).toBeVisible()
    await expect(page).toHaveURL(/\/$/, { timeout: 10_000 })
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  })

  test('players do not see admin sections', async ({ page }) => {
    await mockApi(page, { role: 'player' })
    await page.goto('/server')
    await expect(page.getByTestId('player-list')).toBeVisible()
    await expect(page.getByTestId('collectors')).toHaveCount(0)
  })

  test('language and energy unit can be switched', async ({ page }) => {
    await mockApi(page)
    await page.goto('/account')
    await page.getByRole('button', { name: 'Deutsch' }).click()
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Konto')
    await page.getByRole('button', { name: 'English' }).click()
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Account')
  })
})
