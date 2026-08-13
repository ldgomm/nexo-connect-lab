# Nexo Connect Lab phase governance

## Current state

- Programme: `NEXO_CONNECT_LAB`
- Repository: `connect-lab`
- Branch: `main`
- Accepted phase: `CONNECT.10`
- Accepted HEAD: `b75633a445e6c7dc6bd686a0471f4078c616a14c`
- Immutable user baseline: `558d702bd5e7729721cde71d0e3080513798dcdd`
- Active phase: `CONNECT.11`
- Next phase after acceptance: `CONNECT.12`

`CONNECT.07` is the accepted governance commit `e330359...`; its historical
subject used the temporary label `[CONNECT.01]`. The hash is preserved and the
continuous numbering `CONNECT.07`–`CONNECT.56` is authoritative from now on.

The machine-verifiable policy is held in
`docs/governance/connect-phase-policy.properties`. Accepted historical phase
commits are recorded in `docs/governance/connect-phase-ledger.tsv` and component
authority is frozen in `docs/governance/connect-ownership.properties`.

## Phase lifecycle

Every numbered phase starts from its accepted clean parent and has one visible
scope and one final commit:

1. verify the accepted parent and a clean index/worktree;
2. implement only the active phase;
3. classify and inspect the complete phase diff;
4. run targeted checks and the full relevant regression gate;
5. create one commit only after every required check passes;
6. retain evidence and wait for user acceptance before starting the next phase.

Intermediate commits, commits on failure, unrelated changes, pushes and history
rewrites are forbidden. A failed installer must restore every phase-owned path
to the accepted parent and leave a clean index/worktree.

## Formatter and import safety

Manual IntelliJ IDEA `Reformat Code` (`Option+Command+L`), `Optimize Imports`
and broad cleanup actions are not part of the phase workflow. The repository's
pinned formatter remains an automated CI guard. Compilation, executable
contracts and runtime tests are the functional authority.

The canonical Kotlin/KTS contract is defined by `.editorconfig`, Spotless
`8.9.0` and ktlint `1.8.0`. Its ratchet starts at the accepted user baseline
after `CONNECT.07`, so only later code is formatted. Automated convergence and
the manual-action prohibition are documented in
`docs/governance/INTELLIJ_FORMATTING.md`.

Semantic acceptance authority is documented in
`docs/governance/SEMANTIC_ACCEPTANCE_GATES.md`. Positive behaviour is proven by
compiled, database and runtime contracts; source scans are secondary defence.

The `CONNECT.10` capacity contract is documented in
`docs/performance/CONNECT_10_SINGLE_INSTANCE_CAPACITY.md`. It records a bounded,
reproducible local envelope and never promotes a workstation measurement into a
production capacity claim.

The `CONNECT.11` distributed topology is frozen by
`docs/architecture/ADR-001_CONNECT_MULTI_INSTANCE_EPHEMERAL_REDIS_FANOUT.md`
and its machine-verifiable properties contract. PostgreSQL remains durable
truth; Redis is an ephemeral best-effort notification path, and authorised
catch-up repairs live fan-out loss.

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
