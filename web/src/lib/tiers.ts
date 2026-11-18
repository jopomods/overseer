/**
 * Voltage tiers at the end of device names, e.g. "Factory: Assembler MV" (docs/modules/flux.md).
 * Optional: the server says which scheme applies (`energyTiers` on server/server/main); with
 * "none" the dashboard shows no tiers at all. Colours are GregTech's own (GTValues.VNF).
 */
export type TierScheme = 'gregtech' | 'none'

export interface Tier {
  name: string
  /** Minecraft formatting code of the tier's colour. */
  code: string
  bold?: boolean
}

export const GREGTECH_TIERS: Tier[] = [
  { name: 'ULV', code: '8' },
  { name: 'LV', code: '7' },
  { name: 'MV', code: 'b' },
  { name: 'HV', code: '6' },
  { name: 'EV', code: '5' },
  { name: 'IV', code: '9' },
  { name: 'LuV', code: 'd' },
  { name: 'ZPM', code: 'c' },
  { name: 'UV', code: '3' },
  { name: 'UHV', code: '4' },
  { name: 'UEV', code: 'a' },
  { name: 'UIV', code: '2' },
  { name: 'UXV', code: 'e' },
  { name: 'OpV', code: '9', bold: true },
  { name: 'MAX', code: 'c', bold: true },
]

export function tiersOf(scheme: TierScheme | undefined): Tier[] {
  return scheme === 'gregtech' ? GREGTECH_TIERS : []
}

const BY_NAME = new Map(GREGTECH_TIERS.map((t) => [t.name.toLowerCase(), t]))
/** The last word, ignoring colour codes: "§6Diesel 1 HV" -> "HV". */
const LAST_WORD = /(?:^|\s)(?:§[0-9a-fk-or])*([A-Za-z]{2,3})(?:§[0-9a-fk-or])*\s*$/i

/** The tier at the end of a name (case-insensitive), or undefined. */
export function tierInName(name: string, tiers: Tier[]): Tier | undefined {
  if (tiers.length === 0) return undefined
  const m = LAST_WORD.exec(name)
  if (!m) return undefined
  const tier = BY_NAME.get(m[1].toLowerCase())
  return tier && tiers.includes(tier) ? tier : undefined
}

/** The name with its tier replaced, added or (with `null`) removed. */
export function withTier(name: string, tier: Tier | null, tiers: Tier[]): string {
  let base = name.trimEnd()
  if (tierInName(base, tiers)) base = base.replace(LAST_WORD, '').trimEnd()
  if (!tier) return base
  return base ? `${base} ${tier.name}` : tier.name
}
