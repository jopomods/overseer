import { useState } from 'react'
import { cn } from '@/lib/utils'

/** Keys whose icon is known to be missing in this page view: no request, no flicker. */
const missing = new Set<string>()

/**
 * Icon of a stack key (served by Overseer: exported from a client, or a mod texture). Falls back to
 * the first letter of the name. Animated textures arrive as a strip: only the first frame shows.
 */
export function ItemIcon({ stackKey, name, className }: { stackKey: string; name: string; className?: string }) {
  const [failed, setFailed] = useState(() => missing.has(stackKey))
  const fluid = stackKey.startsWith('fluid:')
  if (failed) {
    return (
      <span
        aria-hidden
        className={cn(
          'bg-muted text-muted-foreground flex size-6 shrink-0 items-center justify-center rounded-sm text-[0.65rem] font-semibold uppercase',
          fluid && 'rounded-full',
          className,
        )}
      >
        {name.trim().charAt(0) || '?'}
      </span>
    )
  }
  return (
    <span aria-hidden className={cn('flex size-6 shrink-0 overflow-hidden', className)}>
      <img
        src={`/api/v1/icon?key=${encodeURIComponent(stackKey)}`}
        alt=""
        loading="lazy"
        decoding="async"
        className="size-6 object-cover object-top [image-rendering:pixelated]"
        onError={() => {
          missing.add(stackKey)
          setFailed(true)
        }}
      />
    </span>
  )
}
