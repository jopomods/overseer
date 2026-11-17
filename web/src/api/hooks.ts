import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { components } from './schema.gen'

export type Me = components['schemas']['Me']
export type Snapshot = components['schemas']['Snapshot']
export type Resource = components['schemas']['Resource']
export type Provider = components['schemas']['Provider']
export type Collectors = components['schemas']['Collectors']
export type Credential = components['schemas']['Credential']
export type History = components['schemas']['History']
export type Sessions = components['schemas']['Sessions']
export type Override = components['schemas']['Override']

/** An API failure with its HTTP status; 401 means "not logged in". */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string) {
    super(`${status} ${code}`)
    this.status = status
    this.code = code
  }
}

interface Result<T> {
  data?: T
  error?: unknown
  response: Response
}

async function unwrap<T>(call: Promise<Result<T>>): Promise<T> {
  const { data, error, response } = await call
  if (response.ok) return data as T
  const code =
    typeof error === 'object' && error && 'error' in error
      ? String((error as { error: { code?: string } }).error.code ?? 'error')
      : 'error'
  throw new ApiError(response.status, code)
}

/** Do not retry auth and permission failures. */
function retry(count: number, error: unknown) {
  if (error instanceof ApiError && error.status < 500) return false
  return count < 2
}

export const keys = {
  me: ['me'] as const,
  providers: ['providers'] as const,
  snapshot: (provider: string) => ['snapshot', provider] as const,
  collectors: ['collectors'] as const,
  credentials: ['credentials'] as const,
  sessions: ['sessions'] as const,
  overrides: (provider: string) => ['overrides', provider] as const,
  history: (resource: string, metrics: string[], rangeMs: number) => ['history', resource, metrics.join(','), rangeMs] as const,
}

export function useMe() {
  return useQuery({ queryKey: keys.me, queryFn: () => unwrap(api.GET('/api/v1/me')), retry, staleTime: 60_000 })
}

export function useProviders(enabled = true) {
  return useQuery({
    queryKey: keys.providers,
    queryFn: () => unwrap(api.GET('/api/v1/providers')),
    retry,
    enabled,
    staleTime: 5 * 60_000,
  })
}

/**
 * Latest snapshot of a provider. Kept current by the SSE stream (see live.ts); the
 * interval is only a fallback while the stream is down.
 */
export function useSnapshot(provider: string, options: { enabled?: boolean; pollMs?: number | false } = {}) {
  return useQuery({
    queryKey: keys.snapshot(provider),
    queryFn: () =>
      unwrap(api.GET('/api/v1/providers/{provider}/snapshot', { params: { path: { provider } } })),
    retry,
    enabled: options.enabled ?? true,
    refetchInterval: options.pollMs ?? false,
  })
}

export function useCollectors(enabled: boolean) {
  return useQuery({
    queryKey: keys.collectors,
    queryFn: () => unwrap(api.GET('/api/v1/admin/collectors')),
    retry,
    enabled,
    refetchInterval: 5_000,
  })
}

/** Uptime sessions and the current uptime; the base for history ranges and offline markers. */
export function useSessions() {
  return useQuery({
    queryKey: keys.sessions,
    queryFn: () => unwrap(api.GET('/api/v1/sessions')),
    retry,
    refetchInterval: 30_000,
  })
}

/** History of one resource over the last `rangeMs` of server uptime. */
export function useHistory(resource: string, metrics: string[], rangeMs: number, uptimeNow: number | undefined) {
  return useQuery({
    queryKey: keys.history(resource, metrics, rangeMs),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/history', {
          params: {
            query: { resource, metric: metrics, from: Math.max(0, (uptimeNow ?? 0) - rangeMs), points: 240 },
          },
        }),
      ),
    retry,
    enabled: uptimeNow !== undefined,
    refetchInterval: 30_000,
    placeholderData: (previous) => previous,
  })
}

/** Overseer-side settings (e.g. zones) of a provider's resources. */
export function useOverrides(provider: string) {
  return useQuery({
    queryKey: keys.overrides(provider),
    queryFn: () => unwrap(api.GET('/api/v1/overrides', { params: { query: { provider } } })),
    retry,
    staleTime: 30_000,
  })
}

export function useSetOverride(provider: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (override: Override) => unwrap(api.PUT('/api/v1/overrides', { body: override })),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.overrides(provider) }),
  })
}

export function useCredentials() {
  return useQuery({ queryKey: keys.credentials, queryFn: () => unwrap(api.GET('/api/v1/me/credentials')), retry })
}

export function useRevokeCredential() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: number) =>
      unwrap(api.DELETE('/api/v1/me/credentials/{id}', { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.credentials }),
  })
}

export function useLogoutEverywhere() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(api.POST('/api/v1/me/credentials/revoke-all')),
    onSuccess: () => client.resetQueries(),
  })
}

export function useCreateDeviceToken() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => unwrap(api.POST('/api/v1/me/device-tokens', { body: { name } })),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.credentials }),
  })
}

export function useLogout() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(api.POST('/api/v1/auth/logout')),
    onSettled: () => client.resetQueries(),
  })
}

export { unwrap }
