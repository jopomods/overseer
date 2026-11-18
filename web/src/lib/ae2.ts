import type { Override, Resource, Snapshot } from '@/api/hooks'
import { siParts } from './format'
import type { Locale } from './preferences'
import { metric, ofType } from './snapshot'

/**
 * Helpers for the Applied Energistics 2 provider (docs/modules/ae2.md).
 */
export type GridState = 'online' | 'conflict' | 'unpowered' | 'booting'

/** controller: found by its controller; registered: by a registered block; hidden: neither (admins). */
export type GridKind = 'controller' | 'registered' | 'hidden'

export interface Ae2Grid {
  resource: Resource
  id: string
  /** Name set in Overseer, else "Network at x, y, z". */
  name: string | null
  dimension: string
  pos: string
  owner: string
  ownerName: string
  state: GridState
  kind: GridKind
  canEdit: boolean
  /** AE (AE2's own unit). */
  powerStored: number
  powerMax: number
  /** AE/t */
  usage: number
  injection: number
  idle: number
  itemTypes: number
  items: number
  fluidTypes: number
  /** mB */
  fluids: number
  channels: number
  cpus: number
  cpusBusy: number
  cpuStorage: number
  coProcessors: number
  nodes: number
  controllers: number
}

const num = (value: string | undefined) => (value === undefined || value === '' ? 0 : Number(value))

export function grids(snapshot: Snapshot | undefined, overrides: Override[] = []): Ae2Grid[] {
  const names = new Map(overrides.filter((o) => o.key === 'name' && o.value).map((o) => [o.resource, o.value as string]))
  return ofType(snapshot, 'grid')
    .map((r): Ae2Grid => {
      const a = r.attributes
      const state: GridState =
        a.booting === 'true' ? 'booting' : a.state === 'controller_conflict' ? 'conflict' : a.powered === 'false' ? 'unpowered' : 'online'
      return {
        resource: r,
        id: r.ref.id,
        name: names.get(r.ref.key) ?? null,
        dimension: a.dimension ?? '',
        pos: a.pos ?? '',
        owner: a.owner ?? '',
        ownerName: a.ownerName ?? '',
        state,
        kind: a.kind === 'hidden' || a.kind === 'registered' ? a.kind : 'controller',
        canEdit: r.canEdit ?? false,
        powerStored: metric(r, 'power_stored') ?? 0,
        powerMax: metric(r, 'power_max') ?? 0,
        usage: metric(r, 'power_usage') ?? 0,
        injection: metric(r, 'power_injection') ?? 0,
        idle: num(a.idlePower),
        itemTypes: metric(r, 'item_types') ?? 0,
        items: metric(r, 'items') ?? 0,
        fluidTypes: metric(r, 'fluid_types') ?? 0,
        fluids: metric(r, 'fluids') ?? 0,
        channels: metric(r, 'channels') ?? 0,
        cpus: num(a.cpus),
        cpusBusy: metric(r, 'cpus_busy') ?? 0,
        cpuStorage: num(a.cpuStorage),
        coProcessors: num(a.coProcessors),
        nodes: num(a.nodes),
        controllers: num(a.controllers),
      }
    })
    .sort((a, b) => (a.name ?? '￿' + a.id).localeCompare(b.name ?? '￿' + b.id, undefined, { numeric: true }))
}

/** "minecraft:the_nether" -> "the_nether"; other namespaces stay. */
export function shortDimension(dimension: string): string {
  return dimension.startsWith('minecraft:') ? dimension.slice('minecraft:'.length) : dimension
}

/** "1,64,-3" -> "1, 64, -3" */
export function formatPos(pos: string): string {
  return pos.split(',').join(', ')
}

export function formatAe(value: number, locale: Locale): string {
  const [number, prefix] = siParts(value, locale)
  return `${number} ${prefix}AE`
}

export function formatAeRate(value: number, locale: Locale): string {
  return `${formatAe(value, locale)}/t`
}

/** Fluids as a number of buckets (1000 mB) with SI prefix; the caller adds the word. */
export function formatBuckets(mB: number, locale: Locale): string {
  const [number, prefix] = siParts(mB / 1000, locale)
  return prefix ? `${number} ${prefix}` : number
}
