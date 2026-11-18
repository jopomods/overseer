# Recordings and the simulator

A **recording** is a sequence of provider snapshots over time (JSON Lines: a header line,
then one snapshot per line; format `overseer-recording`, version 1). Recordings power
core tests, Playwright tests with realistic data, and the **simulator**, which runs
Overseer without Minecraft.

## Rules

- Commit only **anonymized** recordings, under `fixtures/recordings/`. A test
  (`RecordingTest.committedRecordingsAreAnonymized`) fails if a file is not marked
  anonymized or contains a real UUID.
- Raw databases and `--raw` exports stay local, e.g. in
  `platform/forge-1.20.1/run/fixtures/` (ignored by git).

Anonymization replaces player UUIDs and names everywhere (ids, attributes, server MOTD;
names as whole words only; AE2 owner names count as player names), shifts every block
position (`dimension@x,y,z` in ids and references, `pos`) by a random multiple of 16 and
replaces the MOTD. Device, network, zone and CPU names are kept.

Committed recordings:

| File | Content |
|---|---|
| `flux-real-01.jsonl`, `flux-real-02.jsonl` | Flux networks and the server, a real modpack world |
| `ae2-real-01.jsonl` | AE2 only, 15 minutes every 10 s: 5 ME networks, 20 crafting CPUs with jobs, drives with cells, watched items. Live contents (the item list) are never stored, so they are not in it. |

## Export from a real world

1. Copy `world/overseer/overseer.db` (plus `-wal` and `-shm` if present) from a server or
   singleplayer world into an ignored folder.
2. Export:

   ```sh
   ./gradlew :core:exportRecording --args="platform/forge-1.20.1/run/fixtures/<name>/overseer.db fixtures/recordings/<name>.jsonl 5000"
   ```

   The third argument is the snapshot spacing in milliseconds. Paths are relative to the
   repository root. A whole history is far too big to commit; keep it small with
   `--providers=ae2,server` (only these modules) and `--last=15` (the last 15 minutes of
   uptime), e.g. `... ae2-real-01.jsonl 10000 --providers=ae2 --last=15`. Aim for 1-2 MB.
3. Check the export against its source database before committing:

   ```sh
   node scripts/check-recording-leaks.mjs <overseer.db> fixtures/recordings/<name>.jsonl
   ```

   It fails if a player UUID, player name, MOTD or original device position remains.

The exporter rebuilds full snapshots on a fixed uptime grid from the stored events and
thinned samples, up to the end of the last uptime session.

## Run the simulator

```sh
./gradlew :core:runSimulator                       # replays fixtures/recordings/*.jsonl
./gradlew :core:runSimulator --args="path/to/x.jsonl"
```

Options: `--data=<dir>` uses the `overseer.db` in that folder (e.g. a copy from a real
server; its history is served and extended — work on a copy), `--port=<n>` picks another
port (e.g. when an SSH tunnel already uses 8765):

```sh
./gradlew :core:runSimulator --args="../fixtures/recordings --data=C:/tmp/realdb --port=8766"
```

It serves the API on `http://127.0.0.1:8765` (and the built UI if `web/dist` exists),
replays each provider in a loop at the recorded pace, and prints a one-time login link,
also one for `npm run dev` (Vite on `127.0.0.1:5173` proxies `/api`). Everyone counts as
operator. Data goes to `core/build/simulator/`.

## Recordings in tests

- Core: `RecordingTest` (format, anonymizer, exporter, replay, fixture guard).
- Web: `mockApi(page, { realistic: true })` serves the last frames of
  `fixtures/recordings/flux-real-01.jsonl`.

## Available recordings

| File | Content |
|---|---|
| `flux-real-01.jsonl` | Real GregTech server (Star Technology), ~3 min, 1 Flux network with 22 devices in 6 zones, 2 members, 11 dimensions. 5 s spacing. Used by the realistic Playwright tests and `dev:mock`. |
| `flux-real-02.jsonl` | Same server, 87 min of play, 23 devices, 2 players, one input drop to ~25 kFE/t. 30 s spacing (short TPS dips are only in the database, not at this spacing). Preferred by the simulator. |
