import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { mockApiPlugin } from './mock/vite-plugin.ts'

// The production build is embedded into the mod jar and served by the Overseer web server.
//
// Dev modes:
//   npm run dev               proxies /api to a running core (simulator or Forge dev server)
//   npm run dev:mock          no server needed: realistic data from fixtures/recordings
//   npm run dev:mock:extreme  no server needed: extreme data (long names, 200 devices)
export default defineConfig(({ mode }) => {
  const mock = mode === 'mock' || mode === 'mock-extreme'
  return {
    plugins: [
      react(),
      tailwindcss(),
      ...(mock ? [mockApiPlugin({ realistic: mode === 'mock' })] : []),
    ],
    resolve: {
      alias: { '@': path.resolve(import.meta.dirname, './src') },
    },
    server: mock
      ? {}
      : {
          proxy: {
            '/api': { target: process.env.OVERSEER_API ?? 'http://127.0.0.1:8765', changeOrigin: false },
          },
        },
    build: {
      outDir: 'dist',
      emptyOutDir: true,
    },
  }
})
