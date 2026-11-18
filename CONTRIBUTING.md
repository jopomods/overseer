# Contributing

Thanks for helping. Overseer is a small project; a few rules keep it that way.

- **Read [AGENTS.md](AGENTS.md) first.** It has the layout, the commands, the hard rules (for
  example: `core` never imports Minecraft, optional mods stay optional, default deny) and the Git
  workflow. It applies to people and coding agents alike.
- **Issues before big changes.** For a new feature or mod integration, open an issue first so we
  can agree on the scope. One mod integration is one milestone ([roadmap](docs/ROADMAP.md)).
- **Pull requests** go into `main`, are small, have a
  [Conventional Commit](https://www.conventionalcommits.org/) title (`feat(flux): ...`) and are
  squash-merged. The **CI ok** check must be green ([ADR-0015](docs/adr/0015-ci-suites-and-path-rules.md)).
- **Docs move with code**: a change to behaviour or a decision updates `docs/` in the same PR, with
  an ADR for decisions ([docs/adr](docs/adr/README.md)).
- **Tests**: `./gradlew build`, `./gradlew gameTestAll` for anything that touches the game, and
  `npm run test:e2e` in `web/` for the UI.
- **No personal data** in commits: no real player names, UUIDs, server addresses or databases.
  Recordings are anonymized ([guide](docs/guides/recordings.md)).

Code, comments, commits and docs are in English; the UI has English and German texts.

By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).
