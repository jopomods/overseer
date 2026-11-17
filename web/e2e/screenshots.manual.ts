// Manual helper (not part of the test suite): renders key pages to PNGs for review.
import { chromium } from '@playwright/test'
import { mockApi } from './mock-api.ts'

const out = process.argv[2]
const base = 'http://127.0.0.1:4391'
const browser = await chromium.launch()
for (const [name, width, height] of [['phone', 390, 844], ['desktop', 1280, 860]] as const) {
  for (const path of ['/', '/server', '/energy', '/energy/0', '/account', '/login']) {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 })
    await mockApi(page, { loggedIn: path !== '/login' })
    await page.goto(base + path)
    await page.waitForTimeout(700)
    await page.screenshot({ path: `${out}/${name}${path.replace(/\//g, '_') || '_'}.png`, fullPage: false })
    await page.close()
  }
}
await browser.close()
