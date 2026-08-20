# Nexo Connect Lab phase governance

## Current state

- Programme: `NEXO_CONNECT_LAB`
- Repository: `connect-lab`
- Branch: `main`
- Accepted phase: `CONNECT.21`
- Accepted HEAD: `106f8d6664f58f8e976315fd84f946ad2826c75e`
- Immutable user baseline: `558d702bd5e7729721cde71d0e3080513798dcdd`
- Active phase: `CONNECT.22`
- Next phase after acceptance: `CONNECT.23`

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

`CONNECT.12` implements the isolated Redis application identity, typed client
configuration, bounded timeouts/request queue, reconnect circuit and explicit
ephemeral readiness. Redis remains optional for durable readiness and has local
persistence disabled.

`CONNECT.13` implements the frozen v1 message and receipt Pub/Sub channels,
minimal durable-reference envelopes, origin exclusion, bounded local dedupe,
destination reauthorisation and authorised PostgreSQL payload reload. Redis
cannot create or modify durable chat state.

`CONNECT.14` coordinates multiple devices through a bounded local connection
registry. Server-generated connection, device and session references are
opaque, short-lived and never authorisation credentials. Redis fan-out reaches
each destination instance; exact-origin receipt exclusion preserves delivery
to the subject's other authorised devices without sticky sessions.

`CONNECT.15` proves Redis flush, non-responsive partition, complete loss and
rejoin. Durable readiness remains independent, PostgreSQL history and receipt
cursors remain authoritative, and authorised catch-up repairs every missed
live notification without acknowledged loss or durable duplication.

`CONNECT.16` freezes the presence privacy contract before implementation. Only
self and active conversation participants may receive a coarse state. Unknown,
blocked and unrelated subjects receive no frame; exact last-seen, device
topology and durable presence history are forbidden. Versioned v1 frames carry
only an opaque subject reference and the policy-filtered state.

`CONNECT.17` implements device-scoped Redis presence leases. Authenticated
WebSockets acquire and refresh bounded TTL keys; compare-owner scripts fence
stale instances during refresh and release, reconnect rotates ownership, and a
crash disappears through Redis native expiry. Redis outage degrades presence
without stopping durable chat, and PostgreSQL receives zero mutable presence
state.

`CONNECT.18` implements bounded typing signals. Authenticated subscribed
WebSockets start, refresh and stop six-second Redis leases through a versioned
frame; every sender and recipient is freshly conversation-authorised. A
per-connection sliding window rejects floods, multi-instance Pub/Sub excludes
the exact origin, PostgreSQL receives zero typing writes, and client frames
expose neither device nor instance topology.

`CONNECT.19` aggregates device-scoped leases into one privacy-aware subject
projection. Any active device keeps the subject online; final disconnect moves
to a bounded coarse recent window driven only by Redis relative TTL.
Relationship, future block/mute hooks and visibility are evaluated in a
uniform pipeline, while output excludes device counts, exact time and all
routing topology.

`CONNECT.20` injects Redis flush, extreme application clock skew, duplicate
refreshes, instance crash and rapid reconnect into presence and typing. Old
handles cannot resurrect flushed state, fresh instance ownership is fenced,
all ephemeral keys expire, and the complete PostgreSQL durable hash remains
unchanged.

`CONNECT.21` introduces the durable push-device registry in the isolated
Connect PostgreSQL schema. Tokens are protected with versioned AES-256-GCM,
separate HMAC fingerprints and full owner/application authenticated data.
Every operation enforces actor-shaped scopes, uniform denial and optimistic
version fencing; revocation cryptographically erases stored token material.
Public registry results expose metadata only, and provider delivery remains
deferred to the notification outbox and adapter phases.

`CONNECT.22` adds the durable notification outbox. A newly accepted message,
its idempotency binding and every eligible recipient-device intent share one
PostgreSQL transaction. Intents carry references only, use bounded claim
leases, retries and dead letters, and never store message bodies or provider
tokens. Push remains a best-effort notification path; APNs delivery is deferred
to `CONNECT.23`.

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
