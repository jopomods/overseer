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

  test('flux network: zones, device filter and search', async ({ page }) => {
    await mockApi(page)
    await page.goto('/')
    await page.getByRole('link', { name: 'Energy' }).first().click()
    await expect(page).toHaveURL(/\/energy$/)
    await page.getByTestId('network-list').getByRole('link').first().click()
    await expect(page.getByTestId('zones')).toContainText('Power plant')
    const rows = page.getByTestId('device-list').getByRole('listitem')
    await expect(rows).toHaveCount(200)
    await page.getByRole('tab', { name: /Storages/ }).click()
    await expect(rows).toHaveCount(50)
    await page.getByPlaceholder('Search devices').fill('Device 2')
    await expect(rows.first()).toContainText('Device 2')
    await expect(page.getByTestId('member-list').getByRole('listitem')).toHaveCount(4)
  })

  test('unknown network shows a not-found message', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/999')
    await expect(page.getByText('does not exist')).toBeVisible()
  })

  test('installable: manifest and service worker that never caches API data', async ({ page }) => {
    await mockApi(page)
    const manifest = await page.request.get('/manifest.webmanifest')
    expect((await manifest.json()).display).toBe('standalone')

    await page.goto('/')
    await page.evaluate(() => navigator.serviceWorker.ready)
    await page.reload()
    await page.goto('/server')
    await expect(page.getByTestId('player-list')).toBeVisible()

    const cached = await page.evaluate(async () => {
      const urls: string[] = []
      for (const key of await caches.keys()) {
        for (const request of await (await caches.open(key)).keys()) urls.push(new URL(request.url).pathname)
      }
      return urls
    })
    expect(cached).toContain('/')
    expect(cached.filter((u) => u.startsWith('/api/'))).toEqual([])
  })

  test('history chart: ranges, axis switch and offline marker', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    const chart = page.getByTestId('chart-flow')
    await expect(chart.locator('.recharts-area').first()).toBeVisible()
    // Uptime axis: the restart inside the last 24 h shows the real offline time.
    await expect(chart.getByText(/14 h/)).toBeVisible()
    await chart.getByRole('radio', { name: 'Calendar' }).click()
    await expect(chart.locator('.recharts-reference-area')).toHaveCount(1)
    await chart.getByRole('radio', { name: '7 d' }).click()
    await expect(chart.getByRole('radio', { name: '7 d' })).toHaveAttribute('aria-checked', 'true')
  })

  test('zones can be changed in the UI and the storage forecast is shown', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    await expect(page.getByText(/Empty in ~/)).toBeVisible()

    const rows = page.getByTestId('device-list').getByRole('listitem')
    await rows.first().getByRole('button', { name: 'Change zone' }).click()
    const editor = page.getByTestId('zone-editor')
    await editor.getByLabel('Zone').fill('Reactor hall')
    await editor.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByTestId('zones')).toContainText('Reactor hall')
  })

  test('players without edit rights see no edit buttons', async ({ page }) => {
    await mockApi(page, { role: 'player' })
    await page.goto('/energy/0')
    await expect(page.getByTestId('device-list')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Change zone' })).toHaveCount(0)
  })
})
