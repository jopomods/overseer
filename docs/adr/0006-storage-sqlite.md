# 0006. Storage in SQLite

- Status: Accepted
- Date: 2026-11-17

## Context

History must survive restarts, be queryable for charts, and stay manageable by a
server admin without running a database server.

## Decision

- SQLite via `xerial/sqlite-jdbc` (bundled native libraries for all OSes, about 13 MB).
- Default location: `<world>/overseer/overseer.db`; path configurable so it can live on
  a different disk than the world.
- `PRAGMA journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout` set. **Exactly one
  writer thread** performs all data writes (resources, events, samples, sessions) in
  batched transactions. Readers use separate read-only connections.
- *Clarification (M1a):* low-volume control writes (logins, credentials, admin grants)
  go through one separate "control" connection owned by `AuthStore`, serialized by that
  store. They are rare (a few per user action), so WAL's single-writer lock plus the busy
  timeout absorbs any overlap with the data writer without routing them through the
  snapshot queue.
- Connections are created through `SQLiteConfig` directly, not `DriverManager`, which does
  not reliably find JDBC drivers across Forge's module layers.
- Schema changes go through numbered, forward-only migrations applied at startup and
  tested in CI against databases created by previous versions.
- Rollup and retention jobs run on the writer thread in small batches.
- Admin "Storage" page (M1b): size per provider and series, retention per resolution
  (including unlimited), size warning threshold, purge series, purge older than,
  VACUUM, backup/export.

## Consequences

- No `SQLITE_BUSY` storms: one writer, WAL readers.
- Jar grows by the size of sqlite-jdbc; acceptable for a server mod.

## Alternatives considered

- **H2 (pure Java)**: smaller jar, but less tooling and a less familiar file format.
- **Flat files / NBT**: no queries, no rollups.
