# Nexo Connect Lab phase governance

## Current state

- Programme: `NEXO_CONNECT_LAB`
- Repository: `connect-lab`
- Branch: `main`
- Accepted baseline: `CONNECT.USER.BASELINE` after `CONNECT.07`
- Baseline commit: `558d702bd5e7729721cde71d0e3080513798dcdd`
- Baseline parent: `e330359dc6602e9a33da891b5fdb64ed8c199f38`
- Baseline commit count: `31`
- Establishing phase: `CONNECT.08`
- Next phase after acceptance: `CONNECT.09`

`CONNECT.07` is the accepted governance commit `e330359...`; its historical
subject used the temporary label `[CONNECT.01]`. The hash is preserved and the
continuous numbering `CONNECT.07`–`CONNECT.56` is authoritative from now on.

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

The canonical Kotlin/KTS contract is defined by `.editorconfig`, Spotless
`8.9.0` and ktlint `1.8.0`. Its ratchet starts at the accepted user baseline
after `CONNECT.07`, so only later code is formatted. IntelliJ setup,
allowed user actions and convergence checks are documented in
`docs/governance/INTELLIJ_FORMATTING.md`.

The empty `docker-compose.watch.yml` is tracked by the accepted user baseline.
Later phase commits must preserve it byte-for-byte unless the user explicitly
authorises a functional watch configuration.

## Permanent boundaries

Connect owns conversation transport and durable communication state. Nexo Core
owns business and ERP state. AI Lab owns interpretation and authorised handoff.
The three databases remain separate. Connect never reads or writes the Nexo
database and must not create sales, payments, stock movements, fiscal documents
or accounting entries.

Integration with Nexo Core remains disabled through `CONNECT.45`. Controlled
integration starts at `CONNECT.46` only after the stable Lab gate and approved
cross-repository contracts.
