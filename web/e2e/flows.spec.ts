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
    await expect(rows).toHaveCount(202)
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

  test('history chart: server uptime by default, calendar axis from settings, ranges', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    const chart = page.getByTestId('chart-flow')
    await expect(chart.locator('.recharts-area').first()).toBeVisible()
    // Server uptime axis: the restart inside the last 24 h shows the real offline time.
    await expect(chart.getByText(/14 h/)).toBeVisible()
    await expect(chart.getByRole('radio', { name: 'Calendar' })).toHaveCount(0)

    // The axis is a viewer setting on the account page.
    await page.goto('/account')
    await page.getByRole('button', { name: 'Calendar' }).click()
    await page.goto('/energy/0')
    await expect(chart.locator('.recharts-reference-area')).toHaveCount(1)
    await chart.getByRole('radio', { name: '7 d' }).click()
    await expect(chart.getByRole('radio', { name: '7 d' })).toHaveAttribute('aria-checked', 'true')
  })

  test('energy flow detail: zones or devices, same-named devices grouped', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto('/energy/1')
    const flow = page.getByTestId('energy-flow')
    await expect(page.getByTestId('flow-sankey')).toContainText('ACG II')
    await expect(page.getByTestId('flow-sankey')).not.toContainText('Kraftwerk')
    await flow.getByRole('radio', { name: 'Devices' }).click()
    await expect(page.getByTestId('flow-sankey')).toContainText('Kraftwerk')
    await expect(page.getByTestId('flow-sankey')).toContainText('ACG II')
  })

  test('energy flow groups devices with the same zone and name', async ({ page }) => {
    await mockApi(page)
    await page.addInitScript(() => localStorage.setItem('overseer.preferences', JSON.stringify({ flowDetail: 'devices' })))
    await page.goto('/energy/0')
    await expect(page.getByTestId('flow-sankey')).toContainText('Twin ×2')
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

  test('energy flow shows generation and consumption per zone', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto('/energy/1')
    const sankey = page.getByTestId('flow-sankey')
    await expect(sankey).toContainText('ACG II')
    await expect(sankey).toContainText('Temp')
  })

  test('zone chart stacks generation up and consumption down', async ({ page }) => {
    await mockApi(page)
    await page.goto('/energy/0')
    const chart = page.getByTestId('zone-chart')
    // Some areas are empty (a zone with only points generates nothing); some must be drawn.
    await expect(chart.locator('.recharts-area-area').first()).toBeAttached()
    expect(await chart.locator('.recharts-area-area').count()).toBeGreaterThan(2)
    await expect(chart).toContainText('Power plant')
    await expect(chart).toContainText('Factory')
  })

  test('ME networks: list, detail and rename', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto('/')
    await page.getByRole('link', { name: 'ME networks' }).first().click()
    const cards = page.getByTestId('grid-list').getByRole('link')
    await expect(cards).toHaveCount(2)
    await expect(cards.first()).toContainText('Main base')
    await expect(cards.nth(1)).toContainText('Network at 8, 40, 16')

    await cards.nth(1).click()
    await expect(page.getByTestId('grid-details')).toContainText('the_nether')
    await page.getByRole('button', { name: 'Rename' }).click()
    await page.getByTestId('rename-form').getByLabel('Name').fill('Nether outpost')
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Nether outpost')
  })

  test('ME network owners share and unshare with players', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto('/ae2/minecraft:overworld@120,64,-340')
    const access = page.getByTestId('grid-access')
    const list = page.getByTestId('share-list')
    await expect(list.getByRole('listitem')).toHaveCount(1)
    await access.getByLabel('Player name').fill('Herobrine')
    await access.getByRole('button', { name: 'Share' }).click()
    await expect(access).toContainText('Unknown player')
    await access.getByLabel('Player name').fill('steve')
    await access.getByRole('button', { name: 'Share' }).click()
    await expect(list.getByRole('listitem')).toHaveCount(2)
    await expect(list).toContainText('Steve')
    await list.getByRole('listitem').filter({ hasText: 'Alex' }).getByRole('button', { name: 'Remove access' }).click()
    await expect(list.getByRole('listitem')).toHaveCount(1)
    await expect(list).not.toContainText('Alex')
  })

  test('players ask for access to hidden ME networks', async ({ page }) => {
    await mockApi(page, { role: 'player', realistic: true })
    await page.goto('/ae2')
    const others = page.getByTestId('requestable-list')
    await expect(others).toContainText("Steve's storage")
    await expect(others).toContainText('Network of Alex')
    await expect(others).not.toContainText('@')
    await others.getByRole('listitem').filter({ hasText: "Steve's storage" }).getByRole('button', { name: 'Ask for access' }).click()
    await expect(others.getByText('Asked')).toHaveCount(2)
  })

  test('owners approve access requests', async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto('/ae2')
    await expect(page.getByTestId('grid-list').getByRole('link').first()).toContainText('1 request(s)')
    await page.goto('/ae2/minecraft:overworld@120,64,-340')
    const requests = page.getByTestId('access-requests')
    await expect(requests).toContainText('Steve')
    await requests.getByRole('button', { name: 'Allow' }).click()
    await expect(page.getByTestId('access-requests')).toHaveCount(0)
    await expect(page.getByTestId('grid-access')).toContainText('Steve')
  })

  test('players cannot rename ME networks', async ({ page }) => {
    await mockApi(page, { role: 'player', realistic: true })
    await page.goto('/ae2/minecraft:overworld@120,64,-340')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Main base')
    await expect(page.getByRole('button', { name: 'Rename' })).toHaveCount(0)
    await expect(page.getByTestId('grid-access')).toHaveCount(0)
  })

  test('storage page: admins only, size warning, purge needs confirmation', async ({ page }) => {
    await mockApi(page)
    await page.goto('/admin/storage')
    await expect(page.getByTestId('storage-overview')).toContainText('Above the warning')
    const flux = page.getByTestId('storage-providers').locator('[data-slot=card]').first()
    await flux.getByRole('button', { name: 'Delete history' }).click()
    await expect(flux.getByText('Delete all history of this module?')).toBeVisible()
    const purge = page.waitForRequest((r) => r.url().endsWith('/api/v1/admin/storage/purge'))
    await flux.getByRole('button', { name: 'Delete history' }).click()
    expect((await purge).postDataJSON()).toEqual({ provider: 'flux' })
    const save = page.waitForRequest((r) => r.url().endsWith('/api/v1/admin/storage/settings'))
    await page.getByTestId('storage-settings').getByRole('button', { name: 'Save' }).click()
    expect((await save).postDataJSON().rawMillis).toBe(48 * 3_600_000)
  })

  test('players do not get the storage page', async ({ page }) => {
    await mockApi(page, { role: 'player' })
    await page.goto('/admin/storage')
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByRole('link', { name: 'Storage' })).toHaveCount(0)
  })
})

test('energy flow is a list on phones', async ({ page, viewport }) => {
  test.skip((viewport?.width ?? 0) !== 360, 'phone only')
  await mockApi(page, { realistic: true })
  await page.goto('/energy/1')
  await expect(page.getByTestId('flow-list')).toContainText('ACG II')
  await expect(page.getByTestId('flow-sankey')).toHaveCount(0)
})
