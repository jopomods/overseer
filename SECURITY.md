# Security policy

Overseer runs a web server inside a Minecraft server and can change the world
([ADR-0016](docs/adr/0016-world-actions.md)), so security reports are welcome and taken seriously.

## Reporting a vulnerability

Please **do not open a public issue**. Report it privately through GitHub:
[Security → Report a vulnerability](https://github.com/jopomods/overseer/security/advisories/new).
Include the Overseer version, what an attacker can do, and how to reproduce it.

You get an answer within a week. Fixes go into the next release; the advisory is published once
a fixed version is out.

## Supported versions

Only the latest release is supported. Overseer is before 1.0, so there are no backports.

## Scope

In scope: the dashboard and its API (authentication, permissions, world actions, CSRF), the
in-game commands, and the data Overseer stores. Out of scope: Minecraft, Forge and other mods
themselves, and setups that ignore the [server admin guide](docs/guides/server-admin.md) (for
example exposing the dashboard without TLS).
