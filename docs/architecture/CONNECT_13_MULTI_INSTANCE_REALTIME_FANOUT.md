# CONNECT.13 multi-instance realtime fan-out

- Programme: `NEXO_CONNECT_LAB`
- Phase: `CONNECT.13`
- Durable truth: PostgreSQL
- Ephemeral transport: Redis Pub/Sub
- Delivery claim: best effort, not exactly once

## Implemented flow

Messages and receipt cursors are committed to PostgreSQL before any live
notification is attempted. The origin instance publishes a minimal v1 envelope
to the frozen message or receipt channel and then delivers to its authorised
local sockets. Redis failure never changes the committed HTTP outcome.

Each remote instance validates the channel and schema version, rejects an
oversized or malformed envelope, excludes notifications carrying its own
`originInstanceRef`, and applies a bounded five-minute in-memory dedupe cache.
The envelope contains an opaque durable reference but no message body or
credential.

Before a remote socket receives anything, its current conversation membership
is authorised again. The durable payload is then loaded from PostgreSQL under
that principal and matched against the envelope reference, sequence/version and
timestamp. A stale, denied or unavailable load produces no live delivery.

## Channels

| Channel | Payload |
|---|---|
| `nexo.connect.realtime.v1.message-created` | committed message reference and conversation sequence |
| `nexo.connect.realtime.v1.receipt-advanced` | committed receipt subject reference and cursor version |

Both channels are subscribed through the dedicated `nexo_connect_lab_app`
identity. Subscriber startup is asynchronous, retries with bounded backoff and
relies on Lettuce resubscription after reconnect. Incoming work is processed by
one bounded consumer queue per application instance.

## Correctness boundaries

- PostgreSQL remains the only durable source of messages and receipts.
- Redis stores no replay cursor, idempotency key, body or authorisation state.
- A duplicate notification cannot create a second durable record.
- Per-conversation sequence and receipt high-water marks remain authoritative.
- Missed, reordered or dropped live notifications are repaired by authorised
  PostgreSQL catch-up.
- Cross-conversation delivery is prevented by subscription selection followed
  by destination-side reauthorisation.
- Sticky sessions are not required.

## Acceptance

The phase gate uses two independent application fan-out nodes on the same real
Redis instance. It proves message and receipt propagation, origin exclusion,
duplicate suppression, destination reauthorisation, zero cross-conversation
leaks and an unchanged PostgreSQL dump before and after the fan-out run.

Redis loss, flush, partition and rejoin recovery remain the dedicated scope of
`CONNECT.15`.
