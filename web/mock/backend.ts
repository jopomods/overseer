import fs from 'node:fs'

/**
 * Mock Overseer backend, shared by the Playwright tests (e2e/mock-api.ts) and the Vite dev
 * server (`npm run dev:mock`). Framework-free: request in, response out.
 *
 * Two datasets: extreme (very long names, 200 devices, huge numbers; finds layout bugs)
 * and realistic (last frames of the committed real-world recording).
 */
export interface MockOptions {
  role?: 'player' | 'admin' | 'super_admin'
  loggedIn?: boolean
  /** Sequence returned by GET /api/v1/auth/link-codes/current, last value repeats. */
  linkStatuses?: Array<'pending' | 'confirmed' | 'expired'>
  /** Serve the last frames of the committed real-world recording instead of extreme data. */
  realistic?: boolean
}

interface Frame {
  provider: string
  wall: string
  uptimeMillis: number
  session: number
  resources: Array<{ type: string; id: string; attributes: Record<string, string>; metrics: Record<string, number> }>
}

/** Last frame per provider of fixtures/recordings/flux-real-01.jsonl, in API snapshot shape. */
export function realSnapshot(provider: string) {
  const file = new URL('../../fixtures/recordings/flux-real-01.jsonl', import.meta.url)
  const lines = fs.readFileSync(file, 'utf8').trim().split('\n')
  const frame = lines.slice(1).map((l) => JSON.parse(l) as Frame).filter((f) => f.provider === provider).at(-1)!
  return {
    provider,
    at: { wall: now(), uptimeMillis: frame.uptimeMillis, session: frame.session },
    resources: frame.resources.map((r) => ({
      ref: { provider, type: r.type, id: r.id, key: `${provider}/${r.type}/${r.id}` },
      attributes: r.attributes,
      metrics: r.metrics,
    })),
  }
}

const LONG = 'Extremely-Long-Name-That-Does-Not-Fit-Anywhere-On-A-Phone-Screen_xXx_1234567890'
const now = () => new Date().toISOString()

export function serverSnapshot() {
  const players = Array.from({ length: 40 }, (_, i) => ({
    ref: { provider: 'server', type: 'player', id: `uuid-${i}`, key: `server/player/uuid-${i}` },
    attributes: { name: i === 0 ? LONG : `Player${i}`, dimension: i % 2 ? 'minecraft:overworld' : `${LONG}:dimension` },
    metrics: { ping: i * 37 },
  }))
  return {
    provider: 'server',
    at: { wall: now(), uptimeMillis: 123_456_789, session: 3 },
    resources: [
      {
        ref: { provider: 'server', type: 'server', id: 'main', key: 'server/server/main' },
        attributes: { version: '1.20.1', motd: `${LONG} ${LONG}`, maxPlayers: '100' },
        metrics: { tps: 17.3, mspt: 57.8, players_online: 40 },
      },
      ...players,
      ...['minecraft:overworld', 'minecraft:the_nether', `${LONG}:void_dimension`].map((id, i) => ({
        ref: { provider: 'server', type: 'dimension', id, key: `server/dimension/${id}` },
        attributes: { name: id },
        metrics: { loaded_chunks: 123456 * (i + 1), entities: 98765 * (i + 1), dimension_mspt: 12.34 },
      })),
      {
        ref: { provider: 'server', type: 'jvm', id: 'main', key: 'server/jvm/main' },
        attributes: { java: '17.0.21' },
        metrics: { memory_used: 13.7e9, memory_max: 16e9 },
      },
    ],
  }
}

export function fluxSnapshot() {
  const zones = ['Power plant', 'Factory', LONG, '']
  const types = ['plug', 'point', 'storage', 'controller']
  const networks = Array.from({ length: 12 }, (_, i) => ({
    ref: { provider: 'flux', type: 'network', id: String(i), key: `flux/network/${i}` },
    attributes: { name: i === 0 ? LONG : `Network ${i}`, color: '#ff7a00', security: i % 3 === 0 ? 'public' : 'private', owner: 'uuid-0' },
    metrics: { energy_input: 9.2e18, energy_output: 4.1e17, buffer: 1e12, energy: 1e15 },
  }))
  // Network 0: 200 devices across zones, some unloaded, some with extreme names.
  const devices = Array.from({ length: 200 }, (_, i) => {
    const zone = zones[i % zones.length]
    const type = types[i % types.length]
    const name = i === 1 ? LONG : zone ? `${zone}: Device ${i}` : i % 8 === 3 ? '' : `Device ${i}`
    const id = `minecraft:overworld@${i * 1000},64,-${i * 1000}`
    return {
      ref: { provider: 'flux', type: 'device', id, key: `flux/device/${id}` },
      attributes: {
        network: '0', type, name, dimension: i === 5 ? `${LONG}:dimension` : 'minecraft:overworld',
        pos: `${i * 1000},64,-${i * 1000}`, priority: String(i % 5), limit: i % 2 ? '999999999999' : '0',
        loaded: String(i % 7 !== 0), surge: String(i % 11 === 0),
      },
      metrics: { transfer: type === 'point' ? -9.2e18 / (i + 1) : 9.2e18 / (i + 1), buffer: 5e15 },
    }
  })
  // Two plugs with the same zone and name: grouped as "Twin ×2" in the device flow.
  for (const n of [0, 1]) {
    const id = `minecraft:overworld@-5${n},70,5`
    devices.push({
      ref: { provider: 'flux', type: 'device', id, key: `flux/device/${id}` },
      attributes: {
        network: '0', type: 'plug', name: 'Power plant: Twin', dimension: 'minecraft:overworld',
        pos: `-5${n},70,5`, priority: '0', limit: '0', loaded: 'true', surge: 'false',
      },
      metrics: { transfer: 4e18, buffer: 0 },
    })
  }
  const members = ['owner', 'admin', 'user', 'blocked'].map((access, i) => ({
    ref: { provider: 'flux', type: 'member', id: `0:uuid-${i}`, key: `flux/member/0:uuid-${i}` },
    attributes: { network: '0', player: `uuid-${i}`, name: i === 0 ? LONG : `Member${i}`, access },
    metrics: {},
  }))
  return {
    provider: 'flux',
    at: { wall: now(), uptimeMillis: 123_456_789, session: 3 },
    resources: [...networks, ...devices, ...members],
  }
}

interface MockGrid {
  id: string
  owner: string
  ownerName: string
  state?: string
  powered?: boolean
  booting?: boolean
  nodes: number
  controllers: number
  cpus: number
  metrics: Record<string, number>
}

function gridResource(g: MockGrid) {
  return {
    ref: { provider: 'ae2', type: 'grid', id: g.id, key: `ae2/grid/${g.id}` },
    attributes: {
      dimension: g.id.slice(0, g.id.lastIndexOf('@')),
      pos: g.id.slice(g.id.lastIndexOf('@') + 1),
      owner: g.owner,
      ownerName: g.ownerName,
      state: g.state ?? 'controller_online',
      booting: String(g.booting ?? false),
      powered: String(g.powered ?? true),
      nodes: String(g.nodes),
      controllers: String(g.controllers),
      otherTypes: '0',
      cpus: String(g.cpus),
      cpuStorage: String(g.cpus * 65_536),
      coProcessors: String(g.cpus * 2),
      idlePower: '42.5',
    },
    metrics: g.metrics,
  }
}

/** AE2 grids. Extreme: 9 grids, huge numbers, every state; realistic: a typical base. */
export function ae2Snapshot(realistic = false) {
  const grids: MockGrid[] = realistic
    ? [
        {
          id: 'minecraft:overworld@120,64,-340', owner: '00000000-0000-0000-0000-000000000001', ownerName: 'Developer',
          nodes: 412, controllers: 6, cpus: 3,
          metrics: { power_stored: 1.6e6, power_max: 2.4e6, power_usage: 186.5, power_injection: 210, item_types: 2843, items: 18_532_118, fluid_types: 37, fluids: 4_210_000, channels: 214, cpus_busy: 1 },
        },
        {
          id: 'minecraft:the_nether@8,40,16', owner: '00000000-0000-0000-0000-000000000001', ownerName: 'Developer',
          nodes: 23, controllers: 1, cpus: 0,
          metrics: { power_stored: 3.1e4, power_max: 1.6e5, power_usage: 12.25, power_injection: 0, item_types: 41, items: 9_876, fluid_types: 0, fluids: 0, channels: 9, cpus_busy: 0 },
        },
      ]
    : Array.from({ length: 9 }, (_, i) => ({
        id: i === 1 ? `${LONG}:dimension@-29999999,-64,29999999` : `minecraft:overworld@${i * 1000},64,-${i * 1000}`,
        owner: `uuid-${i % 3}`,
        ownerName: i === 0 ? LONG : `Player${i % 3}`,
        state: i === 2 ? 'controller_conflict' : 'controller_online',
        powered: i !== 3,
        booting: i === 4,
        nodes: 12_345 * (i + 1),
        controllers: 68,
        cpus: i === 5 ? 0 : 64,
        metrics: {
          power_stored: i === 3 ? 0 : 9.2e15 / (i + 1), power_max: 9.2e15, power_usage: 1.23e9 / (i + 1), power_injection: 2e9,
          item_types: 987_654, items: 9.2e18, fluid_types: 12_345, fluids: 9.2e18, channels: 32_768 * (i + 1), cpus_busy: i === 5 ? 0 : 63,
        },
      }))
  return {
    provider: 'ae2',
    at: { wall: now(), uptimeMillis: 123_456_789, session: 3 },
    resources: grids.map(gridResource),
  }
}

// Two uptime sessions: 10 h, then 14 h offline, then 16 h up to now.
const HOUR = 3_600_000
const UPTIME_NOW = 26 * HOUR
function sessionsBody() {
  const end = Date.now()
  const s2Start = end - 16 * HOUR
  const s1End = s2Start - 14 * HOUR
  return {
    uptimeMillis: UPTIME_NOW,
    sessions: [
      { session: 1, startedAt: new Date(s1End - 10 * HOUR).toISOString(), startedUptime: 0, lastAt: new Date(s1End).toISOString(), lastUptime: 10 * HOUR },
      { session: 2, startedAt: new Date(s2Start).toISOString(), startedUptime: 10 * HOUR, lastAt: new Date(end).toISOString(), lastUptime: UPTIME_NOW },
    ],
  }
}

function historyBody(url: URL) {
  const sessions = sessionsBody().sessions
  const metrics = url.searchParams.getAll('metric')
  const to = Number(url.searchParams.get('to') ?? UPTIME_NOW)
  const from = Math.max(0, Number(url.searchParams.get('from') ?? to - 24 * HOUR))
  const points = Number(url.searchParams.get('points') ?? 240)
  const bucket = Math.max(1000, Math.ceil((to - from) / points))
  const wallAt = (u: number) => {
    const s = u >= sessions[1].startedUptime ? sessions[1] : sessions[0]
    return Date.parse(s.startedAt) + (u - s.startedUptime)
  }
  return {
    resource: url.searchParams.get('resource'),
    resolution: bucket < 60_000 ? 'raw' : bucket < HOUR ? 'minute' : 'hour',
    from, to, bucketMillis: bucket,
    // One series per requested resource and metric.
    series: url.searchParams.getAll('resource').flatMap((resource, r) =>
      metrics.map((metric, m) => ({
        resource,
        metric,
        points: Array.from({ length: Math.floor((to - from) / bucket) }, (_, i) => {
          const t = from + i * bucket
          // Stored energy drains steadily (forecast "empty in"); flows oscillate.
          const v =
            metric === 'energy'
              ? 1e15 * (1 - (t - from) / (2 * (to - from)))
              : (9.2e18 / (r + 1)) * (0.6 + 0.4 * Math.sin(i / 12 + m + r))
          return { t, wall: new Date(wallAt(t)).toISOString(), session: t >= 10 * HOUR ? 2 : 1, min: v * 0.9, max: v * 1.1, avg: v, last: v }
        }),
      })),
    ),
  }
}

export interface MockResponse {
  status: number
  json?: unknown
}

export interface MockRequest {
  method: string
  url: URL
  /** Parsed JSON body, if any. */
  body?: unknown
}

/**
 * A stateful mock backend: overrides and login state live per instance. Used by the
 * Playwright tests (e2e/mock-api.ts) and the Vite dev server (`npm run dev:mock`).
 */
export function createMockBackend(options: MockOptions = {}) {
  const role = options.role ?? 'admin'
  const loggedIn = options.loggedIn ?? true
  const linkStatuses = [...(options.linkStatuses ?? ['pending'])]
  const overrides: Array<{ resource: string; key: string; value: string }> = options.realistic
    ? [{ resource: 'ae2/grid/minecraft:overworld@120,64,-340', key: 'name', value: 'Main base' }]
    : [{ resource: 'ae2/grid/minecraft:overworld@0,64,-0', key: 'name', value: LONG.slice(0, 48) }]
  const editable = role !== 'player'
  let loggedInNow = false
  const withEdit = (snapshot: { resources: Array<Record<string, unknown>> }) => ({
    ...snapshot,
    resources: snapshot.resources.map((r) => ({ ...r, canEdit: editable })),
  })
  const json = (body: unknown, status = 200): MockResponse => ({ status, json: body })

  /** Current snapshot of a provider (also pushed over SSE by the dev server). */
  const snapshot = (provider: 'server' | 'flux' | 'ae2') =>
    provider === 'server'
      ? options.realistic
        ? realSnapshot('server')
        : serverSnapshot()
      : provider === 'ae2'
        ? withEdit(ae2Snapshot(options.realistic))
        : withEdit(options.realistic ? realSnapshot('flux') : fluxSnapshot())

  function handle({ method, url, body }: MockRequest): MockResponse {
    const path = url.pathname
    if (path === '/api/v1/health') return json({ status: 'ok', version: '0.0.0-mock', dataAgeMillis: 500 })
    if (path === '/api/v1/auth/link-codes' && method === 'POST') {
      return json({ code: 'ABC-234', expiresAt: new Date(Date.now() + 300_000).toISOString() }, 201)
    }
    if (path === '/api/v1/auth/link-codes/current') {
      const status = linkStatuses.length > 1 ? linkStatuses.shift()! : linkStatuses[0]
      if (status === 'confirmed') loggedInNow = true
      return json({ status })
    }
    if (!loggedIn && !loggedInNow) return json({ error: { code: 'unauthorized', message: 'no' } }, 401)

    switch (path) {
      case '/api/v1/me':
        return json({
          uuid: '00000000-0000-0000-0000-000000000001',
          name: options.realistic ? 'Developer' : LONG,
          role,
          deviceToken: false,
        })
      case '/api/v1/providers':
        return json([
          { id: 'server', displayName: 'Server', capabilities: ['metrics'] },
          { id: 'flux', displayName: 'Flux Networks', capabilities: ['metrics'] },
          { id: 'ae2', displayName: 'Applied Energistics 2', capabilities: ['metrics'] },
        ])
      case '/api/v1/providers/server/snapshot':
        return json(snapshot('server'))
      case '/api/v1/providers/flux/snapshot':
        return json(snapshot('flux'))
      case '/api/v1/providers/ae2/snapshot':
        return json(snapshot('ae2'))
      case '/api/v1/admin/collectors':
        return json({
          collectors: [
            { provider: 'server', baseIntervalTicks: 20, currentIntervalTicks: 20, budgetNanos: 2e6, runs: 1e6, errors: 0, overruns: 0, lastNanos: 230000, avgNanos: 234000, maxNanos: 1.9e6, throttled: false },
            { provider: 'flux', baseIntervalTicks: 20, currentIntervalTicks: 320, budgetNanos: 2e6, runs: 10, errors: 3, overruns: 9, lastNanos: 9e6, avgNanos: 8e6, maxNanos: 3e7, throttled: true, lastError: `IllegalStateException: ${LONG}` },
          ],
          droppedSnapshots: 0,
          queuedSnapshots: 0,
        })
      case '/api/v1/overrides': {
        if (method === 'PUT') {
          if (!editable) return json({ error: { code: 'forbidden', message: 'no' } }, 403)
          const o = body as { resource: string; key: string; value: string | null }
          const i = overrides.findIndex((x) => x.resource === o.resource && x.key === o.key)
          if (i >= 0) overrides.splice(i, 1)
          if (o.value) overrides.push({ resource: o.resource, key: o.key, value: o.value })
          return { status: 204 }
        }
        const provider = url.searchParams.get('provider')
        return json(overrides.filter((o) => o.resource.startsWith(`${provider}/`)))
      }
      case '/api/v1/admin/storage':
        return json({
          fileBytes: 3.2 * 1024 ** 3,
          walBytes: 12_345_678,
          settings: { rawMillis: 48 * HOUR, minuteMillis: 14 * 24 * HOUR, hourMillis: 365 * 24 * HOUR, warnBytes: 2 * 1024 ** 3 },
          providers: [
            { provider: 'flux', series: 1234, raw: 98_765_432, minute: 1_234_567, hour: 45_678 },
            { provider: LONG.toLowerCase().slice(0, 40), series: 1, raw: 0, minute: 0, hour: 0 },
          ],
        })
      case '/api/v1/admin/storage/settings':
      case '/api/v1/admin/storage/vacuum':
        return { status: 204 }
      case '/api/v1/admin/storage/purge':
        return json({ deletedRows: 42 })
      case '/api/v1/sessions':
        return json(sessionsBody())
      case '/api/v1/history':
        return json(historyBody(url))
      case '/api/v1/me/credentials':
        return json([
          { id: 1, kind: 'session', name: `Mozilla/5.0 ${LONG}`, createdAt: now(), lastUsedAt: now(), current: true },
          { id: 2, kind: 'device_token', name: LONG.slice(0, 64), createdAt: now(), lastUsedAt: now(), current: false },
        ])
      case '/api/v1/auth/logout':
        return { status: 204 }
      default:
        return json({ error: { code: 'not_found', message: path } }, 404)
    }
  }

  return { handle, snapshot }
}
