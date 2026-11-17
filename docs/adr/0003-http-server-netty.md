# 0003. HTTP server on Netty with embedded codec-http

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
- Bundle `netty-codec-http` 4.1.82.Final (same version as the shipped core) with
  **Forge Jar-in-Jar**, declared with the range `[4.1.82.Final,4.2)`. If several mods
  embed it, FML loads one copy that satisfies all ranges; staying on the 4.1 line keeps
  it compatible with Minecraft's Netty core.
- `core` declares Netty as `compileOnly` at exactly that version; the standalone
  simulator includes it at runtime.
- Write only a small router on top of Netty's HTTP codec (`HttpServerCodec`,
  `HttpObjectAggregator`, chunked writes for SSE). No full framework.
- REST for queries, Server-Sent Events for live updates (one-way, proxy-friendly,
  auto-reconnect in browsers).

## Revision (M1a, 2026-11-17)

The original decision was to shade and relocate codec-http with the Shadow plugin.
Combining Shadow, ModDevGradle reobfuscation and Jar-in-Jar (needed anyway for
sqlite-jdbc, whose native libraries cannot be relocated) proved needlessly complex.
Jar-in-Jar with a version range gives the same safety: FML deduplicates embedded
libraries across mods, and the codec-http package does not overlap with the Netty jars
Minecraft ships. Verified on a Forge 1.20.1 dev server.

## Consequences

- About 0.6 MB added to the jar instead of 5-8 MB for Jetty.
- We own routing, request limits and static file serving, and must test them
  (path traversal, size limits) in core.
- When a platform's Minecraft ships a different Netty version, that platform's build
  bundles the matching codec-http version.

## Alternatives considered

- **Javalin/Jetty shaded**: comfortable API, large jar, more relocation work.
- **Relocating codec-http with Shadow**: see Revision above.
- **JDK `com.sun.net.httpserver`**: no dependency, but no proper SSE/async story and
  limited performance.
- **WebSocket instead of SSE**: bidirectional is not needed for a read-only dashboard;
  SSE is simpler and passes through reverse proxies without extra config.
