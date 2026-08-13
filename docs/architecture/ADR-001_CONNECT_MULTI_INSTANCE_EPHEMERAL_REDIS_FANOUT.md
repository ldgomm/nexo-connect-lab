# ADR-001: Multi-instance realtime fan-out with ephemeral Redis

- Status: Accepted
- Programme phase: `CONNECT.11`
- Decision version: `1`
- Durable source of truth: PostgreSQL
- Live distribution mechanism: Redis Pub/Sub

## Context

Connect currently delivers live events inside one application instance. Durable
messages, per-conversation sequences and receipt cursors already live in
PostgreSQL, and WebSocket clients repair missed delivery with authorised
catch-up. Multiple application instances need a live notification path without
creating a second source of truth.

Redis Pub/Sub can distribute transient notifications quickly, but it does not
replay notifications to a disconnected subscriber. That limitation is accepted
because the existing PostgreSQL sequence and catch-up contracts provide the
recovery path.

## Decision

Redis is used only as an ephemeral signal bus between Connect application
instances. A message or receipt is published only after its PostgreSQL
transaction commits. Redis never stores message bodies, receipt truth,
idempotency records, conversation membership or replay state.

The convergence contract is:

```text
authorise -> commit PostgreSQL -> notify Redis -> deliver locally
                                     |
                                     +-- loss/duplicate/reorder is repaired by
                                         PostgreSQL sequence plus catch-up
```

Exactly-once delivery is not claimed. Redis delivery is best-effort and
ephemeral. Duplicate notifications are tolerated, bounded local deduplication
reduces duplicate live work, and PostgreSQL uniqueness/idempotency remains the
authority for durable state.

## Channel namespace and compatibility

The channel major version is part of the channel name. Version `v1` defines:

| Channel | State | Purpose |
|---|---|---|
| `nexo.connect.realtime.v1.message-created` | active from CONNECT.13 | Notify committed durable messages |
| `nexo.connect.realtime.v1.receipt-advanced` | active from CONNECT.13 | Notify committed monotonic receipt cursors |
| `nexo.connect.realtime.v1.presence-changed` | reserved until CONNECT.17 | Ephemeral presence lease changes |
| `nexo.connect.realtime.v1.typing-changed` | reserved until CONNECT.18 | Ephemeral typing lease changes |

Publishers and subscribers may coexist only when they understand the same
channel major. Additive optional envelope fields are compatible within `v1`.
An unknown major is not decoded or delivered and increments an incompatibility
metric. A new major requires an explicit migration period and dual-publish or
dual-subscribe plan.

## Minimal envelope

Every active-channel notification contains only:

- `schemaVersion`;
- `eventId`;
- `eventType`;
- `occurredAt`;
- `conversationRef`;
- `aggregateSequence`;
- `originInstanceRef`;
- `payloadRef`.

`payloadRef` contains only the opaque durable reference needed to load or
correlate authorised state. Message bodies, bearer credentials, access or
refresh tokens, passwords and authorisation headers are forbidden. The
consuming instance reauthorises each local subscription before delivery.

## Dedupe and ordering

`eventId` is stable across a retry of the same notification. Each instance may
keep an in-memory, bounded TTL dedupe cache. The cache is an optimisation and
may be lost at any time.

Redis arrival order is never business order. For messages,
`aggregateSequence` is the PostgreSQL conversation sequence. A notification at
or below the local cursor is ignored; a gap triggers authorised catch-up. For
receipts, the PostgreSQL monotonic high-water mark wins. No global ordering is
required across conversations.

## Publish and consume lifecycle

1. Complete authorisation and commit durable state in PostgreSQL.
2. Build the minimal versioned notification from the committed result.
3. Attempt Redis publish without changing the committed outcome.
4. Deliver to authorised local sockets on the origin instance.
5. Remote instances validate version and shape, apply bounded dedupe, and
   reauthorise local recipients before live delivery.
6. On reconnect, resubscribe to channels without requesting Redis replay.
7. WebSocket clients resubscribe with their last durable sequence; PostgreSQL
   catch-up closes any gap.

Sticky sessions are not required. `originInstanceRef` enables the origin
instance to exclude its own remote notification when local delivery already
occurred.

## Failure modes

| Failure | Required behaviour | Durable impact |
|---|---|---:|
| Redis unavailable before/after publish | Preserve committed response; mark live fan-out degraded; catch-up repairs | 0 |
| Publish result unknown | Do not repeat PostgreSQL write; optional republish uses the same `eventId` | 0 |
| Subscriber disconnected | Reconnect and resubscribe; do not request Redis replay | 0 |
| Duplicate notification | Dedupe by `eventId` or suppress by durable sequence | 0 |
| Out-of-order notification | Trust PostgreSQL sequence/high-water mark; catch up gaps | 0 |
| Redis restart or flush | Rebuild subscriptions; no durable restoration from Redis | 0 |
| Application instance crash | Clients reconnect to any instance and catch up | 0 |
| PostgreSQL commit fails | Publish nothing and report the durable operation failure | 0 committed events |
| Unknown schema/channel major | Reject notification, emit metric, preserve catch-up path | 0 |
| Unauthorised or stale local subscription | Deliver nothing; reauthorise from Connect-owned state | 0 |

## Security and isolation

- Redis authentication and configuration are introduced only by CONNECT.12.
- Every key/channel uses the dedicated Connect namespace.
- Redis contains no Nexo Core or AI Lab credentials or business state.
- Connect never accesses the Nexo database.
- Notification logs contain event type, opaque references and outcome only.
- Raw envelopes are not logged in production.

## Observability

The implementation must expose publish attempts/failures, subscriber state,
decode/version rejection, dedupe hits, sequence gaps, catch-up repairs and
degraded duration. Metrics may identify an instance and event type but must not
contain message bodies or credentials.

## Consequences

Live delivery can be temporarily missed during Redis failure, but durable chat
state remains correct. Clients may briefly observe a duplicate live
notification, so live handlers remain idempotent. PostgreSQL load may increase
during catch-up after an outage; capacity and backpressure limits remain
mandatory.

Redis Streams, Redis persistence, distributed locks and durable consumer
offsets are outside this decision. Introducing any of them as durable truth
requires a new ADR and may not weaken the PostgreSQL/catch-up authority.

## Phase boundaries

- CONNECT.11 freezes this architecture only.
- CONNECT.12 implements the isolated Redis client and lifecycle.
- CONNECT.13 implements message and receipt fan-out.
- CONNECT.15 proves Redis loss, flush, partition and rejoin recovery.
