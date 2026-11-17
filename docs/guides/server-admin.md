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

Changes take effect on the next server start.

## Commands

All commands also work from the server console and via RCON (the console is always
super admin).

| Command | Who | What |
|---|---|---|
| `/ov link <code>` | any player | Confirms a dashboard login. The browser shows the code. |
| `/ov status` | any player | Prints the dashboard address. |
| `/ov login <player>` | level 4 / console | Prints a one-time login link (10 minutes) for that player. Recovery path if someone cannot use `/ov link`. |
| `/ov admin add <player>` | ops | Grants dashboard admin: sees everything. |
| `/ov admin remove <player>` | ops | Revokes dashboard admin. |
| `/ov sessions revoke <player>` | ops | Logs the player out of every browser session. Device tokens stay. |

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
