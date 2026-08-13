# Semantic acceptance gates

## Authority

Acceptance is based on executable behaviour, not source layout. Whitespace,
line wrapping, import order and JSON field order cannot satisfy or invalidate a
functional contract by themselves.

| Area | Primary gate | Secondary defence |
| --- | --- | --- |
| Migrations | Flyway migrate/validate plus PostgreSQL schema and repository probes | exact migration set and forbidden-operation scans |
| Protocol | serialization, envelope validation and websocket runtime tests | forbidden provisional-frame and secret-field scans |
| Security | authorization decisions and authenticated runtime tests | domain-boundary scans |
| Runtime | transport, catch-up, receipt, restart and lifecycle probes | dependency and scope scans |

`scripts/verify-semantic-acceptance.sh` runs the compiled unit/runtime contract
set and a mutation probe. The same valid frame represented with different
whitespace and field order must pass; changing its protocol major while keeping
valid JSON must fail with the stable semantic error.

The full CI gate then executes the PostgreSQL migrations and runtime probes.
Text search remains defence in depth for forbidden constructs only; it is not
evidence that required behaviour exists.

## Phase workflow

Manual IntelliJ `Reformat Code`, `Optimize Imports` and broad cleanup actions
are outside the phase workflow. The repository's pinned formatter remains an
automated CI guard. Each phase starts from its accepted clean parent and creates
exactly one commit after targeted and full gates pass.
