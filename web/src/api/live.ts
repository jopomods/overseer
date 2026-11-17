import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { keys, type Snapshot } from './hooks'

export type LiveState = 'connecting' | 'live' | 'down'

/**
 * Keeps snapshot queries current through the SSE stream (ADR-0009):
 * every `snapshot` event replaces the cached snapshot of its provider.
 * The stream is closed while the tab is hidden (saves battery on phones) and
 * reopened when it becomes visible. While it is down, pages poll instead.
 */
export function useLiveUpdates(enabled: boolean): LiveState {
  const client = useQueryClient()
  const [state, setState] = useState<LiveState>('connecting')

  useEffect(() => {
    if (!enabled || typeof EventSource === 'undefined') return
    let source: EventSource | null = null

    const open = () => {
      if (source) return
      setState('connecting')
      source = new EventSource('/api/v1/stream')
      source.onopen = () => setState('live')
      source.onerror = () => setState('down')
      source.addEventListener('snapshot', (event) => {
        try {
          const snapshot = JSON.parse((event as MessageEvent<string>).data) as Snapshot
          client.setQueryData(keys.snapshot(snapshot.provider), snapshot)
          setState('live')
        } catch {
          // Ignore malformed events; the next one replaces it.
        }
      })
    }
    const close = () => {
      source?.close()
      source = null
    }
    const onVisibility = () => (document.hidden ? close() : open())

    open()
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      close()
    }
  }, [enabled, client])

  return state
}
