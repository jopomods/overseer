import createClient from 'openapi-fetch'
import type { paths } from './schema.gen'

/**
 * Typed API client generated from api/openapi.yaml (ADR-0010).
 * Same origin only: the dashboard is always served by the Overseer web server,
 * and in dev Vite proxies /api to it. Cookies are sent automatically.
 */
export const api = createClient<paths>({ baseUrl: '/', credentials: 'same-origin' })
