# 0013. Versioning, branching and releases

- Status: Accepted
- Date: 2026-11-17

## Context

Releases should be automatic but deliberate, with a readable changelog, and one
Overseer version should mean the same feature set on every platform.

## Decision

- **SemVer** for Overseer, independent of Minecraft. Start at 0.1.0; 1.0.0 once Flux
  and AE2 are stable. Before 1.0, breaking changes bump MINOR.
- **Jar name** carries the platform: `overseer-X.Y.Z+forge-1.20.1.jar`.
- **Version source**: `version.txt`, managed by release-please (`release-type: simple`).
- **Conventional Commits** for every commit.
- **Branching**: feature branches, PRs into `main`, **squash merge only** (repository
  setting). The PR title is the commit on `main` and is linted in CI. Individual commits
  stay visible in the PR.
- **CI** on every push and PR: Java build and tests, web lint/typecheck/build, API client
  drift check, Playwright layout tests, jar build.
- **CI layout** (since M1b): three parallel jobs: Java build and unit tests (also builds
  the jar), GameTests on a flat-world Forge server, and web (lint, types, API drift, build,
  Playwright with 4 workers and a cached browser). Wall time is set by the slowest job.
- **Release**: release-please maintains a release PR on `main` with version bump and
  changelog. Merging it creates the tag and GitHub release; the release workflow builds
  and attaches the jar.

## Consequences

- No release without an explicit merge.
- `CHANGELOG.md` is generated; do not edit by hand.

## Alternatives considered

- **semantic-release**: releases on every push to `main`; too eager.
- **Rebase or merge commits**: full history on `main`, but noisier changelog; squash was
  chosen explicitly.
