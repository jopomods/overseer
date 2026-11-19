# 0017. AE2 actions: who may act and how they reach AE2

- Status: Accepted
- Date: 2026-11-19

## Context

ADR-0016 lets providers change the game, but only where the mod's own rules allow it. Flux
Networks has such rules (owner, admins, members). Applied Energistics 2 15 has none: the security
terminal is gone, and anyone who reaches a terminal can do anything on that grid. Players still
want to operate their ME networks from the dashboard (cancel and suspend crafting jobs, name CPUs
and pattern providers, set priorities, start crafting jobs).

Some of what they want is not public AE2 API: names are private fields, suspending a job exists
only in some AE2 builds (the Star Technology modpack ships a fork, `15.5.4-uelm`, that has it;
AE2 15.4.12, which Overseer compiles against, does not).

## Decision

**Who may act.** On a grid with an owner (the player who placed the identifying controller, or
who registered it, ADR-0011):

1. The owner.
2. Members of the owner's FTB Teams party (when FTB Teams is installed). They already see the
   grid; in AE2 they could also walk up to its terminal.
3. Server operators (ADR-0016), also on grids without owner.

Shares and placer access stay view only. Dashboard admins who are not operators get no extra
rights (ADR-0016). Hidden grids have no actions.

**Targets.** Every AE2 action targets the grid (`ae2/grid/<id>`); its parameters name the part
(`cpu`, `drive`, `provider`). The UI asks for the allowed actions once per grid, and permission
checks stay in one place. Changes name the part they changed, so the part's action log shows them.
The server thread checks again that the part still belongs to that grid.

**How changes reach AE2.** Through AE2's own calls where they exist (`cancelJob`, `setPriority`,
the config manager for settings). Where AE2 only has a private field (custom names of blocks and
parts), the writer sets that field by reflection and saves and syncs the block entity as AE2
does; the field names are AE2's own source names, the same in all AE2 15 builds. Features only
some AE2 builds have (job suspension) are looked up once by reflection; when missing, the action
is not offered and the UI shows no control for it. A failed lookup never fails the integration.

**Limits on purpose.** Nothing is ever moved into a player's inventory (that would turn the
dashboard into a remote terminal that ignores distance and balance). Patterns are shown, never
edited: they are items in the provider.

## Consequences

- AE2 actions use the generic endpoint and log of ADR-0016; their parameters are documented in
  `docs/modules/ae2.md`.
- A player in the owner's party can change the owner's grid. That matches what they can do in
  game; owners who do not want it leave the party.
- Reflection on private AE2 fields can break with a future AE2 version; the GameTests run every
  action against AE2 and fail loudly when a field moves.

## Alternatives considered

- **Owner only:** safest, but party members play on the same base and can already use its
  terminals.
- **A new share level "operate":** more control, but another concept to explain; can be added
  later on top of this rule.
- **Mixins (accessors) for the private names:** cleaner types, but ADR-0014 keeps mixins for
  things reflection cannot do.
