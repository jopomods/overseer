# 0010. Contract-first OpenAPI with generated TypeScript client

- Status: Accepted
- Date: 2026-11-17

## Context

The REST API is a product of its own ("an interface to all information about the
server"), used by the web UI and by third parties (Home Assistant, Grafana, scripts).
Backend and frontend must not drift apart.

## Decision

- `api/openapi.yaml` (OpenAPI 3.1) is the single source of truth. All routes are
  versioned under `/api/v1`.
- The TypeScript types are generated with `openapi-typescript`
  (`web/src/api/schema.gen.ts`, committed) and used through `openapi-fetch`.
  CI regenerates and fails on a diff.
- The Java side uses hand-written records; a contract test validates real responses
  from core against the spec.
- The spec is served by Overseer at `/api/docs` with a rendered viewer (M1a).

## Consequences

- API changes start in the YAML file, then code on both sides.
- Breaking API changes require `/api/v2`.

## Alternatives considered

- **Generate the spec from Java annotations**: needs a framework or a custom generator;
  the spec would follow the code instead of leading it.
- **Generate Java from the spec**: heavy generator output for a small API.
