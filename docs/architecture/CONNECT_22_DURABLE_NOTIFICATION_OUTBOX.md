# CONNECT.22 — Durable notification outbox

## Scope and authority

CONNECT.22 makes the intention to notify an offline recipient durable without
making push delivery authoritative. PostgreSQL remains the only durable truth
for messages and notification intents. APNs delivery starts in CONNECT.23.

## Atomic write boundary

A newly accepted text message, its idempotency binding and one notification
intent per active recipient device are inserted by the same PostgreSQL
transaction. Any outbox insertion failure rolls back the message, identity and
conversation sequence. An idempotent message replay creates no second intent.

The sender is excluded. Only active conversation participants with an active,
fully scope-compatible push registration receive an intent. Client recipients
carry platform scope only; business recipients retain platform, organisation
and business scope.

## Minimal durable payload

The outbox stores opaque references and routing metadata only: conversation,
message, recipient, registration, application, provider and environment. It
contains no message body, provider token, token fingerprint, ciphertext, nonce
or credentials. Token resolution remains behind the protected registry and the
future provider adapter.

## Lease, retry and dead letter

Workers claim bounded batches with `FOR UPDATE SKIP LOCKED`, an opaque lease
owner, expiry and optimistic version. Delivery and failure transitions require
the current unexpired lease, owner and version. Retries are scheduled with a
closed generic failure taxonomy that cannot retain provider bodies, tokens or
free-form text. Reaching the configured attempt limit or an explicit permanent
failure produces an auditable dead letter.

Delivery is at least once. Exactly-once provider delivery is not claimed.
Idempotency is enforced by one durable intent for each message, registration
and notification type.

## Acceptance

PASS requires migration V7, least-privilege grants, atomic rollback failure
injection, duplicate-intent count zero, lease fencing, retry and dead-letter
runtime proof, minimised payload inspection, full CI and one final phase commit.

## Phase boundary

The accepted subject is
`feat(connect): [CONNECT.22] add durable notification outbox`. CONNECT.23 adds
the APNs sandbox adapter and provider response taxonomy.
