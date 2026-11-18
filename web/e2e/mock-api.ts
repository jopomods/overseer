import type { Page } from '@playwright/test'
import { createMockBackend, type MockOptions } from '../mock/backend.ts'

export type { MockOptions }

/** Routes every API call of the page to a fresh mock backend (see mock/backend.ts). */
export async function mockApi(page: Page, options: MockOptions = {}) {
  const backend = createMockBackend(options)
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/stream') {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: ': test\n\n' })
    }
    let body: unknown
    try {
      body = request.postDataJSON()
    } catch {
      body = undefined
    }
    const response = backend.handle({ method: request.method(), url, body })
    return response.json === undefined
      ? route.fulfill({ status: response.status })
      : route.fulfill({ status: response.status, json: response.json })
  })
}
