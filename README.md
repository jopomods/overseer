<img src="assets/icon.svg" width="64" height="64" alt="Overseer logo">

# Overseer

A server-side Minecraft mod with a built-in web dashboard: live data and history from
your world and its mods, on desktop and phone.

- **Flux Networks**: networks, devices, zones, energy flow (Sankey), storage fill and
  full/empty forecasts; change names, colours, priorities, limits and members from the dashboard,
  for many devices at once, with a change log; optional GregTech voltage tiers in names
- **Applied Energistics 2**: ME networks, power, contents with history for every item,
  watchlist with targets and deficits, crafting CPUs with live jobs, drives and cells, item icons
- **Server**: TPS, MSPT, players, dimensions, and a tick profiler that shows which blocks,
  entities and mods cost tick time
- History for everything, with live views of the last minutes and ranges up to a year

Players log in with a code they confirm in-game and only see what the mods' own
permission rules allow (owners, FTB Teams parties, shares). Mods that are not installed are
skipped. Overseer runs on the server only; installing it on a client as well is optional and
only needed to export item icons (`/ovicons export`).

> Status: in active use, before 1.0. Targets Forge 1.20.1. See the [roadmap](docs/ROADMAP.md).

## Install

1. Download `overseer-X.Y.Z+forge-1.20.1.jar` from the [latest release](https://github.com/jopomods/overseer/releases/latest)
   and put it into the server's `mods/` folder. Optional check that it was built by this
   repository's release workflow: `gh attestation verify overseer-X.Y.Z+forge-1.20.1.jar -R jopomods/overseer`
   (releases after 0.8.0; their `SHA256SUMS.txt` has the checksums).
2. Start the server. The dashboard listens on `http://127.0.0.1:8765`; put a reverse proxy with
   TLS in front of it for access from outside ([guide](docs/guides/reverse-proxy.md)).
3. Open the dashboard and log in: it shows a code to confirm in game with `/ov link <code>`.

Settings, commands and permissions: [server admin guide](docs/guides/server-admin.md).

## Documentation

- [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md): how to contribute, build and test
- [Security policy](SECURITY.md): how to report vulnerabilities privately
- [Server admin guide](docs/guides/server-admin.md) and [reverse proxy setup](docs/guides/reverse-proxy.md)
- [Recordings and the simulator](docs/guides/recordings.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Architecture decisions](docs/adr/README.md)
- [Glossary](docs/GLOSSARY.md)

## License

[MIT](LICENSE). Not affiliated with Mojang, Microsoft or the authors of the supported mods.
