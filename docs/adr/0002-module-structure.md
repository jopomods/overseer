# 0002. Module structure: Minecraft-free core, thin platform adapters

- Status: Accepted
- Date: 2026-11-17

## Context

Overseer starts on Forge 1.20.1 (the target modpack), but should be able to support
other loaders (NeoForge, Fabric) and Minecraft versions later without a rewrite.
Minecraft and mod APIs change substantially between versions (1.20.1 to 1.21 changed
item data, registries and networking; Flux and AE2 change with them). Integrations must
be optional: if a mod is missing, its part is simply not loaded.

## Decision

A Gradle multi-project:

- `core/`: plain Java 17. Provider contract, data model, storage, HTTP, auth,
  permissions, simulator. Must never import Minecraft, Forge or any mod API.
- `web/`: the dashboard, built into the mod jar.
- `platform/<loader>-<mc>/`: one module per supported loader and Minecraft version,
  starting with `platform/forge-1.20.1` (ModDevGradle Legacy). Contains the entry
  point, tick collector, commands and one adapter package per integrated mod.

One jar per platform contains platform + core + web. Integrations are soft
dependencies: each adapter is only class-loaded after `ModList.isLoaded(modId)`.

Version support is driven by actual use. A second platform module is added only when
someone plays that version. When that happens, evaluate Stonecutter to share one source
tree across versions instead of copying adapters.

Architectury is not used: the core split already gives loader independence, and every
mod API differs per loader and version anyway.

## Consequences

- Most of the code (estimated 80 %: storage, HTTP, auth, permissions, UI) never changes
  for a new Minecraft version.
- Porting = compiling the adapter against the new version and fixing what the compiler
  reports.
- core is fully testable in CI without starting Minecraft.

## Alternatives considered

- **Single Forge module**: fastest start, but every port touches everything.
- **Architectury**: adds a runtime dependency without removing per-version adapter work.
- **Separate jar per integration** (`overseer-flux.jar`): more files for users; the
  package split keeps this possible later.
