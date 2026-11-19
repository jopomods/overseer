/**
 * Storage order of an ME network (docs/modules/ae2.md): drives and storage buses by priority, what
 * each accepts (its partition) and where AE2 would put an item.
 */
import type { Resource, Snapshot } from '@/api/hooks'
import type { Ae2Drive } from '@/lib/ae2'

export interface PartitionKey {
  key: string
  kind: string
  name: string
}

/** What a cell or storage bus accepts: everything without keys; else the keys, or all but them. */
export interface Partition {
  keys: PartitionKey[]
  exclude: boolean
  fuzzy: boolean
}

export const OPEN: Partition = { keys: [], exclude: false, fuzzy: false }

export const ACCESS_MODES = ['read_write', 'read', 'write', 'no_access'] as const
export type Access = (typeof ACCESS_MODES)[number]

/** Partition slots of a storage bus (capacity cards unlock them in game). */
export const STORAGE_BUS_SLOTS = 63

export interface Ae2StorageBus {
  resource: Resource
  id: string
  side: string | null
  block: string
  name: string | null
  target: string
  targetBlock: string
  priority: number
  partition: Partition
  access: Access
  extractableOnly: boolean
  active: boolean
}

export function parsePartition(text: string | undefined): Partition {
  if (!text) return OPEN
  try {
    const p = JSON.parse(text) as Partial<Partition>
    return { keys: Array.isArray(p.keys) ? p.keys : [], exclude: p.exclude === true, fuzzy: p.fuzzy === true }
  } catch {
    return OPEN
  }
}

/** Partitions of a drive's cells by slot, from the `partitions` attribute. */
export function cellPartitions(drive: Ae2Drive): Map<number, Partition> {
  const result = new Map<number, Partition>()
  try {
    const list = JSON.parse(drive.resource.attributes.partitions || '[]') as Array<Partition & { slot: number }>
    if (Array.isArray(list)) for (const p of list) result.set(p.slot, parsePartition(JSON.stringify(p)))
  } catch {
    // no partitions
  }
  return result
}

export function storageBuses(snapshot: Snapshot | undefined, gridId: string): Ae2StorageBus[] {
  return (snapshot?.resources ?? [])
    .filter((r) => r.ref.provider === 'ae2' && r.ref.type === 'storage_bus' && r.attributes.grid === gridId)
    .map((r) => {
      const a = r.attributes
      const slash = r.ref.id.indexOf('/', r.ref.id.indexOf('@'))
      return {
        resource: r,
        id: r.ref.id,
        side: slash < 0 ? null : r.ref.id.slice(slash + 1),
        block: a.block ?? '',
        name: a.name || null,
        target: a.target ?? '',
        targetBlock: a.targetBlock ?? '',
        priority: Number(a.priority) || 0,
        partition: parsePartition(a.partition),
        access: (ACCESS_MODES as readonly string[]).includes(a.access) ? (a.access as Access) : 'read_write',
        extractableOnly: a.extractableOnly === 'true',
        active: a.active !== 'false',
      }
    })
}

/** Stack keys without their NBT hash, as a fuzzy card compares them. */
const base = (key: string) => key.replace(/#[0-9a-f]+$/i, '')

export function accepts(partition: Partition, key: string): boolean {
  if (partition.keys.length === 0) return true
  const listed = partition.keys.some((k) => (partition.fuzzy ? base(k.key) === base(key) : k.key === key))
  return partition.exclude ? !listed : listed
}

/** One storage in the order: a drive (with its cells) or a storage bus. */
export type StorageEntry =
  | { kind: 'drive'; id: string; priority: number; drive: Ae2Drive; partitions: Map<number, Partition> }
  | { kind: 'bus'; id: string; priority: number; bus: Ae2StorageBus }

/** Drives and storage buses, highest priority first (AE2 fills them in this order). */
export function storageOrder(drives: Ae2Drive[], buses: Ae2StorageBus[]): StorageEntry[] {
  const entries: StorageEntry[] = [
    ...drives.map((d): StorageEntry => ({ kind: 'drive', id: d.id, priority: d.priority ?? 0, drive: d, partitions: cellPartitions(d) })),
    ...buses.map((b): StorageEntry => ({ kind: 'bus', id: b.id, priority: b.priority, bus: b })),
  ]
  return entries.sort((a, b) => b.priority - a.priority || a.id.localeCompare(b.id))
}

/** Whether a storage would take the key at all: some cell with room accepts it, or a writable bus. */
export function entryAccepts(entry: StorageEntry, key: string): boolean {
  if (entry.kind === 'bus') {
    const b = entry.bus
    return b.active && (b.access === 'read_write' || b.access === 'write') && accepts(b.partition, key)
  }
  const fluid = key.startsWith('fluid:')
  return entry.drive.cells.some((c) => {
    if (c.state === 'full') return false
    // Item cells take items, fluid cells fluids; add-on cells may take either.
    if (c.item.includes('fluid') !== fluid && /storage_cell/.test(c.item)) return false
    return accepts(entry.partitions.get(c.slot) ?? OPEN, key)
  })
}

/**
 * Where AE2 tries to put a key: the storages of the highest priority that accept it. At equal
 * priority AE2 prefers one that already holds the key, which only the game knows.
 */
export function destination(entries: StorageEntry[], key: string): { first: Set<string>; accepting: Set<string> } {
  const accepting = new Set(entries.filter((e) => entryAccepts(e, key)).map((e) => e.id))
  const top = entries.find((e) => accepting.has(e.id))
  const first = new Set(top ? entries.filter((e) => e.priority === top.priority && accepting.has(e.id)).map((e) => e.id) : [])
  return { first, accepting }
}
