import type { Override, Resource, Snapshot } from '@/api/hooks'
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

export interface FlowNode {
  name: string
  kind: 'source' | 'network' | 'sink'
}

export interface FlowGraph {
  nodes: FlowNode[]
  links: Array<{ source: number; target: number; value: number }>
  /** Total flow through the network (FE/t). */
  total: number
}

/**
 * Energy flow of one network for the Sankey diagram: generation per zone (plugs) and
 * discharging storages flow into the network, which feeds consumption per zone (points)
 * and charging storages.
 *
 * Flux reports transfer positive for energy entering the network (plugs) and negative for
 * energy leaving it (points); storages follow the same sign: positive = discharging.
 */
export function flowGraph(list: FluxDevice[], labels: { network: string; noZone: string; storageIn: string; storageOut: string }): FlowGraph {
  const sources = new Map<string, number>()
  const sinks = new Map<string, number>()
  const add = (map: Map<string, number>, key: string, value: number) => map.set(key, (map.get(key) ?? 0) + value)
  for (const d of list) {
    const flow = d.transfer
    if (!flow) continue
    if (d.type === 'plug') add(sources, d.zone ?? labels.noZone, Math.abs(flow))
    else if (d.type === 'point') add(sinks, d.zone ?? labels.noZone, Math.abs(flow))
    else if (d.type === 'storage') {
      if (flow > 0) add(sources, labels.storageOut, flow)
      else add(sinks, labels.storageIn, -flow)
    }
  }
  const nodes: FlowNode[] = []
  const links: FlowGraph['links'] = []
  const sorted = (m: Map<string, number>) => [...m.entries()].filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1])
  const srcEntries = sorted(sources)
  const sinkEntries = sorted(sinks)
  for (const [name] of srcEntries) nodes.push({ name, kind: 'source' })
  const network = nodes.length
  nodes.push({ name: labels.network, kind: 'network' })
  srcEntries.forEach(([, value], i) => links.push({ source: i, target: network, value }))
  for (const [name, value] of sinkEntries) {
    links.push({ source: network, target: nodes.length, value })
    nodes.push({ name, kind: 'sink' })
  }
  const total = Math.max(
    srcEntries.reduce((s, [, v]) => s + v, 0),
    sinkEntries.reduce((s, [, v]) => s + v, 0),
  )
  return { nodes, links, total }
}
