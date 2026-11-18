import type { Override, Resource, Snapshot } from '@/api/hooks'
import type { FlowDetail } from './preferences'
import { metric, ofType } from './snapshot'
import { tierInName, withTier, type Tier } from './tiers'

/**
 * Helpers for the Flux Networks provider (docs/modules/flux.md).
 */
export type DeviceType = 'plug' | 'point' | 'storage' | 'controller'

export interface FluxDevice {
  resource: Resource
  id: string
  type: DeviceType
  /** Zone: set in Overseer, else the name prefix before the first ':' (e.g. "Power plant"), else null. */
  zone: string | null
  /** The zone was set in Overseer rather than taken from the name. */
  zoneOverridden: boolean
  canEdit: boolean
  /** Name without the zone prefix; falls back to type and position. */
  label: string
  /** The name as stored in Flux (with zone prefix and colour codes); may be empty. */
  name: string
  /** Voltage tier at the end of the name, when the server uses tiers (lib/tiers.ts). */
  tier?: Tier
  /** The label without the tier, shown next to a coloured tier badge. */
  title: string
  dimension: string
  pos: string
  loaded: boolean
  /** FE/t as reported by Flux. */
  transfer: number
  /** FE; stored energy for storages. */
  buffer: number
  priority: number
  limit: number
  surge: boolean
  /** Ignores its transfer limit ("bypass limit"). */
  disableLimit: boolean
  /** Keeps its chunk loaded. */
  chunkLoading: boolean
  /** FE a Flux storage holds at most; undefined for other devices or when never seen loaded. */
  capacity?: number
}

export interface FluxNetwork {
  resource: Resource
  id: string
  name: string
  color: string
  security: string
  owner: string
  input: number
  output: number
  buffer: number
  energy: number
}

export interface FluxMember {
  uuid: string
  name: string
  access: string
}

/** "Power plant: Diesel 1" -> { zone: "Power plant", rest: "Diesel 1" } */
export function splitZone(name: string): { zone: string | null; rest: string } {
  const i = name.indexOf(':')
  if (i <= 0) return { zone: null, rest: name.trim() }
  const zone = name.slice(0, i).trim()
  const rest = name.slice(i + 1).trim()
  return zone ? { zone, rest } : { zone: null, rest: name.trim() }
}

export function networks(snapshot: Snapshot | undefined): FluxNetwork[] {
  return ofType(snapshot, 'network')
    .map((r) => ({
      resource: r,
      id: r.ref.id,
      name: r.attributes.name || `#${r.ref.id}`,
      color: /^#[0-9a-f]{6}$/i.test(r.attributes.color ?? '') ? r.attributes.color : '#a8a29e',
      security: r.attributes.security ?? 'private',
      owner: r.attributes.owner ?? '',
      input: metric(r, 'energy_input') ?? 0,
      output: metric(r, 'energy_output') ?? 0,
      buffer: metric(r, 'buffer') ?? 0,
      energy: metric(r, 'energy') ?? 0,
    }))
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
}

export function devices(
  snapshot: Snapshot | undefined,
  networkId: string,
  overrides: Override[] = [],
  tiers: Tier[] = [],
): FluxDevice[] {
  const zoneOverrides = new Map(overrides.filter((o) => o.key === 'zone' && o.value).map((o) => [o.resource, o.value as string]))
  return ofType(snapshot, 'device')
    .filter((r) => r.attributes.network === networkId)
    .map((r) => {
      const type = (r.attributes.type ?? 'plug') as DeviceType
      const { zone: nameZone, rest } = splitZone(r.attributes.name ?? '')
      const override = zoneOverrides.get(r.ref.key)
      const zone = override ?? nameZone
      const pos = r.attributes.pos ?? ''
      const label = rest || `${type} @ ${pos}`
      const tier = tierInName(rest, tiers)
      return {
        resource: r,
        id: r.ref.id,
        type,
        zone,
        zoneOverridden: override !== undefined,
        canEdit: r.canEdit === true,
        label,
        name: r.attributes.name ?? '',
        tier,
        title: tier ? withTier(rest, null, tiers) || tier.name : label,
        dimension: r.attributes.dimension ?? '',
        pos,
        loaded: r.attributes.loaded !== 'false',
        transfer: metric(r, 'transfer') ?? 0,
        buffer: metric(r, 'buffer') ?? 0,
        priority: Number(r.attributes.priority ?? 0),
        limit: Number(r.attributes.limit ?? 0),
        surge: r.attributes.surge === 'true',
        disableLimit: r.attributes.disableLimit === 'true',
        chunkLoading: r.attributes.forcedLoading === 'true',
        capacity: r.attributes.capacity ? Number(r.attributes.capacity) : undefined,
      }
    })
    .sort((a, b) => (a.zone ?? '￿').localeCompare(b.zone ?? '￿') || a.label.localeCompare(b.label, undefined, { numeric: true }))
}

export function members(snapshot: Snapshot | undefined, networkId: string): FluxMember[] {
  const order = ['owner', 'super_admin', 'admin', 'user', 'blocked']
  return ofType(snapshot, 'member')
    .filter((r) => r.attributes.network === networkId)
    .map((r) => ({ uuid: r.attributes.player ?? '', name: r.attributes.name || '?', access: r.attributes.access ?? 'user' }))
    .sort((a, b) => order.indexOf(a.access) - order.indexOf(b.access) || a.name.localeCompare(b.name))
}

/** World action types of the Flux provider (docs/modules/flux.md, ADR-0016). */
export const FLUX_ACTIONS = {
  networkEdit: 'flux.network.edit',
  deviceEdit: 'flux.device.edit',
  deviceConnect: 'flux.device.connect',
  memberEdit: 'flux.member.edit',
} as const

/** Flux's limits (docs/modules/flux.md): names count colour codes too. */
export const MAX_NAME = 24
export const MAX_PASSWORD = 16
export const MIN_PRIORITY = -9999
export const MAX_PRIORITY = 9999

/** The colours Flux offers in game (EnumNetworkColor); any other hex colour works too. */
export const FLUX_COLORS = [
  '#295e8a', '#343477', '#582a72', '#882d60', '#aa3939', '#aa6f39', '#c6b900',
  '#609732', '#87cefa', '#86608e', '#f08080', '#ffc0cb', '#ffdab9', '#eedc82',
] as const

/** `#rgb`, `rgb`, `#rrggbb` or `rrggbb` as `#rrggbb`; undefined if it is no colour. */
export function parseHexColor(input: string): string | undefined {
  const v = input.trim().replace(/^#/, '').toLowerCase()
  if (/^[0-9a-f]{6}$/.test(v)) return `#${v}`
  if (/^[0-9a-f]{3}$/.test(v)) return `#${[...v].map((c) => c + c).join('')}`
  return undefined
}

/**
 * Names for several devices from one pattern: `{n}` is the position in the selection (1, 2, ...),
 * `{name}` the device's current name. Without a placeholder every device gets the same name.
 */
export function namesFromPattern(pattern: string, current: string[]): string[] {
  return current.map((name, i) => pattern.replaceAll('{n}', String(i + 1)).replaceAll('{name}', name))
}

export interface PriorityGroup {
  /** `surge` before every number: Flux serves surge devices first. */
  priority: number | 'surge'
  devices: FluxDevice[]
  /** Sum of |transfer| (FE/t). */
  transfer: number
}

/**
 * The order in which Flux serves devices: points (and storages charging) receive energy from the
 * highest priority down; plugs (and storages discharging) are drained the same way. Devices with
 * the same priority share. Power surge goes before everything.
 */
export function priorityOrder(list: FluxDevice[], side: 'receive' | 'supply'): PriorityGroup[] {
  const kinds: DeviceType[] = side === 'receive' ? ['point', 'storage'] : ['plug', 'storage']
  const groups = new Map<number | 'surge', FluxDevice[]>()
  for (const d of list) {
    if (!kinds.includes(d.type)) continue
    const key = d.surge ? 'surge' : d.priority
    groups.set(key, [...(groups.get(key) ?? []), d])
  }
  return [...groups.entries()]
    .sort(([a], [b]) => (a === 'surge' ? -1 : b === 'surge' ? 1 : b - a))
    .map(([priority, devs]) => ({
      priority,
      devices: [...devs].sort((x, y) => Math.abs(y.transfer) - Math.abs(x.transfer)),
      transfer: devs.reduce((sum, d) => sum + Math.abs(d.transfer), 0),
    }))
}

export interface TierSummary {
  tier: Tier | null
  devices: FluxDevice[]
  generation: number
  consumption: number
}

/** Generation (plugs) and consumption (points) per voltage tier, in tier order; no tier last. */
export function tierSummaries(list: FluxDevice[], tiers: Tier[]): TierSummary[] {
  const result: TierSummary[] = []
  for (const tier of [...tiers, null]) {
    const devs = list.filter((d) => (d.tier ?? null) === tier && d.type !== 'controller')
    if (devs.length === 0) continue
    result.push({
      tier,
      devices: devs,
      generation: devs.filter((d) => d.type === 'plug').reduce((s, d) => s + Math.abs(d.transfer), 0),
      consumption: devs.filter((d) => d.type === 'point').reduce((s, d) => s + Math.abs(d.transfer), 0),
    })
  }
  return result
}

/**
 * Hints for the recommended (never enforced) naming convention "Zone: Title Tier" (docs/modules/flux.md).
 */
export function nameHints(name: string, others: string[]): Array<'noZone' | 'duplicate'> {
  const hints: Array<'noZone' | 'duplicate'> = []
  const plain = name.replace(/§[0-9a-fk-or]/gi, '').trim()
  if (plain === '') return hints
  if (splitZone(plain).zone === null) hints.push('noZone')
  if (others.some((o) => o.replace(/§[0-9a-fk-or]/gi, '').trim().toLowerCase() === plain.toLowerCase())) hints.push('duplicate')
  return hints
}

export interface ZoneSummary {
  zone: string | null
  devices: FluxDevice[]
  /** Sum of |transfer| of plugs: energy taken into the network. */
  generation: number
  /** Sum of |transfer| of points: energy delivered. */
  consumption: number
}

export function zones(list: FluxDevice[]): ZoneSummary[] {
  const byZone = new Map<string | null, FluxDevice[]>()
  for (const d of list) byZone.set(d.zone, [...(byZone.get(d.zone) ?? []), d])
  return [...byZone.entries()].map(([zone, devs]) => ({
    zone,
    devices: devs,
    generation: devs.filter((d) => d.type === 'plug').reduce((s, d) => s + Math.abs(d.transfer), 0),
    consumption: devs.filter((d) => d.type === 'point').reduce((s, d) => s + Math.abs(d.transfer), 0),
  }))
}

export type FlowNodeKind = 'device-source' | 'source' | 'network' | 'sink' | 'device-sink'

export interface FlowNode {
  name: string
  kind: FlowNodeKind
  /** Storage node: charging (energy goes in) or discharging (energy comes out). */
  storage?: 'charging' | 'discharging'
}

export interface FlowGraph {
  nodes: FlowNode[]
  links: Array<{ source: number; target: number; value: number }>
  /** Total flow through the network (FE/t). */
  total: number
}


const COLUMN: Record<FlowNodeKind, number> = { 'device-source': 0, source: 1, network: 2, sink: 3, 'device-sink': 4 }

export interface FlowLabels {
  network: string
  noZone: string
  storageIn: string
  storageOut: string
  /** Label of the node that groups the smaller devices of a zone, e.g. "+12 more". */
  more: (count: number) => string
}

/** Device detail shows at most this many device groups per zone; the rest is grouped. */
export const MAX_DEVICES_PER_ZONE = 8

/**
 * Energy flow of one network for the Sankey diagram.
 *
 * - `zones`: zone → network → zone (plugs on the left, points on the right).
 * - `devices`: device → zone → network → zone → device. Devices of the same type with the
 *   same zone and name are grouped ("Power plant B ×2").
 *
 * Storages connect to the network directly: discharging on the left, charging on the right.
 * Flux reports transfer positive for energy entering the network (plugs) and negative for
 * energy leaving it (points); storages follow the same sign: positive = discharging.
 */
export function flowGraph(list: FluxDevice[], labels: FlowLabels, detail: FlowDetail = 'zones'): FlowGraph {
  const nodes: FlowNode[] = []
  const links: FlowGraph['links'] = []
  const index = new Map<string, number>()
  const node = (key: string, name: string, kind: FlowNodeKind, storage?: FlowNode['storage']) => {
    let i = index.get(key)
    if (i === undefined) {
      i = nodes.length
      nodes.push(storage ? { name, kind, storage } : { name, kind })
      index.set(key, i)
    }
    return i
  }
  const linkValues = new Map<string, number>()
  const link = (source: number, target: number, value: number) => {
    const key = `${source}>${target}`
    linkValues.set(key, (linkValues.get(key) ?? 0) + value)
  }

  // Sort by size so the biggest flows sit on top.
  const flowing = list.filter((d) => d.transfer !== 0).sort((a, b) => Math.abs(b.transfer) - Math.abs(a.transfer))
  const network = () => node('network', labels.network, 'network')
  let inflow = 0
  let outflow = 0
  // Device groups per side and zone: same zone and name count as one device ("×2").
  const groups = new Map<string, Map<string, { label: string; count: number; value: number }>>()
  const collect = (side: 'dsrc' | 'dsnk', zone: string, label: string, value: number) => {
    const key = `${side}:${zone}`
    const zoneGroups = groups.get(key) ?? new Map()
    const group = zoneGroups.get(label) ?? { label, count: 0, value: 0 }
    group.count++
    group.value += value
    zoneGroups.set(label, group)
    groups.set(key, zoneGroups)
  }

  for (const d of flowing) {
    const value = Math.abs(d.transfer)
    const zone = d.zone ?? labels.noZone
    if (d.type === 'storage') {
      if (d.transfer > 0) link(node('storage-out', labels.storageOut, 'source', 'discharging'), network(), value)
      else link(network(), node('storage-in', labels.storageIn, 'sink', 'charging'), value)
    } else if (d.type === 'plug') {
      const zoneNode = node(`src:${zone}`, zone, 'source')
      if (detail === 'devices') collect('dsrc', zone, d.label, value)
      link(zoneNode, network(), value)
    } else if (d.type === 'point') {
      node(`snk:${zone}`, zone, 'sink')
      link(network(), index.get(`snk:${zone}`)!, value)
      if (detail === 'devices') collect('dsnk', zone, d.label, value)
    } else {
      continue
    }
    if (d.transfer > 0) inflow += value
    else outflow += value
  }

  // Device nodes: the largest groups per zone, the rest as one "+N more" node.
  for (const [key, zoneGroups] of groups) {
    const side = key.startsWith('dsrc') ? 'dsrc' : 'dsnk'
    const zone = key.slice(5)
    const zoneNode = index.get(`${side === 'dsrc' ? 'src' : 'snk'}:${zone}`)!
    const sorted = [...zoneGroups.values()].sort((a, b) => b.value - a.value)
    const shown = sorted.length > MAX_DEVICES_PER_ZONE ? sorted.slice(0, MAX_DEVICES_PER_ZONE - 1) : sorted
    const rest = sorted.slice(shown.length)
    const entries = shown.map((g) => ({ key: g.label, name: g.count > 1 ? `${g.label} ×${g.count}` : g.label, value: g.value }))
    if (rest.length) {
      const count = rest.reduce((s, g) => s + g.count, 0)
      entries.push({ key: ' more', name: labels.more(count), value: rest.reduce((s, g) => s + g.value, 0) })
    }
    for (const e of entries) {
      const deviceNode = node(`${side}:${zone}:${e.key}`, e.name, side === 'dsrc' ? 'device-source' : 'device-sink')
      if (side === 'dsrc') link(deviceNode, zoneNode, e.value)
      else link(zoneNode, deviceNode, e.value)
    }
  }

  // Stable order (ADR-0009): by name within a column, storage and "+N more" last. Ordering by
  // size made nodes of similar flow swap places on every update.
  const rank = (n: FlowNode, key: string) => (n.storage ? 2 : key.endsWith(': more') ? 1 : 0)
  const keys = [...index.entries()].sort((a, b) => a[1] - b[1]).map(([k]) => k)
  const order = nodes
    .map((n, i) => ({ n, i, key: keys[i] }))
    .sort(
      (a, b) =>
        COLUMN[a.n.kind] - COLUMN[b.n.kind] ||
        rank(a.n, a.key) - rank(b.n, b.key) ||
        a.n.name.localeCompare(b.n.name, undefined, { numeric: true, sensitivity: 'base' }),
    )
  const remap = new Map(order.map((o, i) => [o.i, i]))
  for (const [key, value] of linkValues) {
    const [source, target] = key.split('>').map(Number)
    links.push({ source: remap.get(source)!, target: remap.get(target)!, value })
  }
  return { nodes: order.map((o) => o.n), links, total: Math.max(inflow, outflow) }
}
