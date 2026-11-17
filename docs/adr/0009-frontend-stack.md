# 0009. Frontend stack and layout testing

- Status: Accepted
- Date: 2026-11-17

## Context

The dashboard must look modern and clean and be fully usable on phones. A common
failure mode is layouts that work on desktop but overflow on narrow screens (bars and
tables running off the edge).

## Decision

### Stack

- React + Vite + TypeScript (TS 5.9; TS 7 is not yet supported by openapi-typescript)
- Tailwind CSS v4 + shadcn/ui (Radix, "Nova" preset), lucide icons
- TanStack Query (server state), TanStack Router (routing, M1a)
- Recharts via shadcn chart components. Mirrored stacked areas use `stackOffset="sign"`.
  The Sankey is prototyped with recorded Flux data first; only if Recharts is not good
  enough is ECharts added, for that chart alone.
- PWA: installable; the service worker caches only the app shell, never API responses
  with private data.
- i18n: German and English, browser language by default.
- Dark mode default. Desktop: sidebar. Phone: bottom navigation.

### Build and delivery

The production build (`web/dist`) is embedded in the mod jar under `/overseer-web` and
served by Overseer. In development Vite proxies `/api` to a running core.

### Layout safety

1. Mobile-first code rules: no fixed widths; `min-w-0` + `truncate` for names; tables
   become cards on phones; charts are always responsive.
2. Playwright runs every route at 360, 390, 768, 1024 (landscape), 1280 and 1920 px and
   **fails on any horizontal overflow**. A self-test proves the detector works.
3. Fixtures with extreme data (very long names, 200 devices, 9.2 EFE/t) catch layout
   breaks without a Minecraft server.

Implementation notes (M1a):

- TypeScript runs in `strict` mode.
- Texts live in `src/lib/i18n.ts` with typed keys; a missing translation fails the build.
- No external resources at runtime (fonts are bundled; no player-skin services, which would
  leak UUIDs to third parties and violate the CSP). Avatars are initials.
- Charts (`components/history-chart.tsx`): Recharts via shadcn `chart`, step areas (the data
  is a step function, ADR-0005). Axis preference per viewer: server uptime with dashed restart
  markers labelled with the real offline time, or calendar time with shaded offline gaps.
  Y axis shows number + SI prefix only; units are in the tooltip. Later series are dashed so
  identical series stay visible.
- PWA: `public/manifest.webmanifest` + `public/sw.js`. The service worker never touches
  `/api/*` (no private data on the device); hashed `/assets/*` are cache-first, page loads are
  network-first with the cached shell as offline fallback. Registered in production builds only.
- Live data: one `EventSource` per tab updates the TanStack Query cache; closed while the tab
  is hidden; pages poll every 5 s while it is down.

## Consequences

- Every new page must be added to `web/e2e/layout.spec.ts`.
- Live updates pause when the tab is hidden; on SSE failure the UI falls back to polling.
