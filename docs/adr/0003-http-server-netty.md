# 0003. HTTP server on Netty with relocated codec-http

- Status: Accepted
- Date: 2026-11-17

## Context

The mod serves the dashboard and API itself; there is no second process. Options were
an embedded framework (Javalin on Jetty, Undertow) or Netty, which Minecraft already
ships. Mod jars share one classpath with up to hundreds of other mods, so bundled
libraries risk version conflicts.

Fact check (2026-11-17): Minecraft 1.20.1 ships Netty 4.1.82.Final with `netty-buffer`,
`netty-codec`, `netty-common`, `netty-handler`, `netty-resolver` and `netty-transport`,
but **not** `netty-codec-http`. Forge 47 adds none.

## Decision

- Build the HTTP server on Minecraft's Netty 4.1.82 (event loop, channels, buffers).
- Bundle `netty-codec-http` 4.1.82.Final (same version as the shipped core) and
  **relocate** it into `io.github.jopojp.overseer.shadow.netty...` with the Shadow
  plugin, then reobfuscate the shadow jar. This keeps it invisible to other mods and
  avoids Forge JarJar version resolution.
- `core` declares Netty as `compileOnly` at exactly that version; the standalone
  simulator includes it at runtime.
- Write only a small router on top of Netty's HTTP codec (`HttpServerCodec`,
  `HttpObjectAggregator`, chunked writes for SSE). No full framework.
- REST for queries, Server-Sent Events for live updates (one-way, proxy-friendly,
  auto-reconnect in browsers).

## Consequences

- About 0.6 MB added to the jar instead of 5-8 MB for Jetty.
- We own routing, request limits and static file serving, and must test them
  (path traversal, size limits) in core.
- When a platform's Minecraft ships a different Netty version, that platform's build
  bundles the matching codec-http version.

## Alternatives considered

- **Javalin/Jetty shaded**: comfortable API, large jar, more relocation work.
- **JDK `com.sun.net.httpserver`**: no dependency, but no proper SSE/async story and
  limited performance.
- **WebSocket instead of SSE**: bidirectional is not needed for a read-only dashboard;
  SSE is simpler and passes through reverse proxies without extra config.
