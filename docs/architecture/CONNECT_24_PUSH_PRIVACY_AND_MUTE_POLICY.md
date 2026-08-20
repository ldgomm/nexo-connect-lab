# CONNECT.24 — Push privacy and mute policy

## Scope

CONNECT.24 makes notification presentation an explicit, durable policy per
conversation and registered device. PostgreSQL stores only the preference and
the privacy-safe presentation decision; message content and provider secrets
remain outside the notification outbox.

The preference is owner-scoped to the authenticated business or client
principal, an active conversation participant and that principal's active
device registration. Creation and updates are version fenced. A caller cannot
distinguish a missing conversation, inactive membership, foreign device or
stale version: every case returns the same denied result.

## Decision contract

The policy is evaluated while a newly accepted message and its notification
intents share one PostgreSQL transaction:

| Preference | Durable result |
|---|---|
| muted | no notification intent for that device |
| generic privacy | generic localised alert; no message or identity preview |
| hidden privacy | background-only notification |
| badge `SET_ONE` | APNs badge is exactly `1` |
| badge `UNCHANGED` | APNs badge field is absent |
| quiet mode | background-only and badge unchanged |

No preference row means the product default: a generic localised alert and
badge `1`. The outbox freezes `presentation_mode` and `badge_mode` so every
retry preserves the decision made atomically with message acceptance. A
setting changed concurrently with message acceptance applies to the next
transaction that observes it; CONNECT.24 does not claim cross-request
linearisation.

## Lock-screen privacy

The generic APNs alert uses only the fixed localisation keys
`CONNECT_NOTIFICATION_TITLE` and `CONNECT_NOTIFICATION_NEW_MESSAGE`. It has no
literal body, localisation arguments, sender name, recipient identity or
conversation title. Hidden and quiet notifications contain no `alert` field.

Every payload retains only `content-available`, a schema version, the closed
notification type and opaque conversation/message references. Device tokens,
provider credentials and message bodies never enter the payload or durable
intent.

Generic alerts and badge changes use APNs push type `alert` with priority `10`.
A truly background-only notification with an unchanged badge uses push type
`background` with priority `5`.

## Quiet-mode hook

`NotificationQuietModeHook` is an injected policy boundary. CONNECT.24 ships a
deterministic implementation backed by the stored `ON|OFF` choice. Calendar,
time-zone and scheduled quiet-hours orchestration can replace the hook without
changing the durable policy or APNs adapter.

## Acceptance

PASS requires owner and version fencing, PostgreSQL migration verification,
zero outbox rows for a muted recipient device, exact per-device presentation
snapshots, fixed-key generic APNs alerts, hidden background payloads, absence
of body/PII/secrets, full regression and one final phase commit. The accepted
subject is `feat(connect): [CONNECT.24] enforce push privacy and mute policy`.

Runtime worker scheduling, invalid-token cleanup and offline recovery remain
the scope of CONNECT.25.
