# Architecture Decision Records

One file per decision: context, decision, consequences, alternatives. ADRs are never
edited to reverse a decision; a new ADR supersedes the old one and both link to each
other. Small clarifications are fine.

| # | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-module-structure.md) | Module structure: Minecraft-free core, thin platform adapters | Accepted |
| [0003](0003-http-server-netty.md) | HTTP server on Netty with embedded codec-http | Accepted |
| [0004](0004-threading-and-snapshots.md) | Threading model and snapshot hand-off | Accepted |
| [0005](0005-data-model.md) | Data model: events vs metrics, stable identity, two clocks | Accepted |
| [0006](0006-storage-sqlite.md) | Storage in SQLite | Accepted |
| [0007](0007-authentication.md) | Authentication: in-game link codes, sessions, device tokens | Accepted |
| [0008](0008-permissions.md) | Permissions: global roles plus mod-native rules, default deny | Accepted |
| [0009](0009-frontend-stack.md) | Frontend stack and layout testing | Accepted |
| [0010](0010-contract-first-api.md) | Contract-first OpenAPI with generated TypeScript client | Accepted |
| [0011](0011-ae2-discovery-and-access.md) | AE2 grid discovery and access | Accepted |
| [0012](0012-item-icons.md) | Item icons in the browser | Accepted |
| [0013](0013-versioning-and-releases.md) | Versioning, branching and releases | Accepted |
| [0014](0014-tick-profiler.md) | Tick profiler with Forge's timers and one mixin | Accepted |
| [0015](0015-ci-suites-and-path-rules.md) | CI: GameTest suites in parallel, path rules, one status | Accepted |
| [0016](0016-world-actions.md) | World actions: changing the game from the dashboard | Accepted |
| [0017](0017-ae2-actions.md) | AE2 actions: who may act and how they reach AE2 | Accepted |
| [0018](0018-mixins-for-missing-mod-hooks.md) | Mixins where a mod offers no hook | Accepted |
| [0019](0019-actions-that-finish-later.md) | World actions that finish later and answer with data | Accepted |

## Template

```markdown
# NNNN. Title

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD

## Context
## Decision
## Consequences
## Alternatives considered
```
