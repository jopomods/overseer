import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { components } from './schema.gen'

export type Me = components['schemas']['Me']
export type Snapshot = components['schemas']['Snapshot']
export type Resource = components['schemas']['Resource']
export type Provider = components['schemas']['Provider']
export type Collectors = components['schemas']['Collectors']
export type Credential = components['schemas']['Credential']

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
