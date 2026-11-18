import type { Override, Resource, Snapshot } from '@/api/hooks'
import type { FlowDetail } from './preferences'
import { metric, ofType } from './snapshot'

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

export function devices(snapshot: Snapshot | undefined, networkId: string, overrides: Override[] = []): FluxDevice[] {
  const zoneOverrides = new Map(overrides.filter((o) => o.key === 'zone' && o.value).map((o) => [o.resource, o.value as string]))
  return ofType(snapshot, 'device')
    .filter((r) => r.attributes.network === networkId)
    .map((r) => {
      const type = (r.attributes.type ?? 'plug') as DeviceType
      const { zone: nameZone, rest } = splitZone(r.attributes.name ?? '')
      const override = zoneOverrides.get(r.ref.key)
      const zone = override ?? nameZone
      const pos = r.attributes.pos ?? ''
      return {
        resource: r,
        id: r.ref.id,
        type,
        zone,
        zoneOverridden: override !== undefined,
        canEdit: r.canEdit === true,
        label: rest || `${type} @ ${pos}`,
        dimension: r.attributes.dimension ?? '',
        pos,
        loaded: r.attributes.loaded !== 'false',
        transfer: metric(r, 'transfer') ?? 0,
        buffer: metric(r, 'buffer') ?? 0,
        priority: Number(r.attributes.priority ?? 0),
        limit: Number(r.attributes.limit ?? 0),
        surge: r.attributes.surge === 'true',
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
