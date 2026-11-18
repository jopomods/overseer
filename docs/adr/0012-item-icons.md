# 0012. Item icons in the browser

- Status: Accepted (implementation in M2)
- Date: 2026-11-17

## Context

The server cannot render item icons: block items are 3D models, GregTech material items
are tinted at runtime, some items use dynamic renderers. The target modpack contains a
third-party icon exporter (AE2WebIntegration, undocumented format).

## Decision

Priority order:

1. **Client exporter** (primary). Overseer installed on a client is optional; when it
   is, an admin runs `/ov icons export`. The client renders all items into a WebP atlas
   and uploads it to the server. Needed once per modpack version.
2. **Jar texture fallback**. The server reads flat item textures directly from mod jars.
   Covers simple items only.
3. **Placeholder**: colored initial letter.

Icons are addressed by item id + NBT hash and served with immutable caching headers.
The third-party `.ae2wi-icons` format is not read.

## Notes

- 2026-11-18 (M2k): implemented differently in detail: the client command is `/ovicons export`
  (a client command; `/ov` stays the server's), icons are single 32x32 PNGs per item and fluid,
  sent in batches below 32 KiB over an optional Forge network channel instead of an HTTP atlas
  upload (the client is already authenticated as a player; operators only), and stored per stack
  key in SQLite. See docs/modules/ae2.md and ADR-0011 notes.

## Consequences

- The client part is an optional companion; every feature works without it, with
  plainer icons.
- Recipe export (backlog) can reuse the client exporter path.
