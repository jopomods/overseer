# Server admin guide

How to install, configure and operate Overseer on a server. For TLS and public access see
[reverse-proxy.md](reverse-proxy.md).

## Install

1. Put `overseer-X.Y.Z+forge-1.20.1.jar` into the server's `mods/` folder. Clients do not
   need it.
2. Start the server once. Overseer creates `world/serverconfig/overseer-server.toml` and
   logs `Overseer dashboard: http://127.0.0.1:8765`.
3. Data lives in `world/overseer/overseer.db` (SQLite). Back it up together with the world.

Singleplayer works the same way: the integrated server starts Overseer, and the world
owner counts as operator.

## Configuration: `overseer-server.toml`

| Key | Default | Meaning |
|---|---|---|
| `web.enabled` | `true` | Start the dashboard with the server. |
| `web.bindAddress` | `127.0.0.1` | Interface to listen on. Keep it local and use a reverse proxy; `0.0.0.0` only on a trusted LAN. |
| `web.port` | `8765` | TCP port. |
| `web.trustedProxies` | `["127.0.0.1", "::1"]` | Proxies whose `X-Forwarded-For/-Proto/-Host` are believed. |
| `web.forceSecureCookies` | `false` | Mark cookies `Secure` even without `X-Forwarded-Proto: https`. |
| `web.publicUrl` | `""` | Address players open, e.g. `https://dash.example.org`. Used in links printed by `/ov`. Empty: `http://<bindAddress>:<port>`. |
| `access.ae2PlacerAccess` | `false` | AE2: players who placed any block or part of an ME network may view it (not edit). Applies without restart. |
| `actions.ae2Crafting` | `true` | AE2: owners of an ME network and their FTB Teams party may plan and start crafting jobs from the dashboard, like from a terminal anywhere. Results go into the network. Applies without restart. |
| `dashboard.energyTiers` | `AUTO` | Voltage tiers at the end of Flux device names (`Factory: Assembler MV`): coloured badges, filter, energy by tier. `AUTO` = GregTech tiers when GregTech CEu (`gtceu`) is installed, `GREGTECH` = always, `OFF` = never. Applies without restart. |

`web.*` changes take effect on the next server start.

## Commands

All commands also work from the server console and via RCON (the console is always
super admin).

| Command | Who | What |
|---|---|---|
| `/ov link <code>` | any player | Confirms a dashboard login. The browser shows the code. |
| `/ov status` | any player | Prints the dashboard address. |
| `/ov ae2 register <name>` | any player | Registers the ME network without controller the player looks at; the player becomes its owner (AE2 only). |
| `/ov login <player>` | level 4 / console | Prints a one-time login link (10 minutes) for that player. Recovery path if someone cannot use `/ov link`. |
| `/ov admin add <player>` | ops | Grants dashboard admin: sees everything. |
| `/ov admin remove <player>` | ops | Revokes dashboard admin. |
| `/ov sessions revoke <player>` | ops | Logs the player out of every browser session. Device tokens stay. |
| `/ovicons export` | ops, **client** | Only with Overseer also installed on your client: renders every item and fluid icon and sends them to the server. See below. |

### Tick profiler

The server page has a "Tick profiler" section (admins only). Pick a duration and start, or let it
measure continuously every few seconds. It shows which block and entity types cost the most tick
time over time, and the most expensive single machines with their position. It uses Forge's own
timers (the ones behind `/forge track`); Overseer fixes Forge 1.20.1's block entity timer with a
small mixin, so `/forge track start te` works again too. Measuring costs a little tick time
itself: prefer timed runs while you look for lag.

### Item icons

A dedicated server cannot render items, and it has no vanilla textures. Without an export the
dashboard shows mod textures where an item has a plain one, and the first letter otherwise.

For real icons: put the same Overseer jar into your client's `mods` folder (it stays optional;
other players do not need it), join the server as an operator and run `/ovicons export`. The
client renders all icons over a few seconds and sends them; the chat shows progress. Run it again
after adding mods. Icons are stored in `overseer.db` (table `icon`, roughly 1 kB per icon).

## Roles

- **Operators** (permission level >= 2) and the console are **super admins**.
- **Dashboard admins** are granted with `/ov admin add` and see everything.
- **Players** see public server data and whatever the mods' own rules allow (e.g. Flux
  networks they are a member of).

## Storage

Dashboard admins find **Storage** in the navigation: database size (with a configurable
warning), data per module, retention for raw data / minute / hour values (0 = forever,
counted in server uptime), deleting a module's history, compacting the database and
downloading a backup.

## Health

`GET /api/v1/health` needs no login and returns `ok` or `degraded` plus the age of the
newest data. Suitable for uptime monitors.
