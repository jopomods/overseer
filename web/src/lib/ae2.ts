import type { Override, Resource, Snapshot } from '@/api/hooks'
import { siParts } from './format'
import type { Locale } from './preferences'
import { metric, ofType } from './snapshot'

/**
 * Helpers for the Applied Energistics 2 provider (docs/modules/ae2.md).
 */
export type GridState = 'online' | 'conflict' | 'unpowered' | 'booting'

/** World actions on AE2 grids (ADR-0017); all target the grid, the part is a parameter. */
export const AE2_ACTIONS = {
  cpuCancel: 'ae2.cpu.cancel',
  cpuSuspend: 'ae2.cpu.suspend',
  cpuEdit: 'ae2.cpu.edit',
  driveEdit: 'ae2.drive.edit',
  providerEdit: 'ae2.provider.edit',
  craftPlan: 'ae2.craft.plan',
  craftStart: 'ae2.craft.start',
} as const

/** AE2's lock crafting modes (pattern providers). */
export const LOCK_MODES = ['none', 'lock_until_pulse', 'lock_while_high', 'lock_while_low', 'lock_until_result'] as const
export type LockMode = (typeof LOCK_MODES)[number]

/** AE2 names blocks like an anvil: at most 50 characters, colour codes included. */
export const MAX_AE2_NAME = 50

export const CPU_MODES = ['any', 'player_only', 'machine_only'] as const

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
  /** The job is suspended (AE2 builds that support it). */
  suspended: boolean
  /** When the job last made progress, if it has made none for five minutes (ISO time); else null. */
  stalledSince: string | null
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
        suspended: a.suspended === 'true',
        stalledSince: a.stalledSince || null,
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
  /** Storage priority; null for drives without one. */
  priority: number | null
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
      priority: r.attributes.priority ? Number(r.attributes.priority) : null,
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

/**
 * AE2 15 reports a job's total as 2,147,483,647 and its progress as a share of that (weighted
 * across item and fluid work), so only the ratio means something. Unknown only without a total.
 */
export function jobProgressKnown(job: Ae2Job): boolean {
  return job.total > 0
}

/** Whether total and progress are real item counts (other AE2 versions) rather than a scale. */
export function jobCountsAreItems(job: Ae2Job): boolean {
  return job.total < INFINITE
}

export interface Ae2PatternProvider {
  resource: Resource
  id: string
  /** Registry id of the block or part, e.g. ae2:pattern_provider, ae2:cable_pattern_provider. */
  block: string
  /** A part on a cable: the side it faces. */
  side: string | null
  /** Custom name, else null. */
  name: string | null
  /** What AE2's Pattern Access Terminal shows: the custom name, else the machine next to it. */
  group: string
  /** Stack key of the icon the terminal shows. */
  groupIcon: string | null
  /** Item id chosen from the dashboard, else null. */
  icon: string | null
  priority: number
  blocking: boolean
  lock: LockMode
  /** Why the provider waits right now; 'none' when it does not. */
  locked: LockMode
  /** Listed in the Pattern Access Terminal. */
  visible: boolean
  patterns: number
}

const asLock = (value: string | undefined): LockMode => (LOCK_MODES.includes(value as LockMode) ? (value as LockMode) : 'none')

/** Pattern providers of one grid, by name (what the terminal shows). */
export function patternProviders(snapshot: Snapshot | undefined, gridId: string): Ae2PatternProvider[] {
  return ofType(snapshot, 'provider')
    .filter((r) => r.attributes.grid === gridId)
    .map((r): Ae2PatternProvider => {
      const a = r.attributes
      const slash = r.ref.id.indexOf('/', r.ref.id.indexOf('@'))
      return {
        resource: r,
        id: r.ref.id,
        block: a.block ?? '',
        side: slash < 0 ? null : r.ref.id.slice(slash + 1),
        name: a.name || null,
        group: a.group ?? '',
        groupIcon: a.groupIcon || null,
        icon: a.icon || null,
        priority: num(a.priority),
        blocking: a.blocking === 'true',
        lock: asLock(a.lock),
        locked: asLock(a.locked),
        visible: a.visible !== 'false',
        patterns: num(a.patterns),
      }
    })
    .sort((a, b) => plainLabel(a).localeCompare(plainLabel(b), undefined, { numeric: true }) || a.id.localeCompare(b.id))
}

/** What to call a provider: its name, else the terminal's name for it. */
export function providerLabel(p: Ae2PatternProvider): string {
  return p.name ?? p.group
}

const plainLabel = (p: Ae2PatternProvider) => providerLabel(p).replace(/§./g, '')

export interface PatternStack {
  key: string
  kind: string
  name: string
  amount: number
}

export interface Ae2Pattern {
  provider: string
  output: PatternStack
  inputs: PatternStack[]
}

/** Patterns from the grid's live table (rows of `provider`, `output*`, `amount`, `inputs` JSON). */
export function patternsOf(rows: Record<string, string>[] | undefined): Ae2Pattern[] {
  return (rows ?? []).flatMap((r) => {
    if (!r.output) return []
    let inputs: PatternStack[] = []
    try {
      const parsed: unknown = JSON.parse(r.inputs ?? '[]')
      if (Array.isArray(parsed)) inputs = parsed as PatternStack[]
    } catch {
      inputs = []
    }
    return [{ provider: r.provider ?? '', output: { key: r.output, kind: r.outputKind ?? 'item', name: r.outputName || r.output, amount: num(r.amount) }, inputs }]
  })
}

/** The answer of `ae2.craft.plan` (docs/modules/ae2.md), like AE2's craft confirm screen. */
export interface CraftPlan {
  plan: string
  output: PatternStack
  bytes: number
  /** Items are missing: the plan cannot start. */
  missing: boolean
  multiplePaths: boolean
  expiresInSeconds: number
  used: PatternStack[]
  missingItems: PatternStack[]
  crafted: PatternStack[]
  cpus: Array<{ id: string; number: number; name: string | null; storage: number; coProcessors: number; busy: boolean; mode: string; fits: boolean }>
}

/** A device that needs a channel and has none. */
export interface Ae2Offline {
  id: string
  /** A part on a cable: the side it faces. */
  side: string | null
  item: string
  name: string
}

/** Devices without channel from the grid's live table (rows with `row=offline`). */
export function offlineOf(rows: Record<string, string>[] | undefined): Ae2Offline[] {
  return (rows ?? [])
    .filter((r) => r.row === 'offline' && r.id)
    .map((r) => {
      const slash = r.id.indexOf('/', r.id.indexOf('@'))
      return { id: r.id, side: slash < 0 ? null : r.id.slice(slash + 1), item: r.item ?? '', name: r.name ?? '' }
    })
}

export type BusKind = 'import' | 'export' | string

export interface Ae2Bus {
  resource: Resource
  id: string
  /** The side of the cable the bus sits on. */
  side: string | null
  /** Registry id of the part item, e.g. ae2:export_bus. */
  block: string
  kind: BusKind
  name: string | null
  /** The block the bus faces (custom name if it has one); empty when nothing. */
  target: string
  targetBlock: string
  /** Configured keys; empty means everything (import buses). */
  filter: Array<{ key: string; kind: string; name: string }>
  /** Installed cards: item id to count. */
  upgrades: Array<{ item: string; count: number }>
  redstone: string
  /** Export buses only. */
  craftOnly: boolean | null
  scheduling: string | null
  /** Crafting jobs the bus requested that still run. */
  requested: number
  /** Has power and a channel. */
  active: boolean
}

/** Import and export buses of one grid: by what they face, then position. */
export function buses(snapshot: Snapshot | undefined, gridId: string): Ae2Bus[] {
  return ofType(snapshot, 'bus')
    .filter((r) => r.attributes.grid === gridId)
    .map((r): Ae2Bus => {
      const a = r.attributes
      const slash = r.ref.id.indexOf('/', r.ref.id.indexOf('@'))
      let filter: Ae2Bus['filter'] = []
      try {
        const parsed: unknown = JSON.parse(a.filter || '[]')
        if (Array.isArray(parsed)) filter = parsed as Ae2Bus['filter']
      } catch {
        filter = []
      }
      return {
        resource: r,
        id: r.ref.id,
        side: slash < 0 ? null : r.ref.id.slice(slash + 1),
        block: a.block ?? '',
        kind: a.kind ?? '',
        name: a.name || null,
        target: a.target ?? '',
        targetBlock: a.targetBlock ?? '',
        filter,
        upgrades: (a.upgrades ?? '')
          .split(';')
          .filter(Boolean)
          .map((u) => {
            const [item, count] = u.split('=')
            return { item: item ?? '', count: num(count) }
          }),
        redstone: a.redstone || 'ignore',
        craftOnly: a.craftOnly ? a.craftOnly === 'true' : null,
        scheduling: a.scheduling || null,
        requested: num(a.requested),
        active: a.active !== 'false',
      }
    })
    .sort((a, b) => busLabel(a).localeCompare(busLabel(b), undefined, { numeric: true }) || a.id.localeCompare(b.id))
}

/** What to call a bus: its name, else what it faces. */
export function busLabel(b: Ae2Bus): string {
  return (b.name ?? b.target).replace(/§./g, '')
}
