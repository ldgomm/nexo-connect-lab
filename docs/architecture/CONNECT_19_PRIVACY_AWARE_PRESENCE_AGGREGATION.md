# CONNECT.19 privacy-aware presence aggregation

## Scope and authority

Presence remains a best-effort, ephemeral projection. PostgreSQL continues to
own durable conversation, message and receipt truth; Redis holds only bounded
device leases and a bounded coarse-recent marker. An unavailable Redis result
is unknown and produces no frame rather than an invented offline state.

## Multi-device aggregation

A subject is `ONLINE` while any unexpired device lease exists. Removing one
device lease cannot make the subject offline while another device lease
remains. After the final device leaves, the subject is `RECENTLY_ONLINE` only
while its coarse marker exists; after that relative TTL expires the projection
is `OFFLINE` for an already authorised viewer.

Device leases are discovered with incremental Redis `SCAN` inside the isolated
hashed subject prefix. The projection never carries a device count, device
reference, session, connection or application-instance reference.

## Relative time and clock skew

Lease and recent-marker windows use Redis relative TTL commands. No client or
application timestamp participates in the presence decision, so application
clock skew cannot lengthen, shorten or resurrect a lease. The recent marker is
refreshed from authenticated lease activity and expires after the frozen
15-minute coarse window.

## Privacy decision pipeline

Every request evaluates relationship, block hook, mute hook, subject visibility
and ephemeral snapshot exactly once in a fixed order. Only `SELF` and
`ACTIVE_CONVERSATION_PARTICIPANT` may receive a projection. Relationship
denial, block, mute and unavailable ephemeral state collapse to the same
`SILENT_NO_FRAME` result without an externally visible reason.

The block and mute ports are explicit future integration hooks. CONNECT.19
does not create durable block or mute state and does not read Nexo Core. A
subject visibility override of `HIDE` emits `HIDDEN` only after relationship
authorisation and suppression checks succeed.

## Topology-free projection

The v1 output has exactly `schemaVersion,frameType,subjectRef,state` and uses
`PRESENCE_CHANGED`. Its state is one of
`ONLINE|RECENTLY_ONLINE|OFFLINE|HIDDEN`. Exact last-seen, lease expiry and all
device, session, connection and instance topology are absent by construction.

## Durable isolation

Presence aggregation creates no PostgreSQL migration, repository, outbox row,
message event or receipt mutation. Redis loss cannot block durable chat and
Redis is never promoted to durable truth. Nexo Core database access remains
zero and cross-repository integration remains disabled.

## Acceptance evidence

Compiled tests prove coarse state mapping, uniform denial shape, privacy
override and exact topology-free fields. Real isolated Redis proves two device
leases remain online after one release, transition to recently online after the
final release and expire to offline without changing the PostgreSQL durable
hash.

## Phase boundary

CONNECT.19 implements multi-device privacy-aware aggregation only. CONNECT.20
adds multi-instance failure injection for presence and typing under Redis
flush, clock skew, duplicate refresh, crash and rapid reconnect.
