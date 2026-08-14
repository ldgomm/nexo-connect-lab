# CONNECT.17 — Ephemeral presence leases

Status: implemented and gated for `NEXO_CONNECT_LAB`.

## Scope and authority

CONNECT.17 turns the frozen CONNECT.16 privacy model into a bounded liveness
primitive. It does not publish presence frames or decide who may observe a
subject; those privacy decisions remain frozen and later phases consume only
the coarse authorised projection.

PostgreSQL remains the durable authority for conversations, messages and
receipts. Presence is mutable Redis state only. No migration, repository,
outbox record, durable event or exact last-seen field is added.

## Lease identity and bounded keys

Each authenticated WebSocket owns one device-scoped presence lease. The Redis
key is built from the fixed `nexo-connect-lab:presence:v1` namespace plus two
SHA-256 URL-safe digests: scoped subject identity and the server-created device
reference. Raw subject and device identifiers never appear in Redis keys.

The key is capped at 160 UTF-8 bytes. Its value contains only a bounded opaque
instance reference and a random lease reference and is capped at 192 bytes.
Keys are bounded by the connection limiter and the TTL window; abandoned keys
cannot survive beyond their lease.

## Acquire, refresh, and release

Acquire is one atomic Redis `SET ... PX` operation. The value establishes the
current instance and lease owner. A reconnect for the same target rotates the
lease reference and renews the full TTL.

Refresh uses an atomic compare-owner-and-`PEXPIRE` script. Release uses an
atomic compare-owner-and-`DEL` script. A stale instance cannot refresh or
delete a lease replaced by another instance.

The production TTL is 45 seconds and the heartbeat interval is 15 seconds.
Both are bounded, and the refresh interval must remain below half the TTL.

## Crash and reconnect semantics

A clean disconnect attempts owner-checked release. A crashed process performs
no cleanup write; Redis native TTL expiration removes the stale lease. A live
reconnect replaces ownership, renews TTL and immediately fences the prior
owner from refresh and release.

If Redis is unavailable, authenticated durable chat continues without a
presence claim. The heartbeat loop retries safely; after Redis recovers it
refreshes a surviving owned lease or reacquires an expired/replaced lease.

## Application lifecycle

The presence store is installed before the authenticated realtime transport.
After authentication and local device registration, the WebSocket acquires a
lease. The same bounded coroutine that touches the local routing registry also
refreshes Redis. Shutdown cancels that coroutine before owner-checked release,
so a late heartbeat cannot resurrect a closed connection.

The store owns a dedicated bounded Lettuce connection and closes it with the
Ktor application. Its Redis ACL is limited to the existing isolated key and
channel namespaces plus the commands required by Pub/Sub, lease mutation and
readiness.

## Durable isolation

Mutable presence never enters PostgreSQL. The runtime gate hashes the complete
`connect` schema before and after the real Redis lease probe. The hash must be
identical, the Flyway migration set must remain exactly V1–V5 and zero stale
presence keys may remain after the probe.

Redis persistence stays disabled. A Redis flush, restart or loss may remove
presence leases, but it cannot alter acknowledged messages, receipt cursors or
any other durable fact.

## Acceptance evidence

The phase gate proves all of the following:

- acquire, refresh and release are owner checked;
- reconnect rotates ownership and renews TTL;
- a crashed owner expires without cleanup writes;
- keys are namespace-bound, hashed and byte-bounded;
- Redis outage degrades presence without stopping durable chat;
- the real isolated Redis accepts only the dedicated application identity;
- PostgreSQL durable hash is preserved and mutable presence writes equal zero;
- static mutation probes reject unsafe TTL, ownership and persistence changes.

## Phase boundary

CONNECT.17 implements only presence leases. It does not add presence
subscriptions, privacy projection, aggregation, recent-online state or typing.
CONNECT.18 implements bounded typing leases next. Multi-device presence
aggregation remains assigned to CONNECT.19.
