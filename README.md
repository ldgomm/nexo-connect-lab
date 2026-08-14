# connect-lab

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

* [Ktor Documentation](https://ktor.io/docs/home.html)
* [Ktor GitHub page](https://github.com/ktorio/ktor)
* [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).

## Features

Here's a list of features included in this project:

| Name | Description |
|------|-------------|

## Building & Running

To build or run the project, use one of the following tasks:

| Task              | Description       |
|-------------------|-------------------|
| `./gradlew test`  | Run the tests     |
| `./gradlew build` | Build the project |
| `./gradlew run`   | Run the server    |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## Isolated local runtime

Generate local-only credentials once, then start the private Compose stack:

```bash
make env
make up
```

Durable service readiness and the optional Redis live-signal boundary are
reported separately:

```bash
curl -i http://127.0.0.1:8282/health/ready
curl -i http://127.0.0.1:8282/health/ready/ephemeral-redis
```

PostgreSQL is the durable authority. If Redis is unavailable, the first route
remains `READY` while its Redis header changes to `DEGRADED`; the second route
returns `REDIS_DEGRADED`. Redis is authenticated with a dedicated application
ACL, has persistence disabled and cannot access namespaces outside Connect Lab.

Committed messages and receipt cursors are fanned out between application
instances through the versioned Redis Pub/Sub channels. Notifications contain
only opaque durable references; each destination reauthorises the subscription
and reloads the payload from PostgreSQL. Missed live signals are repaired by
the existing authorised catch-up path.

Each WebSocket is also represented by a bounded TTL registry entry with
independent opaque connection, device and session references. The same subject
may hold several routes on one or many instances. Receipt updates exclude only
their exact origin route and continue to the subject's other devices.

The Redis recovery gate injects `FLUSHDB`, a bounded non-responsive partition,
a complete stop and a rejoin. Durable readiness remains available, PostgreSQL
history and receipt cursors remain unchanged, missed live signals are repaired
through authorised catch-up, and live multi-device fan-out resumes after
rejoin without acknowledged loss or durable duplicates.

Presence follows the frozen CONNECT.16 privacy contract. Only self and active
conversation participants may receive the coarse
`ONLINE|RECENTLY_ONLINE|OFFLINE|HIDDEN` projection. Unknown, blocked and
unrelated subjects produce no frame; exact last-seen and device topology are
never exposed. Presence remains ephemeral and outside durable history.

Authenticated WebSockets now acquire device-scoped presence leases in the
isolated Redis namespace. Heartbeats refresh a 45-second TTL every 15 seconds;
release is owner checked, reconnect rotates ownership, and a crash disappears
through native expiry. Keys contain only bounded digests, Redis loss never
blocks durable chat, and PostgreSQL receives zero mutable presence state.

Typing is a versioned, conversation-authorised six-second lease. Start and
refresh are rate limited per WebSocket, stop and disconnect clear owned leases,
and crashes expire through Redis TTL. Only freshly authorised subscribers
receive the bounded state; typing never enters PostgreSQL history and exposes
no device or instance topology.

Presence now aggregates all active device leases into one privacy-aware subject
state. Disconnecting one device leaves the subject online while another lease
remains; final disconnect enters only the coarse recent window. Redis relative
TTL makes the decision independent of application clock skew, and uniform
relationship/block/mute/visibility policy exposes neither topology nor denial
reason and never writes mutable presence to PostgreSQL.
