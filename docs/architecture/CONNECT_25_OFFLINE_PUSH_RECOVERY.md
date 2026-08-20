# CONNECT.25 — Retry, token lifecycle and offline recovery

## Scope

CONNECT.25 turns the durable notification worker into an application-owned
runtime. The runtime polls the PostgreSQL outbox on one bounded fixed-delay
daemon schedule, contains a failed cycle so later polls continue, and closes
before the database lifecycle stops. Delivery remains disabled by default and
must be enabled explicitly with complete token-protection and APNs sandbox
configuration.

PostgreSQL remains the only durable authority. A provider outage changes only
the outbox attempt metadata and next-attempt time; it neither removes nor
duplicates the accepted message. Reconnect claims the same intent through the
existing lease and optimistic-version fences.

## Invalid-token lifecycle

The protected token resolver supplies the exact `token_version` used for an
APNs request. `BadDeviceToken` and `Unregistered` responses carry that version
to the settlement boundary. Retirement locks the exact owner-scoped
registration and succeeds only when the active version is unchanged.

Successful retirement sets the registration to `REVOKED` and clears the token
fingerprint, ciphertext, nonce and key version before the outbox is
dead-lettered. If a concurrent rotation already installed a newer token, the
retirement is rejected as `TokenRotated`; the intent returns to the bounded
retry path so the replacement token receives the next attempt. A stale APNs
response can therefore never revoke a newer credential.

## Offline and duplicate recovery

The acceptance sequence injects a provider outage, persists the retry, rotates
the token during an invalid-response race, then reconnects and delivers on the
third bounded attempt. Replaying the original message identity creates neither
a second durable message nor a second notification intent. The authorised
PostgreSQL catch-up returns exactly one visible message, and a cursor already
at that sequence returns none.

No push payload gains a message body, device token, recipient identity or
provider credential. Logs retain only the existing closed, sanitised delivery
event.

## Runtime keys

`CONNECT_LAB_PUSH_TOKEN_KEY_VERSION` identifies the active encryption key.
`CONNECT_LAB_PUSH_TOKEN_ENCRYPTION_KEY_B64` and
`CONNECT_LAB_PUSH_TOKEN_FINGERPRINT_KEY_B64` must each decode to exactly 32
bytes. The local environment generator creates independent values with private
file permissions. APNs credentials are required only when
`CONNECT_LAB_NOTIFICATION_DELIVERY_ENABLED=true`; the configured private-key
path must be mounted as a read-only file by the deployment environment.

## Acceptance

PASS requires a bounded and restart-safe scheduler, zero durable impact during
provider outage, exact-version invalid-token erasure, rotation-wins retry,
successful reconnect delivery, zero duplicate durable or visible messages,
PostgreSQL catch-up, formatter convergence, full unit and PostgreSQL regression,
and one final phase commit. The accepted subject is
`test(connect): [CONNECT.25] prove offline push recovery`.
