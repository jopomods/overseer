# 0018. Mixins where a mod offers no hook

- Status: Accepted
- Date: 2026-11-19

## Context

ADR-0014 allowed one mixin, to complete a timer Forge forgot to stop, and AGENTS.md limited
mixins to fixing what Forge or Minecraft get wrong. M2.8 needs something different: players want
to choose the icon of an AE2 pattern provider, and see it where it matters, in AE2's Pattern Access
Terminal. AE2 15 (also the Star Technology fork) builds that icon itself in
`PatternProviderLogic#getTerminalGroup` (the provider item, or the machine next to it) and has no
field, setting or event to change it. Reflection cannot help: there is no value to set.

## Decision

Mixins are also allowed where a mod offers no hook for a feature players asked for, under these
conditions:

1. **Server side only.** The change lives in data the server already sends (here the terminal
   group packet), so players need nothing on their client.
2. **Optional like the mod.** Each mod gets its own mixin config (`overseer.<mod>.mixins.json`,
   `required: false`) with a plugin that applies it only when the mod is loaded
   (`Ae2MixinPlugin` asks FML's loading mod list, never the mod's classes). AGENTS.md rule 3
   holds.
3. **Fail soft.** Injections use `require = 0` and `remap = false` (mod code is not obfuscated):
   when another build of the mod lacks the method, the mixin does nothing and the feature is
   dashboard only. It never crashes the game.
4. **Small and additive.** Inject at the return and adjust the result; never replace a method or
   change the mod's own state.
5. **Data in Minecraft's own places.** What the mixin reads is stored where it travels with the
   block, so it needs no Overseer database on the server thread. The pattern provider icon is
   kept in Forge's persistent data of the block entity (`ForgeData.overseer.providerIcons`; for
   parts the cable bus, one entry per side).
6. **An ADR or a section in one** for each mixin, and a GameTest that proves its effect against
   the real mod.

The first case: `PatternProviderLogicMixin` returns AE2's group with the chosen icon; AE2's name and
tooltip stay. AE2's terminal compares groups every tick and refreshes when one changes, so an open
terminal shows the new icon at once.

## Consequences

- AGENTS.md now allows mixins for fixes (ADR-0014) and for missing mod hooks (this ADR).
- Breaking or removing the provider and placing it again loses the icon, like a renamed block that
  is broken without keeping its name. Picking it up with a wrench keeps neither.
- The mixin runs every time AE2 asks for a provider's group (every tick per provider while a
  Pattern Access Terminal is open): one NBT lookup each.

## Alternatives considered

- **Icon only in the dashboard:** no mixin, but the players asked for the icon in game.
- **Overseer database plus a lookup table on the server thread:** works for every block, but it
  means database writes for a world action and a table that goes stale when blocks move.
- **A pull request to AE2:** the right long-term fix; it would not reach the modpack's fork.
