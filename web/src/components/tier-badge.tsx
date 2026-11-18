import { mcColor } from '@/lib/mc-text'
import type { Tier } from '@/lib/tiers'
import { cn } from '@/lib/utils'

/** A voltage tier in GregTech's colour, e.g. a gold "HV". Only shown when the server uses tiers. */
export function TierBadge({ tier, className }: { tier: Tier; className?: string }) {
  const color = mcColor(tier.code)
  return (
    <span
      className={cn('shrink-0 rounded border px-1 text-[0.7rem] leading-4', tier.bold && 'font-bold', className)}
      style={{ color, borderColor: `color-mix(in srgb, ${color} 45%, transparent)` }}
      data-testid="tier-badge"
    >
      {tier.name}
    </span>
  )
}
