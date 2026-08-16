# CONNECT.21 — Protected push-device registry

## Scope and authority

CONNECT.21 owns only durable push-device registrations inside the isolated
Connect PostgreSQL schema. It does not deliver notifications, read the Nexo
Core database, or mutate business state.

## Protected token boundary

Raw provider tokens enter through a closeable byte-backed secret. At rest they
are AES-256-GCM ciphertext with a random 96-bit nonce and a versioned key. The
complete platform, optional organization/business scope, subject, actor,
application, provider and environment form authenticated data. Tokens are
never returned by registry results or rendered by value objects.

HMAC-SHA256 fingerprints support equality and uniqueness without storing raw
tokens or device references. The token fingerprint key is separate from every
encryption key.

## Ownership and isolation

The database and Kotlin domain enforce actor-shaped scopes and application
ownership. Client, business and admin applications cannot be mixed. Every
lookup, rotation, revocation and listing predicate includes the complete owner
scope plus application, provider and environment.

Unknown registrations, stale versions, guessed references and cross-tenant
attempts collapse to the same `NotFoundOrDenied` result. A global partial
unique index prevents an active provider token from being rebound across
owners.

## Lifecycle

Registration is idempotent for the same protected token and scoped device.
Changing a token requires an explicitly version-fenced rotation. Revocation
clears ciphertext, nonce, fingerprint and key version in the same transaction,
leaving only non-secret audit metadata. PostgreSQL serializable transactions
retry only serialization/deadlock states and never log token material.

## Acceptance evidence

Acceptance requires migration V6, least-privilege app grants, compiled crypto
tests, real PostgreSQL repository tests, uniform denial for guessing and tenant
crossover, cryptographic erasure on revoke, zero token disclosure, full CI and
one final phase commit.

## Phase boundary

The accepted subject is
`feat(connect): [CONNECT.21] add protected push device registry`. CONNECT.22
adds the durable notification outbox; provider delivery is intentionally not
part of this phase.
