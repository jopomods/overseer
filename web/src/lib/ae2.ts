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

export interface Ae2Job {
  key: string
  kind: string
  name: string
  amount: number
  /** Items the whole job crafts (all steps) and how many of them are done. */
  total: number
  progress: number
  /** Seconds since the job started (AE2 counts across restarts). */
  elapsed: number
}

export interface Ae2Cpu {
  resource: Resource
  id: string
  /** Custom name from an anvil, else null. */
  name: string | null
  /** AE2's own number ("CPU #3"); 0 when the server is older than 0.4.2. */
  number: number
  /** Bytes. */
  storage: number
  coProcessors: number
  mode: string
  job: Ae2Job | null
}

/** Crafting CPUs of one grid: busy first, then by name and position. */
export function cpus(snapshot: Snapshot | undefined, gridId: string): Ae2Cpu[] {
  return ofType(snapshot, 'cpu')
    .filter((r) => r.attributes.grid === gridId)
    .map((r): Ae2Cpu => {
      const a = r.attributes
      return {
        resource: r,
        id: r.ref.id,
        name: a.name || null,
        number: num(a.number),
        storage: num(a.storage),
        coProcessors: num(a.coProcessors),
        mode: a.mode ?? 'any',
        job: a.job
          ? {
              key: a.job,
              kind: a.jobKind ?? 'item',
              name: a.jobName || a.job,
              amount: num(a.jobAmount),
              total: metric(r, 'job_total') ?? 0,
              progress: metric(r, 'job_progress') ?? 0,
              elapsed: metric(r, 'job_elapsed') ?? 0,
            }
          : null,
      }
    })
    .sort(
      (a, b) =>
        Number(b.job !== null) - Number(a.job !== null) ||
        (a.name ?? '￿' + a.id).localeCompare(b.name ?? '￿' + b.id, undefined, { numeric: true }),
    )
}

/** Position part of an id: "minecraft:overworld@1,2,3" -> "1, 2, 3". */
export function idPos(id: string): string {
  return formatPos(id.slice(id.lastIndexOf('@') + 1))
}

/** Seconds as "1:02:03" or "2:03". */
export function formatClock(seconds: number): string {
  const s = Math.max(0, Math.round(seconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s % 60)}` : `${m}:${pad(s % 60)}`
}

/** Remaining seconds at the average pace so far; undefined when there is no pace yet. */
export function jobEta(job: Ae2Job): number | undefined {
  if (job.progress <= 0 || job.elapsed <= 0 || job.total <= job.progress) return undefined
  return ((job.total - job.progress) * job.elapsed) / job.progress
}

export interface PastJob {
  name: string
  amount: number
  /** Wall time ISO strings; ended undefined while running. */
  started: string
  ended?: string
  startedUptime: number
  endedUptime?: number
}

/** Rebuilds jobs from the CPU's attribute events (newest first, as the API returns them). */
export function pastJobs(events: { at: { wall: string; uptimeMillis: number }; kind: string; attribute?: string; value?: string }[]): PastJob[] {
  const byTime = new Map<number, { wall: string; values: Record<string, string> }>()
  for (const e of [...events].reverse()) {
    if (e.kind !== 'attribute' || !e.attribute) continue
    const at = byTime.get(e.at.uptimeMillis) ?? { wall: e.at.wall, values: {} }
    at.values[e.attribute] = e.value ?? ''
    byTime.set(e.at.uptimeMillis, at)
  }
  const jobs: PastJob[] = []
  let name = ''
  let amount = 0
  for (const [uptime, change] of [...byTime.entries()].sort((a, b) => a[0] - b[0])) {
    if (change.values.jobName !== undefined) name = change.values.jobName
    if (change.values.jobAmount !== undefined) amount = num(change.values.jobAmount)
    if (change.values.job === undefined) continue
    const running = jobs.at(-1)
    if (running && running.endedUptime === undefined) {
      running.ended = change.wall
      running.endedUptime = uptime
    }
    if (change.values.job) jobs.push({ name: name || change.values.job, amount, started: change.wall, startedUptime: uptime })
  }
  return jobs.reverse()
}

export type CellState = 'empty' | 'not_empty' | 'types_full' | 'full'

export interface Ae2Cell {
  slot: number
  item: string
  state: CellState
}

export interface Ae2Drive {
  resource: Resource
  id: string
  /** Registry id, e.g. ae2:drive, ae2:chest or an add-on drive. */
  block: string
  cells: Ae2Cell[]
  /** Basic AE2 cells only; add-on cells count by state alone. */
  usedBytes: number
  totalBytes: number
  usedTypes: number
  totalTypes: number
}

const CELL_STATES: CellState[] = ['empty', 'not_empty', 'types_full', 'full']

/** Drives and ME chests of one grid. */
export function drives(snapshot: Snapshot | undefined, gridId: string): Ae2Drive[] {
  return ofType(snapshot, 'drive')
    .filter((r) => r.attributes.grid === gridId)
    .map((r): Ae2Drive => ({
      resource: r,
      id: r.ref.id,
      block: r.attributes.block ?? '',
      cells: (r.attributes.cells ?? '')
        .split(';')
        .filter(Boolean)
        .map((c) => {
          const [slot, item, state] = c.split('=')
          return { slot: Number(slot), item: item ?? '', state: CELL_STATES.includes(state as CellState) ? (state as CellState) : 'not_empty' }
        }),
      usedBytes: metric(r, 'bytes_used') ?? 0,
      totalBytes: metric(r, 'bytes_total') ?? 0,
      usedTypes: metric(r, 'types_used') ?? 0,
      totalTypes: metric(r, 'types_total') ?? 0,
    }))
}

export interface Forecast {
  /** Net change per hour: the last day's average, else the last hour's. */
  perHour: number
  /** Hours until nothing is left at this pace; only while shrinking. */
  emptyIn?: number
  /** Hours until the target is reached at this pace; only while below target and growing. */
  targetIn?: number
  /** How much is missing to the target; 0 when reached or no target. */
  deficit: number
}

/** A simple linear forecast from the net changes (inventory scans, minute resolution). */
export function forecast(amount: number, change1h: number, change24h: number, target?: number | null): Forecast {
  const perHour = change24h !== 0 ? change24h / 24 : change1h
  const deficit = target != null ? Math.max(0, target - amount) : 0
  return {
    perHour,
    emptyIn: perHour < 0 && amount > 0 ? amount / -perHour : undefined,
    targetIn: deficit > 0 && perHour > 0 ? deficit / perHour : undefined,
    deficit,
  }
}

/** Hours until an item runs out at its recent pace, if it shrinks and within two weeks. */
export function emptyInHours(amount: number, change1h: number, change24h: number): number | undefined {
  const hours = forecast(amount, change1h, change24h).emptyIn
  return hours !== undefined && hours < 24 * 14 ? hours : undefined
}

/**
 * Infinity cells (add-ons) report the largest 32-bit number: 2,147,483,647 items, or as many
 * buckets for fluids. Anything at or above counts as infinite.
 */
export const INFINITE = 2_147_483_647

export function isInfinite(amount: number, kind: string): boolean {
  return amount >= (kind === 'fluid' ? INFINITE * 1000 : INFINITE)
}

/** A job that includes an infinite source: AE2's total and progress are meaningless then. */
export function jobProgressKnown(job: Ae2Job): boolean {
  return job.total > 0 && job.total < INFINITE
}
