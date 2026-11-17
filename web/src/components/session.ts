import { createContext, useContext } from 'react'
import type { Me } from '@/api/hooks'
import type { LiveState } from '@/api/live'

interface Session {
  me: Me
  live: LiveState
}

export const SessionContext = createContext<Session | null>(null)

/** Logged-in player and live connection state for pages inside the shell. */
export function useSession(): Session {
  const session = useContext(SessionContext)
  if (!session) throw new Error('useSession outside AppShell')
  return session
}
