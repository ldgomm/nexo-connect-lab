# CONNECT.23 — APNs sandbox delivery

## Scope

CONNECT.23 adds a replaceable notification-provider boundary, an APNs sandbox
adapter and a bounded outbox worker. It does not make push authoritative:
PostgreSQL messages and notification intents remain durable truth, and a
provider failure can only schedule a retry or create an auditable dead letter.

The phase deliberately does not start a background scheduler from the Ktor
lifecycle. Runtime outage/rotation/reconnect orchestration and invalid-token
cleanup remain in CONNECT.25. This keeps local startup independent of Apple
credentials while the adapter is exercised with a contractual fake.

## Authentication secret boundary

APNs token authentication uses an unencrypted PKCS#8 P-256 private key loaded
from an explicitly configured file path. The key bytes are never accepted from
command-line arguments, committed configuration or log fields. JWT provider
tokens contain only the required `kid`, `iss` and `iat` fields, use ES256 with
the P1363 signature representation required by JWS, and are cached for 50
minutes. A cached token is erased and regenerated after
`ExpiredProviderToken`.

Configuration, authorisation, HTTP request and response objects render private
material as redacted. Device tokens are decrypted only inside the delivery
adapter through an owner-, scope-, application-, provider- and
environment-matched PostgreSQL resolver. Callback-scoped token objects and
temporary byte/character arrays are cleared after use.

## Sandbox request contract

The concrete transport is pinned to `api.sandbox.push.apple.com`, HTTPS and
HTTP/2. It sends a background notification with priority 5 and a nonzero
24-hour expiration. The JSON payload contains only `content-available`, a
version, the notification type and opaque conversation/message references. It
contains no message body, recipient identity, device token or provider
credential and is bounded to 4096 bytes.

Apple documents HTTP/2, the `/3/device/<device-token>` path, token-based ES256
authentication, topic requirements, the 4096-byte payload limit and nonzero
expiration store-and-forward semantics in its provider API documentation:

- <https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns>
- <https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns>
- <https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns>

## Closed response taxonomy

| APNs result | Durable action |
|---|---|
| `200` | mark the claimed intent delivered |
| `BadDeviceToken`, `DeviceTokenNotForTopic`, `410/Unregistered` | dead letter as `REGISTRATION_REVOKED`; cleanup remains CONNECT.25 |
| `429` | bounded retry as `PROVIDER_RATE_LIMITED` |
| `ExpiredProviderToken` | erase cached JWT and bounded retry as `PROVIDER_UNAVAILABLE` |
| `IdleTimeout`, transport failure, `5xx` | bounded retry as timeout/unavailable |
| other `400`, `403`, `404`, `405`, `413` | dead letter as `PROVIDER_REJECTED` |

Provider response bodies and exception messages never enter persistence or
observability. Unknown reasons collapse into the closed status-based result.

## Sanitised observability

Each attempted settlement emits a typed event containing only the opaque
intent reference, application, provider, environment, attempt count, closed
diagnostic and durable settlement. Device tokens, JWTs, private-key paths,
payloads, message bodies, recipient references and provider response bodies
are absent from the event schema.

## Acceptance

PASS requires isolated JDK 21/Kotlin compilation, contractual APNs tests,
provider-outage retry proof, exact owner-scoped token resolution, secret/log
scans, PostgreSQL regression, full CI and one final phase commit. The accepted
subject is `feat(connect): [CONNECT.23] integrate APNs sandbox delivery`.
