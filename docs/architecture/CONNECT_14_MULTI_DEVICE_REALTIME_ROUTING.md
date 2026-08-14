# CONNECT.14 multi-device realtime routing

## Outcome

Connect Lab routes one authorised conversation event to every eligible socket
owned by the same subject, including sockets attached to different application
instances. Sticky sessions are not required: Redis carries only the versioned
notification and each destination instance resolves its own local routes.

## Ephemeral connection registry

Every accepted WebSocket receives three independent, server-generated opaque
references: connection, device and session. Each contains 192 bits of secure
random entropy and is returned only in `AUTH_OK`. Clients cannot supply these
references or use them as an authorisation credential or routing target.

The local registry is bounded to 10,000 entries. A live route refreshes every
30 seconds and expires after 90 seconds. Normal close removes it immediately;
TTL is the fallback for an interrupted lifecycle. A supplied registration must
match the complete connection/device/session tuple, so guessing one identifier
cannot touch, remove or exclude another socket.

## Delivery and receipts

Message notifications reach every locally subscribed device that still passes
conversation authorisation. Receipt advancement excludes only the exact
originating socket while propagating to the subject's other devices and the
other authorised participant devices. Remote delivery repeats authorisation
and durable payload loading from PostgreSQL.

The registry is live state only. It contains no token, message body, receipt
authority or durable cursor. Redis loss can degrade live delivery but cannot
change committed history; authorised PostgreSQL catch-up remains the repair
path.
