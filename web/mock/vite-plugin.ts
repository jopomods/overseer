import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Plugin } from 'vite'
import { createMockBackend, type MockOptions } from './backend.ts'

/**
 * Serves /api from the mock backend inside the Vite dev server, so the dashboard runs
 * without Minecraft or the simulator (`npm run dev:mock`). The SSE stream pushes the
 * current snapshots every 2 s with small random changes, so pages look live.
 */
export function mockApiPlugin(options: MockOptions): Plugin {
  const backend = createMockBackend(options)
  return {
    name: 'overseer-mock-api',
    configureServer(server) {
      server.middlewares.use('/api', (req: IncomingMessage, res: ServerResponse) => {
        // Connect strips the mount path from req.url; originalUrl keeps it.
        const original = (req as IncomingMessage & { originalUrl?: string }).originalUrl
        const url = new URL(original ?? req.url ?? '/', 'http://localhost')
        if (url.pathname === '/api/v1/stream') return stream(req, res)
        readBody(req).then((body) => {
          const response = backend.handle({ method: req.method ?? 'GET', url, body })
          res.statusCode = response.status
          if (response.json === undefined) return res.end()
          res.setHeader('Content-Type', 'application/json; charset=utf-8')
          res.end(JSON.stringify(response.json))
        })
      })
    },
  }

  function stream(req: IncomingMessage, res: ServerResponse) {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' })
    const push = () => {
      for (const provider of ['server', 'flux'] as const) {
        res.write(`event: snapshot\ndata: ${JSON.stringify(jitter(backend.snapshot(provider)))}\n\n`)
      }
    }
    push()
    const timer = setInterval(push, 2000)
    req.on('close', () => clearInterval(timer))
  }
}

/** ±5 % on every metric so live views visibly move. */
function jitter(snapshot: { resources: Array<Record<string, unknown>> }) {
  return {
    ...snapshot,
    resources: snapshot.resources.map((r) => ({
      ...r,
      metrics: Object.fromEntries(
        Object.entries((r.metrics ?? {}) as Record<string, number>).map(([k, v]) => [k, v * (0.95 + Math.random() * 0.1)]),
      ),
    })),
  }
}

function readBody(req: IncomingMessage): Promise<unknown> {
  return new Promise((resolve) => {
    let data = ''
    req.on('data', (chunk) => (data += chunk))
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : undefined)
      } catch {
        resolve(undefined)
      }
    })
  })
}
