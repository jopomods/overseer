import { useQuery } from '@tanstack/react-query'
import { api } from '@/api/client'

function useHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: async () => {
      const { data, error } = await api.GET('/api/v1/health')
      if (error || !data) throw new Error('unreachable')
      return data
    },
    refetchInterval: 10_000,
  })
}

/** M0 placeholder. The real app shell (navigation, pages) arrives in M1a. */
export default function App() {
  const health = useHealth()

  return (
    <main className="flex min-h-dvh flex-col items-center justify-center gap-6 p-4 text-center">
      <img src="/overseer.svg" alt="" className="size-20 [image-rendering:pixelated]" />
      <div className="min-w-0 space-y-2">
        <h1 className="text-3xl font-semibold tracking-tight">Overseer</h1>
        <p className="text-muted-foreground text-balance">
          Live data and history for your Minecraft world.
        </p>
      </div>
      <p className="text-sm" data-testid="health">
        {health.isPending && 'Connecting…'}
        {health.isError && <span className="text-destructive">Server unreachable</span>}
        {health.data && (
          <span>
            Server {health.data.status} · v{health.data.version}
          </span>
        )}
      </p>
    </main>
  )
}
