<img src="assets/icon.svg" width="64" height="64" alt="Overseer logo">

# Overseer

A server-side Minecraft mod with a built-in web dashboard: live data and history from
your world and its mods, on desktop and phone.

- **Flux Networks**: networks, devices, zones, energy flow (Sankey), storage fill and
  full/empty forecasts
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

## Documentation

- [AGENTS.md](AGENTS.md): build, test and contribution rules
- [Server admin guide](docs/guides/server-admin.md) and [reverse proxy setup](docs/guides/reverse-proxy.md)
- [Recordings and the simulator](docs/guides/recordings.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Architecture decisions](docs/adr/README.md)
- [Glossary](docs/GLOSSARY.md)
