# CONNECT.16 presence privacy contract

## Scope and authority

Presence is an ephemeral projection for authorised Connect participants. It is
not identity authority, proof of human activity, an audit record or durable
chat history. PostgreSQL remains the authority for conversations, messages and
receipts; Redis may later hold only bounded presence leases.

Absence of a presence frame means unknown, never offline. A caller cannot use
presence to discover an account, conversation membership, block state, device,
session, connection or application-instance topology.

## State model

The frozen public state set is `ONLINE|RECENTLY_ONLINE|OFFLINE|HIDDEN`.

- `ONLINE` means at least one unexpired authorised lease exists.
- `RECENTLY_ONLINE` is a coarse 15-minute projection after the last lease
  expires; it never includes the underlying activity time.
- `OFFLINE` means an authorised relationship exists and no current or recent
  lease is visible under policy.
- `HIDDEN` means an authorised relationship exists but the subject's policy
  suppresses its coarse state.

These states do not expose device counts, device identifiers, exact activity
times or the reason why visibility is hidden.

## Relationship policy

The only visibility-bearing relations are `SELF` and
`ACTIVE_CONVERSATION_PARTICIPANT`. Every target-specific decision runs in this
order: authenticate the actor, authorise an active relationship, apply block
rules, apply the subject visibility policy, then project a coarse state.

`NO_ACTIVE_RELATION`, `BLOCKED` and `UNKNOWN_SUBJECT` all produce
`SILENT_NO_FRAME`. They share the same externally observable result and timing
class. `HIDDEN` is emitted only after relationship authorisation, so it cannot
be used for account or membership enumeration.

## Privacy decision order

The subject policy is `SHARE_COARSE` or `HIDE`. `SHARE_COARSE` may expose only
the four-state projection. `HIDE` produces `HIDDEN` for an already authorised
relationship. Exact last-seen is disabled and cannot be inferred from frame
fields, a replay log or a durable cursor.

Block evaluation precedes subject policy evaluation. Neither the blocked actor
nor an actor without an active relation receives a target-specific denial,
state, placeholder or distinguishable not-found result.

## Versioned frames

The v1 client request is `PRESENCE_SUBSCRIBE` with exactly
`schemaVersion,frameType,subjectRef`. The v1 server notification is
`PRESENCE_CHANGED` with exactly `schemaVersion,frameType,subjectRef,state`.
`subjectRef` is opaque and is not an authorisation credential.

Server frames never contain `lastSeenAt`, `lastActiveAt`, `offlineAt`,
`deviceRef`, `sessionRef`, `connectionRef`, `instanceRef`, `ipAddress` or a
lease expiry. Unknown major versions are rejected and measured without
echoing target-specific information.

## Ephemeral lifecycle

Presence frames are best-effort and have no replay contract. Reconnect
reauthorises the relationship and recomputes only the current visible state.
Presence is excluded from message history, receipt history, PostgreSQL
migrations and durable outbox records. Redis persistence is not required.

CONNECT.17 may implement TTL leases within this contract. Lease expiry and
refresh must not create a durable activity timeline.

## Security and non-disclosure

An unauthorised actor learns zero target-specific presence information.
Unknown subject, blocked subject and no active relationship are externally
indistinguishable. Logs and metrics use reason classes and counters without
subject, device, session or connection identifiers.

## Phase boundaries

- CONNECT.16 freezes states, relationship policy, privacy and frame v1.
- CONNECT.17 implements bounded Redis presence leases.
- CONNECT.18 implements bounded conversation-scoped typing signals.
- CONNECT.19 aggregates multi-device presence without topology disclosure.
- CONNECT.20 proves ephemeral-signal resilience under failure injection.

Nexo Core is not read or mutated. Direct access to the Nexo database remains
forbidden.
