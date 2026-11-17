# 0001. Record architecture decisions

- Status: Accepted
- Date: 2026-11-17

## Context

Overseer was planned in a long design interview and reviewed by several external
reviewers. The decisions and especially the reasons behind them must stay findable for
future sessions, human or agent, without re-deriving them.

## Decision

Keep Architecture Decision Records in `docs/adr/`, numbered, one decision per file.
ADRs are written in English. A decision is reversed only by a new ADR that supersedes
the old one. Code changes that contradict an ADR must come with a new ADR in the same PR.

## Consequences

- `AGENTS.md` and `docs/ARCHITECTURE.md` link to ADRs instead of repeating reasoning.
- Reviewers can check a PR against the ADRs.
