# CONNECT.18 — bounded typing signals

## Scope and authority

Typing is a short-lived convenience signal. PostgreSQL remains the durable
authority for conversations, messages and receipts; typing is never written to
history, an outbox or a migration.

## Versioned client contract

An authenticated, subscribed WebSocket may send `TYPING_START` or
`TYPING_STOP` with `typingSchemaVersion=1` and a bounded conversation reference.
Unknown nested schema versions are rejected. Recipients receive
`TYPING_STATE_CHANGED` with only schema version, opaque subject, actor type,
active state and remaining bounded lifetime.

## Start, refresh, stop and expiry

`TYPING_START` creates a six-second Redis lease. Repeating it refreshes the same
owned lease. `TYPING_STOP` removes it using compare-owner deletion. Disconnect
publishes inactive state and releases owned leases; process failure needs no
cleanup write because Redis native TTL expires the key.

## Flood control

Each WebSocket has an independent sliding window allowing six typing commands
per three seconds. Excess commands receive retryable `TYPING_RATE_LIMITED` and
do not touch Redis or fan-out.

## Conversation authorization

The sender must already be subscribed and is freshly reauthorised for every
typing command. Every recipient is also freshly reauthorised immediately before
delivery. Denied, revoked and unrelated subscriptions receive no frame.

## Multi-instance delivery

The versioned `nexo.connect.realtime.v1.typing-state` Pub/Sub channel carries a
bounded ephemeral envelope. Origin instance and event identifiers exist only
inside the transport envelope. Local origin exclusion keeps the sender socket
quiet while the same subject's other authorised devices may receive the state.

## Durable isolation and privacy

Redis keys contain digests for conversation, subject and device. Client frames
expose no device, session, connection or instance topology. Typing loss is
acceptable and never affects durable readiness, history or receipt recovery.

## Acceptance evidence

Compiled tests cover protocol validation, TTL ownership, rate limiting,
conversation leak prevention, exact origin exclusion, multi-instance dedupe and
the authenticated two-device route. The runtime gate uses real isolated Redis,
waits for native expiry, proves zero stale typing keys and compares deterministic
PostgreSQL dumps before and after the probe.

## Phase boundary

CONNECT.18 does not implement durable typing history, presence aggregation,
push, media, calls, E2EE or Nexo Core integration. CONNECT.19 adds privacy-aware
multi-device presence aggregation.
