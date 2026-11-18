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
        ...(type === 'storage' ? { capacity: String(4e16) } : {}),
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
  kind?: string
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
      kind: g.kind ?? 'controller',
    },
    metrics: g.metrics,
  }
}

function cpuResource(grid: string, id: string, attrs: Record<string, string>, metrics: Record<string, number> = {}) {
  return {
    ref: { provider: 'ae2', type: 'cpu', id, key: `ae2/cpu/${id}` },
    attributes: { grid, name: '', storage: '65536', coProcessors: '4', mode: 'any', busy: String(Boolean(attrs.job)), job: '', jobKind: '', jobName: '', jobAmount: '', ...attrs },
    metrics,
  }
}

function driveResource(grid: string, id: string, block: string, cells: string[], bytes: [number, number], types: [number, number]) {
  return {
    ref: { provider: 'ae2', type: 'drive', id, key: `ae2/drive/${id}` },
    attributes: { grid, block, cells: cells.map((c, i) => `${i}=${c}`).join(';') },
    metrics: { bytes_used: bytes[0], bytes_total: bytes[1], types_used: types[0], types_total: types[1] },
  }
}

/** Drives: realistic, a small GregTech base; extreme: 40 drives full of huge add-on cells. */
function driveResources(realistic: boolean) {
  if (realistic) {
    const grid = 'minecraft:overworld@120,64,-340'
    const cell = (size: string, state: string) => `ae2:item_storage_cell_${size}=${state}`
    return [
      driveResource(grid, 'minecraft:overworld@116,64,-338', 'ae2:drive', [cell('64k', 'not_empty'), cell('64k', 'not_empty'), cell('64k', 'types_full'), cell('16k', 'full'), cell('16k', 'not_empty'), 'ae2:fluid_storage_cell_64k=not_empty'], [301_234, 360_448], [298, 378]),
      driveResource(grid, 'minecraft:overworld@116,65,-338', 'ae2:drive', [cell('256k', 'not_empty'), cell('256k', 'empty')], [41_200, 524_288], [12, 126]),
      driveResource(grid, 'minecraft:overworld@114,64,-338', 'ae2:chest', [cell('4k', 'not_empty')], [1_904, 4_096], [3, 63]),
    ]
  }
  return Array.from({ length: 40 }, (_, i) =>
    driveResource('minecraft:overworld@0,64,-0', `minecraft:overworld@${i},${-i},${i}`, i % 3 ? `${LONG.toLowerCase()}:ex_drive` : 'ae2:drive',
      Array.from({ length: i % 3 ? 20 : 10 }, (_, s) => `megacells:bulk_item_cell_${s}=${['empty', 'not_empty', 'types_full', 'full'][(s + i) % 4]}`),
      [9.2e18 / (i + 1), 9.2e18], [i * 1000, 987_654]),
  )
}

/** Crafting CPUs: realistic, one running job that advances in real time; extreme: many, long names. */
function cpuResources(realistic: boolean) {
  const t = (Date.now() / 1000) % 600
  if (realistic) {
    const grid = 'minecraft:overworld@120,64,-340'
    return [
      cpuResource(grid, 'minecraft:overworld@118,60,-338', { name: '§bCircuits', storage: '262144', coProcessors: '16', job: 'item:gtceu:good_electronic_circuit', jobKind: 'item', jobName: 'Good Electronic Circuit', jobAmount: '64' },
        { job_total: 1_280, job_progress: Math.round(1_280 * (t / 600)), job_elapsed: 30 + t }),
      cpuResource(grid, 'minecraft:overworld@118,60,-332', { job: 'fluid:gtceu:polyethylene', jobKind: 'fluid', jobName: 'Polyethylene', jobAmount: '16000' },
        { job_total: 2_147_483_695, job_progress: 1_073_741_830, job_elapsed: 4_210 }),
      cpuResource(grid, 'minecraft:overworld@118,60,-326', {}),
    ]
  }
  return Array.from({ length: 24 }, (_, i) =>
    cpuResource('minecraft:overworld@0,64,-0', `minecraft:overworld@${i},${i},${i}`, i % 2
      ? { name: `${LONG} ${i}`, storage: String(2 ** 40), coProcessors: '65536', job: `item:${LONG.toLowerCase()}:x${i}`, jobKind: 'item', jobName: `${LONG} ${i}`, jobAmount: String(Number.MAX_SAFE_INTEGER) }
      : { name: i === 0 ? LONG : '' },
    i % 2 ? { job_total: 9.2e18, job_progress: 4.6e18, job_elapsed: 987_654 } : {}),
  )
}

/** AE2 grids. Extreme: 9 grids, huge numbers, every state; realistic: a typical base. */
export function ae2Snapshot(realistic = false, adopted: Map<string, string> = new Map()) {
  const grids: MockGrid[] = realistic
    ? [
        {
          id: 'minecraft:overworld@120,64,-340', owner: '00000000-0000-0000-0000-000000000001', ownerName: 'Developer',
          nodes: 412, controllers: 6, cpus: 3,
          metrics: { power_stored: 1.6e6, power_max: 2.4e6, power_usage: 186.5, power_injection: 210, item_types: 2843, items: 18_532_118, fluid_types: 37, fluids: 4_210_000, channels: 214, cpus_busy: 1, 'stock:item:gtceu:good_electronic_circuit': 37, 'stock:item:minecraft:iron_ingot': 212_034, 'stock:fluid:gtceu:oxygen': 512_000 },
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
  // Grids without controller: hidden (admins) until adopted or registered in game.
  const hidden: MockGrid[] = (realistic ? [0, 1] : [0, 1, 2, 3]).map((i) => ({
    id: i === 1 && !realistic ? `${LONG}:dimension@-${i}0,-64,1${i}` : `minecraft:overworld@${300 + i},62,${-80 - i}`,
    owner: '', ownerName: '', kind: 'hidden', nodes: 7 + i * 1234, controllers: 0, cpus: 0, metrics: {},
  }))
  for (const g of hidden) {
    const owner = adopted.get(`ae2/grid/${g.id}`)
    if (owner) Object.assign(g, { kind: 'registered', owner, ownerName: realistic ? 'Developer' : LONG })
  }
  return {
    provider: 'ae2',
    at: { wall: now(), uptimeMillis: 123_456_789, session: 3 },
    resources: [...[...grids, ...hidden].map(gridResource), ...cpuResources(realistic), ...driveResources(realistic)],
  }
}

// Two uptime sessions: 10 h, then 14 h offline, then 16 h up to now.
const HOUR = 3_600_000

type MockStack = { key: string; kind: 'item' | 'fluid'; name: string; amount: number; change1h: number; change24h: number }

/** A mid-game GregTech + AE2 storage; extreme: long names, huge numbers and many keys. */
function inventoryStacks(realistic: boolean): MockStack[] {
  const base: MockStack[] = [
    { key: 'item:gtceu:steel_ingot', kind: 'item', name: 'Steel Ingot', amount: 48_213, change1h: 1_204, change24h: 9_870 },
    { key: 'item:minecraft:iron_ingot', kind: 'item', name: 'Iron Ingot', amount: 212_034, change1h: -3_512, change24h: -20_410 },
    { key: 'item:gtceu:aluminium_ingot', kind: 'item', name: 'Aluminium Ingot', amount: 9_412, change1h: 88, change24h: 1_020 },
    { key: 'item:gtceu:stainless_steel_ingot', kind: 'item', name: 'Stainless Steel Ingot', amount: 2_048, change1h: -64, change24h: -512 },
    { key: 'item:gtceu:copper_single_wire', kind: 'item', name: '1x Copper Wire', amount: 3_120, change1h: 0, change24h: 640 },
    { key: 'item:gtceu:basic_electronic_circuit', kind: 'item', name: 'Basic Electronic Circuit', amount: 0, change1h: -12, change24h: -96 },
    { key: 'item:gtceu:good_electronic_circuit', kind: 'item', name: 'Good Electronic Circuit', amount: 37, change1h: 4, change24h: 16 },
    { key: 'item:gtceu:ev_energy_input_hatch', kind: 'item', name: '§5EV Energy Hatch', amount: 2, change1h: 0, change24h: 1 },
    { key: 'item:minecraft:redstone', kind: 'item', name: 'Redstone', amount: 1_843_220, change1h: 12_400, change24h: 140_220 },
    { key: 'item:minecraft:coal', kind: 'item', name: 'Coal', amount: 64_000, change1h: 0, change24h: 0 },
    { key: 'item:minecraft:cobblestone', kind: 'item', name: 'Cobblestone', amount: 2_147_483_647, change1h: 0, change24h: 0 },
    { key: 'item:ae2:certus_quartz_crystal', kind: 'item', name: 'Certus Quartz Crystal', amount: 5_512, change1h: 128, change24h: 2_048 },
    { key: 'item:minecraft:enchanted_book#3f2a91c0', kind: 'item', name: 'Enchanted Book', amount: 3, change1h: 0, change24h: 1 },
    { key: 'fluid:minecraft:water', kind: 'fluid', name: 'Water', amount: 2_400_000, change1h: -120_000, change24h: 300_000 },
    { key: 'fluid:gtceu:oxygen', kind: 'fluid', name: 'Oxygen', amount: 512_000, change1h: 16_000, change24h: 64_000 },
    { key: 'fluid:gtceu:sulfuric_acid', kind: 'fluid', name: 'Sulfuric Acid', amount: 36_000, change1h: -4_000, change24h: -12_000 },
  ]
  if (realistic) return base
  const many: MockStack[] = Array.from({ length: 640 }, (_, i) => ({
    key: `item:${LONG.toLowerCase()}:part_${i}#${(i * 2654435761 % 2 ** 32).toString(16)}`,
    kind: 'item',
    name: `${LONG} ${i}`,
    amount: i === 0 ? Number.MAX_SAFE_INTEGER : i * 997,
    change1h: i % 3 === 0 ? -i * 13 : i * 7,
    change24h: i % 2 === 0 ? -i * 1_000_003 : i * 31,
  }))
  return [...base, ...many]
}

/** A good circuit job in GregTech: some parts at machines, most still planned. */
function jobLines(realistic: boolean) {
  const lines = [
    { key: 'item:gtceu:resistor', kind: 'item', name: 'Resistor', stored: 4, active: 8, pending: 120 },
    { key: 'item:gtceu:basic_electronic_circuit', kind: 'item', name: 'Basic Electronic Circuit', stored: 0, active: 4, pending: 124 },
    { key: 'item:gtceu:fine_copper_wire', kind: 'item', name: 'Fine Copper Wire', stored: 64, active: 16, pending: 960 },
    { key: 'item:gtceu:phenolic_printed_circuit_board', kind: 'item', name: 'Good Circuit Board', stored: 0, active: 2, pending: 62 },
    { key: 'item:gtceu:copper_single_wire', kind: 'item', name: '1x Copper Wire', stored: 128, active: 0, pending: 0 },
    { key: 'fluid:gtceu:soldering_alloy', kind: 'fluid', name: 'Soldering Alloy', stored: 2_304, active: 0, pending: 0 },
    { key: 'item:gtceu:good_electronic_circuit', kind: 'item', name: 'Good Electronic Circuit', stored: 0, active: 0, pending: 64 },
  ]
  if (realistic) return lines
  return Array.from({ length: 300 }, (_, i) => ({ key: `item:${LONG.toLowerCase()}:l${i}`, kind: 'item', name: `${LONG} ${i}`, stored: 9.2e18, active: 9.2e18 - i, pending: 9.2e18 }))
}

const PROFILER_TYPES: Array<[string, string, number, number]> = [
  ['block:gtceu:ev_large_chemical_reactor', 'Large Chemical Reactor', 3_210, 2],
  ['block:ae2:pattern_provider', 'Pattern Provider', 2_480, 64],
  ['block:gtceu:hv_macerator', '§6HV§r Macerator', 1_150, 12],
  ['block:minecraft:hopper', 'Hopper', 640, 40],
  ['entity:minecraft:item', 'Item', 420, 85],
  ['block:fluxnetworks:flux_point', 'Flux Point', 180, 16],
]

function profilerSnapshot(realistic: boolean) {
  const types = realistic
    ? PROFILER_TYPES
    : Array.from({ length: 16 }, (_, i): [string, string, number, number] => [`block:${LONG.toLowerCase()}:t${i}`, `${LONG} ${i}`, 9e9 / (i + 1), 65_536])
  const resource = (key: string, attributes: Record<string, string>, metrics: Record<string, number>) => {
    const id = key.slice('profiler/'.length).split('/').slice(1).join('/')
    const type = key.split('/')[1]
    return { ref: { provider: 'profiler', type, id, key }, attributes, metrics, canEdit: false }
  }
  return {
    provider: 'profiler',
    at: { wall: now(), uptimeMillis: 123_456_789, session: 3 },
    resources: [
      resource('profiler/total/all', {}, { tick_us: types.reduce((s, x) => s + x[2], 0), objects: types.reduce((s, x) => s + x[3], 0) }),
      ...types.map(([id, name, us, objects]) => resource(`profiler/type/${id}`, { kind: id.split(':')[0], name }, { tick_us: us, objects })),
    ],
  }
}

function profilerTop(realistic: boolean) {
  if (!realistic) return Array.from({ length: 50 }, (_, i) => ({ kind: 'block', type: `${LONG}:x`, name: `${LONG} ${i}`, where: `${LONG}:dimension@-29999999,-64,${i}`, us: String(9e9 / (i + 1)) }))
  return [
    { kind: 'block', type: 'gtceu:ev_large_chemical_reactor', name: 'Large Chemical Reactor', where: 'minecraft:overworld@243,73,30', us: '1820.40' },
    { kind: 'block', type: 'gtceu:ev_large_chemical_reactor', name: 'Large Chemical Reactor', where: 'minecraft:overworld@241,73,30', us: '1389.60' },
    { kind: 'block', type: 'gtceu:hv_macerator', name: '§6HV§r Macerator', where: 'minecraft:overworld@205,68,43', us: '212.10' },
    { kind: 'block', type: 'ae2:pattern_provider', name: 'Pattern Provider', where: 'minecraft:overworld@120,64,-338', us: '96.30' },
    { kind: 'entity', type: 'minecraft:item', name: 'Item', where: 'minecraft:overworld@118,65,-12', us: '12.80' },
  ]
}

/** Job history of a CPU: three finished jobs, then the running one. */
function eventsBody(url: URL) {
  const at = (minutesAgo: number) => ({ wall: new Date(Date.now() - minutesAgo * 60_000).toISOString(), uptimeMillis: 123_456_789 - minutesAgo * 60_000, session: 3 })
  const job = (minutesAgo: number, key: string, name: string, amount: string) => [
    { at: at(minutesAgo), kind: 'attribute', attribute: 'job', value: key },
    { at: at(minutesAgo), kind: 'attribute', attribute: 'jobAmount', value: amount },
    { at: at(minutesAgo), kind: 'attribute', attribute: 'jobName', value: name },
  ]
  const idle = (minutesAgo: number) => job(minutesAgo, '', '', '')
  if (!url.searchParams.get('resource')?.startsWith('ae2/cpu/')) return []
  return [
    ...job(2, 'item:gtceu:good_electronic_circuit', 'Good Electronic Circuit', '64'),
    ...idle(9),
    ...job(31, 'item:gtceu:steel_ingot', 'Steel Ingot', '512'),
    ...idle(40),
    ...job(44, 'item:ae2:logic_processor', 'Logic Processor', '16'),
    ...idle(95),
    ...job(130, 'item:gtceu:basic_electronic_circuit', 'Basic Electronic Circuit', '128'),
    { at: at(600), kind: 'added' },
  ]
}

function inventoryBody(url: URL, realistic: boolean) {
  const q = (url.searchParams.get('q') ?? '').toLowerCase()
  const kind = url.searchParams.get('kind')
  const sort = url.searchParams.get('sort') ?? 'amount'
  const limit = Number(url.searchParams.get('limit') ?? 100)
  const offset = Number(url.searchParams.get('offset') ?? 0)
  const plain = (s: string) => s.replace(/§./g, '')
  const byName = (a: MockStack, b: MockStack) => plain(a.name).localeCompare(plain(b.name))
  const items = inventoryStacks(realistic)
    .filter((s) => !kind || s.kind === kind)
    .filter((s) => !q || s.name.toLowerCase().includes(q) || s.key.includes(q))
    .sort(
      sort === 'name'
        ? byName
        : sort === 'change'
          ? (a, b) => Math.abs(b.change24h) - Math.abs(a.change24h) || byName(a, b)
          : (a, b) => b.amount - a.amount || byName(a, b),
    )
  return {
    resource: url.searchParams.get('resource') ?? '',
    scannedAt: { wall: new Date(Date.now() - 4_000).toISOString(), uptimeMillis: 1_000_000, session: 3 },
    total: items.length,
    items: items.slice(offset, offset + limit),
  }
}
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
  const adopted = new Map<string, string>()
  // Players who logged in to the dashboard once (share targets).
  const knownPlayers = new Map([['steve', { uuid: '00000000-0000-0000-0000-0000000057e7', name: 'Steve' }], ['alex', { uuid: '00000000-0000-0000-0000-00000000a1e7', name: 'Alex' }]])
  const requestable = options.realistic
    ? [{ provider: 'ae2', handle: 'h-steve', name: 'Steve\'s storage', ownerName: 'Steve', request: 'none' }, { provider: 'ae2', handle: 'h-anon', name: null, ownerName: 'Alex', request: 'pending' }]
    : Array.from({ length: 5 }, (_, i) => ({ provider: 'ae2', handle: `h-${i}`, name: i === 0 ? LONG.slice(0, 48) : null, ownerName: i === 1 ? LONG.slice(0, 16) : `Player${i}`, request: ['none', 'pending', 'denied'][i % 3] }))
  const accessRequests = options.realistic
    ? [{ resource: 'ae2/grid/minecraft:overworld@120,64,-340', resourceName: 'Main base', player: '00000000-0000-0000-0000-0000000057e7', name: 'Steve', requestedAt: now() }]
    : [{ resource: 'ae2/grid/minecraft:overworld@0,64,-0', resourceName: null, player: 'uuid-req', name: LONG.slice(0, 16), requestedAt: now() }]
  const grid = 'ae2/grid/minecraft:overworld@120,64,-340'
  const watches: Array<{ resource: string; item: string; name: string; kind: string; target?: number; createdAt: string; change1h: number; change24h: number }> =
    options.realistic
      ? [
          { resource: grid, item: 'item:gtceu:good_electronic_circuit', name: 'Good Electronic Circuit', kind: 'item', target: 128, createdAt: now(), change1h: 4, change24h: 16 },
          { resource: grid, item: 'item:minecraft:iron_ingot', name: 'Iron Ingot', kind: 'item', target: 100_000, createdAt: now(), change1h: -3_512, change24h: -20_410 },
          { resource: grid, item: 'fluid:gtceu:oxygen', name: 'Oxygen', kind: 'fluid', createdAt: now(), change1h: 16_000, change24h: 64_000 },
        ]
      : []
  const profiler: { mode: 'off' | 'timed' | 'continuous'; until?: string; intervalSeconds: number } = { mode: 'off', intervalSeconds: 10 }
  const shares: Array<{ resource: string; player: string; name: string; grantedAt: string }> = options.realistic
    ? [{ resource: 'ae2/grid/minecraft:overworld@120,64,-340', player: '00000000-0000-0000-0000-00000000a1e7', name: 'Alex', grantedAt: now() }]
    : Array.from({ length: 6 }, (_, i) => ({ resource: 'ae2/grid/minecraft:overworld@0,64,-0', player: `uuid-share-${i}`, name: i === 0 ? LONG.slice(0, 16) : `Friend${i}`, grantedAt: now() }))
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
        ? withEdit(ae2Snapshot(options.realistic, adopted))
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
      case '/api/v1/providers/profiler/snapshot':
        return json(profilerSnapshot(options.realistic === true))
      case '/api/v1/admin/profiler': {
        if (role === 'player') return json({ error: { code: 'forbidden', message: 'no' } }, 403)
        if (method === 'PUT') {
          const b = body as { mode: 'off' | 'timed' | 'continuous'; durationSeconds?: number; intervalSeconds?: number }
          profiler.mode = b.mode
          profiler.intervalSeconds = b.intervalSeconds ?? profiler.intervalSeconds
          profiler.until = b.mode === 'timed' ? new Date(Date.now() + (b.durationSeconds ?? 60) * 1000).toISOString() : undefined
        }
        return json({ ...profiler, active: profiler.mode !== 'off' })
      }
      case '/api/v1/details':
        return url.searchParams.get('resource') === 'profiler/total/all'
          ? json({ resource: 'profiler/total/all', at: { wall: now(), uptimeMillis: 123_456_789, session: 3 }, rows: profilerTop(options.realistic === true) })
          : json({ error: { code: 'not_found', message: 'no' } }, 404)
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
      case '/api/v1/shares': {
        if (!editable && !(method === 'DELETE')) return json({ error: { code: 'forbidden', message: 'no' } }, 403)
        const resource = method === 'POST' ? (body as { resource: string }).resource : url.searchParams.get('resource')
        if (method === 'POST') {
          const player = knownPlayers.get(String((body as { name: string }).name).toLowerCase())
          if (!player) return json({ error: { code: 'bad_request', message: 'unknown player' } }, 400)
          const share = { resource: resource!, player: player.uuid, name: player.name, grantedAt: now() }
          if (!shares.some((s) => s.resource === resource && s.player === player.uuid)) shares.push(share)
          return json({ player: share.player, name: share.name, grantedBy: null, grantedAt: share.grantedAt }, 201)
        }
        if (method === 'DELETE') {
          const i = shares.findIndex((s) => s.resource === resource && s.player === url.searchParams.get('player'))
          if (i < 0) return json({ error: { code: 'not_found', message: 'no' } }, 404)
          shares.splice(i, 1)
          return { status: 204 }
        }
        return json(shares.filter((s) => s.resource === resource).map((s) => ({ player: s.player, name: s.name, grantedBy: null, grantedAt: s.grantedAt })))
      }
      case '/api/v1/access/requestable':
        return json(requestable)
      case '/api/v1/access/requests': {
        if (method === 'POST') {
          const r = requestable.find((x) => x.handle === (body as { handle: string }).handle)
          if (!r) return json({ error: { code: 'not_found', message: 'no' } }, 404)
          if (r.request === 'denied') return json({ error: { code: 'conflict', message: 'declined' } }, 409)
          r.request = 'pending'
          return json({ request: 'pending' }, 201)
        }
        return json(editable ? accessRequests : [])
      }
      case '/api/v1/access/decisions': {
        if (!editable) return json({ error: { code: 'forbidden', message: 'no' } }, 403)
        const d = body as { resource: string; player: string; approve: boolean }
        const i = accessRequests.findIndex((r) => r.resource === d.resource && r.player === d.player)
        if (i < 0) return json({ error: { code: 'not_found', message: 'no' } }, 404)
        const [request] = accessRequests.splice(i, 1)
        if (d.approve) shares.push({ resource: d.resource, player: d.player, name: request.name, grantedAt: now() })
        return { status: 204 }
      }
      case '/api/v1/registrations': {
        if (!editable) return json({ error: { code: 'forbidden', message: 'no' } }, 403)
        if (method === 'POST') {
          const b = body as { resource: string; name?: string }
          adopted.set(b.resource, '00000000-0000-0000-0000-000000000001')
          if (b.name) overrides.push({ resource: b.resource, key: 'name', value: b.name })
          return { status: 204 }
        }
        adopted.delete(url.searchParams.get('resource') ?? '')
        return { status: 204 }
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
      case '/api/v1/jobs': {
        const resource = url.searchParams.get('resource') ?? ''
        const cpu = snapshot('ae2').resources.find((r) => (r as { ref: { key: string } }).ref.key === resource) as
          | { attributes: Record<string, string> }
          | undefined
        if (!cpu?.attributes.job) return json({ error: { code: 'not_found', message: 'idle' } }, 404)
        return json({ resource, at: { wall: now(), uptimeMillis: 123_456_789, session: 3 }, lines: jobLines(options.realistic === true) })
      }
      case '/api/v1/watches': {
        const resource = method === 'POST' ? (body as { resource: string }).resource : url.searchParams.get('resource') ?? ''
        if (method === 'POST') {
          if (!editable) return json({ error: { code: 'forbidden', message: 'no' } }, 403)
          const b = body as { item: string; target?: number }
          const stack = inventoryStacks(true).find((s) => s.key === b.item)
          const existing = watches.find((w) => w.resource === resource && w.item === b.item)
          if (existing) existing.target = b.target
          else if (stack) watches.push({ resource, item: b.item, name: stack.name, kind: stack.kind, target: b.target, createdAt: now(), change1h: stack.change1h, change24h: stack.change24h })
          else return json({ error: { code: 'bad_request', message: 'unknown item' } }, 400)
          return { status: 204 }
        }
        if (method === 'DELETE') {
          if (!editable) return json({ error: { code: 'forbidden', message: 'no' } }, 403)
          const i = watches.findIndex((w) => w.resource === resource && w.item === url.searchParams.get('item'))
          if (i < 0) return json({ error: { code: 'not_found', message: 'no' } }, 404)
          watches.splice(i, 1)
          return { status: 204 }
        }
        return json(watches.filter((w) => w.resource === resource).map(({ resource: _r, ...w }) => w))
      }
      case '/api/v1/events':
        return json(eventsBody(url))
      case '/api/v1/inventory':
        return json(inventoryBody(url, options.realistic === true))
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
