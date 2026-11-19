import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
export type Share = components['schemas']['Share']
export type Requestable = components['schemas']['Requestable']
export type AccessRequest = components['schemas']['AccessRequest']
export type Inventory = components['schemas']['Inventory']
export type ResourceEvent = components['schemas']['Event']
export type JobLine = components['schemas']['JobLine']
export type Watch = components['schemas']['Watch']
export type InventoryItem = components['schemas']['InventoryItem']
export type ActionResult = components['schemas']['ActionResult']
export type ActionChange = components['schemas']['ActionChange']
export type ActionLogEntry = components['schemas']['ActionLogEntry']

export interface InventoryQuery {
  q: string
  sort: 'amount' | 'name' | 'change'
  kind?: 'item' | 'fluid' | 'other'
  limit: number
}

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
  shares: (resource: string) => ['shares', resource] as const,
  requestable: (provider: string) => ['requestable', provider] as const,
  accessRequests: ['access-requests'] as const,
  job: (resource: string) => ['job', resource] as const,
  watches: (resource: string) => ['watches', resource] as const,
  events: (resource: string, attributes: string[]) => ['events', resource, attributes.join(',')] as const,
  inventory: (resource: string, q: InventoryQuery) => ['inventory', resource, q.q, q.sort, q.kind ?? '', q.limit] as const,
  allowedActions: (resource: string) => ['actions-allowed', resource] as const,
  actionLog: (resource: string) => ['action-log', resource] as const,
  history: (resources: string[], metrics: string[], rangeMs: number) =>
    ['history', resources.join(','), metrics.join(','), rangeMs] as const,
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

/** History of one or more resources over the last `rangeMs` of server uptime. */
export function useHistory(resource: string | string[], metrics: string[], rangeMs: number, uptimeNow: number | undefined) {
  const resources = Array.isArray(resource) ? resource : [resource]
  return useQuery({
    queryKey: keys.history(resources, metrics, rangeMs),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/history', {
          params: {
            query: { resource: resources, metric: metrics, from: Math.max(0, (uptimeNow ?? 0) - rangeMs), points: 240 },
          },
        }),
      ),
    retry,
    enabled: uptimeNow !== undefined && resources.length > 0,
    refetchInterval: rangeMs <= 15 * 60_000 ? 5_000 : 30_000,
    placeholderData: (previous) => previous,
  })
}

/** Overseer-side settings (e.g. zones) of a provider's resources. */
/** Contents and changes of an inventory; the server scans every 10 s, so poll at that pace. */
export function useInventory(resource: string, query: InventoryQuery) {
  return useQuery({
    queryKey: keys.inventory(resource, query),
    queryFn: () =>
      unwrap(
        api.GET('/api/v1/inventory', {
          params: { query: { resource, q: query.q || undefined, sort: query.sort, kind: query.kind, limit: query.limit } },
        }),
      ),
    placeholderData: keepPreviousData,
    refetchInterval: 10_000,
    retry,
  })
}

/** Lines of a running crafting job; live, so poll briskly while shown. */
export function useJob(resource: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.job(resource),
    queryFn: () => unwrap(api.GET('/api/v1/jobs', { params: { query: { resource } } })),
    enabled,
    refetchInterval: 2_000,
    placeholderData: keepPreviousData,
    retry,
  })
}

/**
 * The live table of a resource (`GET /api/v1/details`), e.g. the patterns of an AE2 grid. Empty
 * when the resource has none.
 */
export function useDetails(resource: string, enabled: boolean, pollMs: number | false = 10_000) {
  return useQuery({
    queryKey: ['details', resource] as const,
    queryFn: async () => {
      try {
        return (await unwrap(api.GET('/api/v1/details', { params: { query: { resource } } }))).rows
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return []
        throw error
      }
    },
    enabled,
    refetchInterval: pollMs,
    placeholderData: keepPreviousData,
    retry,
  })
}

/** What moved into and out of a network per device and item between two uptimes (ADR-0020). */
export function useFlows(resource: string, from: number | undefined, to: number | undefined) {
  return useQuery({
    queryKey: ['flows', resource, from, to] as const,
    queryFn: () => unwrap(api.GET('/api/v1/flows', { params: { query: { resource, from, to } } })),
    enabled: from !== undefined && to !== undefined,
    refetchInterval: 30_000,
    placeholderData: keepPreviousData,
    retry,
  })
}

/** Flow per minute of one item or one device (ADR-0020). */
export function useFlowHistory(resource: string, filter: { item?: string; device?: string }, from: number | undefined, to: number | undefined) {
  return useQuery({
    queryKey: ['flow-history', resource, filter.item, filter.device, from, to] as const,
    queryFn: () => unwrap(api.GET('/api/v1/flows/history', { params: { query: { resource, ...filter, from, to } } })),
    enabled: from !== undefined && to !== undefined,
    refetchInterval: 30_000,
    placeholderData: keepPreviousData,
    retry,
  })
}

/** Recorded changes of a resource, newest first; e.g. the jobs of a crafting CPU. */
export function useEvents(resource: string, attributes: string[], enabled: boolean, pollMs: number | false = false) {
  return useQuery({
    queryKey: keys.events(resource, attributes),
    queryFn: () =>
      unwrap(api.GET('/api/v1/events', { params: { query: { resource, attribute: attributes, limit: 200 } } })),
    enabled,
    refetchInterval: pollMs,
    retry,
  })
}

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

/** Players a resource is shared with (owners and admins only). */
export function useShares(resource: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.shares(resource),
    queryFn: () => unwrap(api.GET('/api/v1/shares', { params: { query: { resource } } })),
    retry,
    enabled,
  })
}

export function useAddShare(resource: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => unwrap(api.POST('/api/v1/shares', { body: { resource, name } })),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.shares(resource) }),
  })
}

export function useRemoveShare(resource: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (player: string) => unwrap(api.DELETE('/api/v1/shares', { params: { query: { resource, player } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.shares(resource) }),
  })
}

/** Watched items of a resource with their changes; amounts come live with the snapshot. */
export function useWatches(resource: string) {
  return useQuery({
    queryKey: keys.watches(resource),
    queryFn: () => unwrap(api.GET('/api/v1/watches', { params: { query: { resource } } })),
    refetchInterval: 60_000,
    retry,
  })
}

/**
 * Watch an item, or change its target (null = just watch, which also ends auto-crafting).
 * {@code restock} switches auto-crafting on or off; undefined keeps it (ADR-0021).
 */
export function useSetWatch(resource: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ item, target, restock }: { item: string; target?: number | null; restock?: boolean }) =>
      unwrap(api.POST('/api/v1/watches', { body: { resource, item, target: target ?? undefined, restock } })),
    onSuccess: (_data, { restock }) => {
      void client.invalidateQueries({ queryKey: keys.watches(resource) })
      // The auto-crafting state is a row of the live table.
      if (restock !== undefined) void client.invalidateQueries({ queryKey: ['details', resource] })
    },
  })
}

export function useRemoveWatch(resource: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (item: string) => unwrap(api.DELETE('/api/v1/watches', { params: { query: { resource, item } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.watches(resource) }),
  })
}

/** Hidden resources the viewer may ask access for. */
export function useRequestable(provider: string) {
  return useQuery({
    queryKey: keys.requestable(provider),
    queryFn: () => unwrap(api.GET('/api/v1/access/requestable', { params: { query: { provider } } })),
    retry,
    staleTime: 30_000,
  })
}

export function useRequestAccess(provider: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (handle: string) => unwrap(api.POST('/api/v1/access/requests', { body: { provider, handle } })),
    onSettled: () => client.invalidateQueries({ queryKey: keys.requestable(provider) }),
  })
}

/** Pending requests on resources the viewer manages. */
export function useAccessRequests() {
  return useQuery({
    queryKey: keys.accessRequests,
    queryFn: () => unwrap(api.GET('/api/v1/access/requests')),
    retry,
    refetchInterval: 30_000,
  })
}

export function useDecideAccess() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (decision: { resource: string; player: string; approve: boolean }) =>
      unwrap(api.POST('/api/v1/access/decisions', { body: decision })),
    onSuccess: (_, decision) => {
      client.invalidateQueries({ queryKey: keys.accessRequests })
      client.invalidateQueries({ queryKey: keys.shares(decision.resource) })
    },
  })
}

/** Admins adopt a hidden resource, owners and admins remove a registration. */
export function useRegistration(provider: string) {
  const client = useQueryClient()
  const refresh = () => {
    client.invalidateQueries({ queryKey: keys.snapshot(provider) })
    client.invalidateQueries({ queryKey: keys.overrides(provider) })
  }
  return {
    adopt: useMutation({
      mutationFn: (body: { resource: string; name?: string }) => unwrap(api.POST('/api/v1/registrations', { body })),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (resource: string) => unwrap(api.DELETE('/api/v1/registrations', { params: { query: { resource } } })),
      onSuccess: refresh,
    }),
  }
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

/** A world action the server did not apply (ADR-0016); `message` says why. */
export class ActionFailed extends Error {
  readonly status: number
  readonly result?: ActionResult

  constructor(status: number, message: string, result?: ActionResult) {
    super(message)
    this.status = status
    this.result = result
  }
}

/** World actions the viewer may run on a resource; the UI shows edit controls only for these. */
export function useAllowedActions(resource: string, enabled = true) {
  return useQuery({
    queryKey: keys.allowedActions(resource),
    queryFn: () => unwrap(api.GET('/api/v1/actions/allowed', { params: { query: { resource } } })),
    retry,
    enabled,
    staleTime: 30_000,
    select: (data) => new Set(data.types),
  })
}

/**
 * Runs a world action. Resolves with the result when it was applied (a message may still say
 * that some devices were skipped); rejects with {@link ActionFailed} otherwise.
 */
export function useRunAction(provider: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (action: { type: string; resource: string; params: Record<string, unknown> }) => {
      const { data, error, response } = await api.POST('/api/v1/actions', { body: action })
      if (response.ok && data) return data
      const body = error as (ActionResult & { error?: { message?: string } }) | undefined
      const message = body?.message ?? body?.error?.message ?? `${response.status}`
      throw new ActionFailed(response.status, message, body?.status ? body : undefined)
    },
    onSettled: (_data, _error, action) => {
      client.invalidateQueries({ queryKey: keys.snapshot(provider) })
      client.invalidateQueries({ queryKey: keys.actionLog(action.resource) })
    },
  })
}

/** Changes made to a resource from the dashboard, newest first. */
export function useActionLog(resource: string, enabled = true) {
  return useQuery({
    queryKey: keys.actionLog(resource),
    queryFn: () => unwrap(api.GET('/api/v1/actions', { params: { query: { resource, limit: 50 } } })),
    retry,
    enabled,
    refetchInterval: 30_000,
  })
}

export { unwrap }
