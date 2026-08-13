# Nexo Connect Lab phase governance

## Current state

- Programme: `NEXO_CONNECT_LAB`
- Repository: `connect-lab`
- Branch: `main`
- Accepted baseline: `CONNECT.C6`
- Baseline commit: `8939caf34d603ee650e62f6a591050e1dbad35a8`
- Baseline commit count: `29`
- Establishing phase: `CONNECT.01`
- Next phase after acceptance: `CONNECT.02`

The machine-verifiable policy is held in
`docs/governance/connect-phase-policy.properties`. Accepted historical phase
commits are recorded in `docs/governance/connect-phase-ledger.tsv` and component
authority is frozen in `docs/governance/connect-ownership.properties`.

## Phase lifecycle

Every numbered phase has one visible scope and one final commit:

1. verify the accepted parent and preserve pre-existing user changes;
2. implement only the active phase;
3. classify and inspect the complete phase diff;
4. run targeted checks and the full relevant regression gate;
5. create one commit only after every required check passes;
6. retain evidence and wait for user acceptance before starting the next phase.

Intermediate commits, commits on failure, unrelated changes, pushes and history
rewrites are forbidden. A failed installer must restore only phase-owned paths
and must preserve the user's prior index and worktree state exactly.

## Formatter and import safety

IntelliJ IDEA `Reformat Code` (`Option+Command+L`) and `Optimize Imports` are
supported user actions. Whitespace, wrapping, trailing commas, final newlines,
import ordering and removal of unused imports are not functional regressions.
Compilation, executable contracts and runtime tests remain the functional
authority. Repository-wide formatting requires its own explicit phase.

The staged empty `docker-compose.watch.yml` present at the C6 baseline belongs
to the user. It must stay empty, staged and outside phase commits until the user
explicitly authorises a different treatment.

## Permanent boundaries

Connect owns conversation transport and durable communication state. Nexo Core
owns business and ERP state. AI Lab owns interpretation and authorised handoff.
The three databases remain separate. Connect never reads or writes the Nexo
database and must not create sales, payments, stock movements, fiscal documents
or accounting entries.

Integration with Nexo Core remains disabled through `CONNECT.45`. Controlled
integration starts at `CONNECT.46` only after the stable Lab gate and approved
cross-repository contracts.
