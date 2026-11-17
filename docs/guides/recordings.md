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
names as whole words only), shifts block coordinates by a random multiple of 16 and
replaces the MOTD. Device, network and zone names are kept.

## Export from a real world

1. Copy `world/overseer/overseer.db` (plus `-wal` and `-shm` if present) from a server or
   singleplayer world into an ignored folder.
2. Export:

   ```sh
   ./gradlew :core:exportRecording --args="platform/forge-1.20.1/run/fixtures/<name>/overseer.db fixtures/recordings/<name>.jsonl 5000"
   ```

   The last argument is the snapshot spacing in milliseconds. Paths are relative to the
   repository root.
3. Check the file for anything personal before committing (search for your player name).

The exporter rebuilds full snapshots on a fixed uptime grid from the stored events and
thinned samples, up to the end of the last uptime session.

## Run the simulator

```sh
./gradlew :core:runSimulator                       # replays fixtures/recordings/*.jsonl
./gradlew :core:runSimulator --args="path/to/x.jsonl"
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
| `flux-real-01.jsonl` | Real GregTech server (Star Technology), ~3 min, 1 Flux network with 22 devices in 6 zones, 2 members, 11 dimensions. 5 s spacing. |
