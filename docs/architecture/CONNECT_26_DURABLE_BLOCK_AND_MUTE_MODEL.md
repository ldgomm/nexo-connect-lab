# CONNECT.26 — Durable block and mute model

## Scope and separation

CONNECT.26 introduces a durable security-block model without turning a
notification preference into an authorization decision. A block is a
directional relationship between two active conversation participants. Its
scope contains the conversation, platform, organization and business
references copied from PostgreSQL conversation truth. A notification mute
continues to be owned by one participant for one registered device in one
conversation.

The two controls intentionally have different effects:

- either direction of an active security block denies communication;
- reciprocal blocks remain independent records and versions;
- a notification mute suppresses only that device's outbox intent;
- a mute never changes message persistence, sequence allocation, idempotency,
  history or catch-up truth.

The old conversation and participant lifecycle statuses are not the durable
relationship authority introduced here. CONNECT.27 will route send,
subscription, catch-up, receipts, presence, typing, push and future media
through the central block authorization boundary.

## Version and ownership fences

Only the authenticated blocker may apply or revoke its direction. Both actors
must be active participants in the exact conversation and platform scope. A
business principal must additionally match the conversation's organization and
business scopes. Unknown, guessed, cross-platform and stale-version requests
all return the same `NotFoundOrDenied` result.

Creation starts at version 1. Revoke and later re-apply increment the version;
an exact replay of the already requested state is unchanged and creates no
extra audit event. No delete path exists.

The read-side lookup verifies the full scope and both active participants in
the same PostgreSQL statement. The authorization port permits communication
only for an explicit `Clear` result. Active block, missing scope, unavailable
PostgreSQL truth and lookup exceptions all deny by default.

## Append-only audit

Every changed block state appends an `APPLIED` or `REVOKED` event in the same
serializable transaction as the versioned block row. Direction and full scope
are copied into each event. The application role may select and insert audit
events but cannot update or delete them.

Notification mute transitions append separate `APPLIED` and `REVOKED` events
in the same preference transaction. Lock-screen, badge or quiet-mode edits
that leave mute unchanged do not fabricate mute events. Existing active mutes
are backfilled once by migration V9. Neither audit table stores message bodies,
device tokens, provider credentials or free-text abuse reasons.

## Acceptance

PASS requires migration V9, scoped directional version fencing, reciprocal
block independence, immutable transactional audit, uniform scope denial,
fail-closed block authorization, and a PostgreSQL proof that a muted device
creates no notification intent while the message remains durable. Runtime
enforcement across all transports is explicitly the CONNECT.27 deliverable.
The accepted subject is
`feat(connect): [CONNECT.26] add durable block and mute model`.
