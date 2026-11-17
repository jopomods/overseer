import { expect, test, type Page } from '@playwright/test'

/**
 * Every route of the app. Add new pages here so they get the layout checks
 * at all viewport widths (see playwright.config.ts).
 */
const ROUTES = ['/']

async function mockApi(page: Page) {
  await page.route('**/api/v1/health', (route) =>
    route.fulfill({ json: { status: 'ok', version: '0.0.0-test', dataAgeMillis: 500 } }),
  )
}

/** Measures whether anything makes the page wider than the viewport. */
async function measureOverflow(page: Page) {
  return page.evaluate(() => {
    const doc = document.documentElement
    const offenders = [...document.querySelectorAll<HTMLElement>('body *')]
      .filter((el) => el.getBoundingClientRect().right > doc.clientWidth + 1)
      .slice(0, 5)
      .map((el) => `${el.tagName.toLowerCase()}${el.className ? `.${el.className}` : ''}`)
    return { scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth, offenders }
  })
}

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await measureOverflow(page)
  expect(overflow.offenders, `elements overflow the viewport: ${overflow.offenders.join(', ')}`).toEqual([])
  expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth)
}

for (const route of ROUTES) {
  test(`no horizontal overflow on ${route}`, async ({ page }) => {
    await mockApi(page)
    await page.goto(route)
    await expect(page.getByTestId('health')).toContainText('ok')
    await expectNoHorizontalOverflow(page)
  })
}

test('overflow detector catches content wider than the viewport', async ({ page }) => {
  await mockApi(page)
  await page.goto('/')
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
