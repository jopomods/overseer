import { defineConfig, devices } from '@playwright/test'

/**
 * Layout tests run every page at every target width (ADR-0009):
 * phone 360/390, tablet 768/1024 landscape, desktop 1280/1920. Behaviour tests do not change between
 * neighbouring widths: the edit specs run on the smallest phone and on desktop, the flow and tier
 * specs on desktop only.
 * Runs against the production build via `vite preview`; the API is mocked per test.
 */
/** Widths that run every spec; the others run only the layout tests. */
const FULL = new Set(['phone-360', 'desktop-1280'])
/**
 * Tests tagged @desktop run only at 1280, @phone only at 360. A tag keeps them out of the other
 * projects instead of skipping them there: skipped tests still count when Playwright splits the
 * tests into shards, which left one shard with 40 skipped tests and the others with all the work.
 */
const ONLY = { '@desktop': 'desktop-1280', '@phone': 'phone-360' } as const
const notHere = (name: string) => {
  const tags = Object.entries(ONLY).filter(([, project]) => project !== name).map(([tag]) => tag)
  return new RegExp(tags.join('|'))
}

const viewports = [
  { name: 'phone-360', width: 360, height: 780 },
  { name: 'phone-390', width: 390, height: 844 },
  { name: 'tablet-768', width: 768, height: 1024 },
  { name: 'tablet-1024-landscape', width: 1024, height: 768 },
  { name: 'desktop-1280', width: 1280, height: 800 },
  { name: 'desktop-1920', width: 1920, height: 1080 },
]

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  // CI runners have 4 vCPU (public repository).
  workers: process.env.CI ? 4 : undefined,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // /energy/0 loads history for 200+ devices; with 4 workers on a CI runner rendering the charts
  // can take longer than the default 5 s.
  expect: { timeout: process.env.CI ? 15_000 : 5_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4391',
    trace: 'retain-on-failure',
  },
  projects: viewports.map(({ name, width, height }) => ({
    name,
    testMatch: FULL.has(name) ? undefined : /layout\.spec\.ts$/,
    grepInvert: notHere(name),
    use: {
      ...devices['Desktop Chrome'],
      viewport: { width, height },
      isMobile: width < 768,
      hasTouch: width < 1024,
    },
  })),
  webServer: {
    command: 'npm run preview -- --host 127.0.0.1 --port 4391 --strictPort',
    url: 'http://127.0.0.1:4391',
    // Never attach to a foreign server that happens to use the port.
    reuseExistingServer: false,
  },
})
