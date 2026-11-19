import { expect, test, type Page } from '@playwright/test'
import { mockApi } from './mock-api.ts'

/**
 * Every route of the app with a marker that proves the page rendered. Add new pages
 * here so they get the layout checks at all viewport widths (playwright.config.ts).
 */
const ROUTES: Array<{ path: string; ready: (page: Page) => Promise<void> }> = [
  { path: '/', ready: (p) => expect(p.getByRole('heading', { level: 1 })).toBeVisible() },
  { path: '/server', ready: (p) => expect(p.getByTestId('player-list')).toBeVisible() },
  { path: '/energy', ready: (p) => expect(p.getByTestId('network-list')).toBeVisible() },
  { path: '/energy/0', ready: (p) => expect(p.getByTestId('device-list')).toBeVisible() },
  { path: '/ae2', ready: (p) => expect(p.getByTestId('grid-list')).toBeVisible() },
  { path: '/ae2/minecraft:overworld@0,64,-0', ready: (p) => expect(p.getByTestId('grid-details')).toBeVisible() },
  { path: '/admin/storage', ready: (p) => expect(p.getByTestId('storage-providers')).toBeVisible() },
  { path: '/account', ready: (p) => expect(p.getByTestId('credentials')).toBeVisible() },
  { path: '/login', ready: (p) => expect(p.getByTestId('link-command')).toBeVisible() },
]

/** Measures whether anything makes the page wider than the viewport. */
async function measureOverflow(page: Page) {
  return page.evaluate(() => {
    const doc = document.documentElement
    const offenders = [...document.querySelectorAll<HTMLElement>('body *')]
      .filter((el) => el.getBoundingClientRect().right > doc.clientWidth + 1)
      .slice(0, 5)
      .map((el) => `${el.tagName.toLowerCase()}${typeof el.className === 'string' && el.className ? `.${el.className.split(' ').slice(0, 3).join('.')}` : ''}`)
    return { scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth, offenders }
  })
}

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await measureOverflow(page)
  expect(overflow.offenders, `elements overflow the viewport: ${overflow.offenders.join(', ')}`).toEqual([])
  expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth)
}

for (const route of ROUTES) {
  test(`no horizontal overflow on ${route.path}`, async ({ page }) => {
    await mockApi(page, { loggedIn: route.path !== '/login' })
    await page.goto(route.path)
    await route.ready(page)
    await expectNoHorizontalOverflow(page)
  })
}

// The same pages with real (anonymized) data from fixtures/recordings.
for (const path of ['/energy/1', '/server']) {
  test(`no horizontal overflow on ${path} with real data`, async ({ page }) => {
    await mockApi(page, { realistic: true })
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
  })
}

test('no horizontal overflow with the zone editor open', async ({ page }) => {
  await mockApi(page)
  await page.goto('/energy/0')
  await page.getByRole('button', { name: 'Change zone' }).first().click()
  await expect(page.getByTestId('zone-editor')).toBeVisible()
  await expectNoHorizontalOverflow(page)
})

test('no horizontal overflow with the Flux editors open', async ({ page }) => {
  await mockApi(page)
  await page.goto('/energy/0')
  await page.getByTestId('network-edit').click()
  await expect(page.getByTestId('network-settings')).toBeVisible()
  await page.getByRole('checkbox').nth(1).check()
  await page.getByRole('checkbox').nth(2).check()
  await page.getByTestId('edit-selected').click()
  await expect(page.getByTestId('device-editor')).toBeVisible()
  await expectNoHorizontalOverflow(page)
})

test('no horizontal overflow with the AE2 editors open', async ({ page }) => {
  // Extreme data: CPU names of 50+ characters, huge numbers, many drives.
  await mockApi(page)
  await page.goto('/ae2/minecraft:overworld@0,64,-0')
  const card = page.getByTestId('cpu-list').getByRole('listitem').first()
  await card.getByRole('button', { expanded: false }).click()
  await card.getByRole('button', { name: 'Edit CPU' }).click()
  await expect(card.getByTestId('cpu-edit')).toBeVisible()
  await page.getByTestId('drive-list').getByRole('button', { name: 'Change priority' }).first().click()
  await expect(page.getByTestId('drive-priority')).toBeVisible()
  await expectNoHorizontalOverflow(page)
})

test('overflow detector catches content wider than the viewport', async ({ page }) => {
  await mockApi(page)
  await page.goto('/login')
  await page.evaluate(() => {
    const wide = document.createElement('div')
    wide.className = 'too-wide'
    wide.style.width = '4000px'
    wide.style.height = '1px'
    document.body.appendChild(wide)
  })
  const overflow = await measureOverflow(page)
  expect(overflow.offenders).toContain('div.too-wide')
})
