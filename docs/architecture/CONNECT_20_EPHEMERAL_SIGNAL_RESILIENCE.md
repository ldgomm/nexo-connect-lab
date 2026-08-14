# CONNECT.20 — Ephemeral signal resilience

## Scope and authority

CONNECT.20 proves that presence and typing remain bounded, disposable Redis
signals. PostgreSQL remains the durable source for messages and receipts, and
this phase neither reads nor mutates the Nexo Core database.

## Failure matrix

The runtime suite injects a Redis `FLUSHDB`, an extreme application-clock
offset, duplicate refreshes, instance shutdown without lease cleanup, and a
rapid reconnect on a second instance. The probe uses the isolated local Redis
boundary and never enables Redis persistence.

## Flush and ownership recovery

After `FLUSHDB`, the previous presence and typing handles have no authority:
refresh and release operations return the existing not-owner outcomes and do
not recreate keys. A reconnect receives fresh opaque handles owned by the new
instance. Repeated refreshes keep exactly one key per target.

## Relative time and clock skew

Lease APIs accept no client timestamps. Presence and typing stores use Redis
relative `PX`/`PEXPIRE` durations, so an injected ten-year application offset
cannot extend, shorten, or resurrect a lease.

## Crash expiry

Closing an instance without release simulates a crash. Typing disappears by
its TTL. The presence device lease disappears by its TTL and its coarse recent
marker disappears by the lease-plus-recent-window deadline. No cleanup write
to PostgreSQL is permitted.

## Durable isolation

The runtime gate hashes the complete `connect` PostgreSQL schema before and
after failure injection. Equality is mandatory. Durable messages, receipt
cursors, ordering and duplicate guarantees remain unchanged.

## Acceptance evidence

Acceptance requires deterministic store tests, the real multi-instance Redis
failure suite, zero remaining presence/typing keys, a preserved PostgreSQL
hash, all semantic and full regression gates, and an exact one-commit phase
boundary.

## Phase boundary

The only accepted subject is
`test(connect): [CONNECT.20] prove ephemeral signal resilience`. CONNECT.21 is
the protected push-device registry phase.
