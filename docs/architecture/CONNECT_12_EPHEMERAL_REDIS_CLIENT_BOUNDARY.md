# CONNECT.12 isolated ephemeral Redis client boundary

- Programme: `NEXO_CONNECT_LAB`
- Phase: `CONNECT.12`
- Durable source of truth: PostgreSQL
- Redis role: authenticated, ephemeral live-signal transport only

## Runtime boundary

The application authenticates as the dedicated Redis ACL identity
`nexo_connect_lab_app`. The default Redis identity is infrastructure-only and
is never passed to the application. Keys are restricted to
`nexo-connect-lab:*`; Pub/Sub channels are restricted to the frozen
`nexo.connect.realtime.v1.*` major namespace.

The client has bounded connect and command timeouts, rejects commands while
disconnected, caps the request queue and uses bounded exponential reconnect
delay. Its circuit reports `CLOSED`, `OPEN`, `HALF_OPEN` or `STOPPED`. No
credential is included in configuration rendering or lifecycle logs.

## Readiness and failure semantics

`GET /health/ready` remains the durable service readiness endpoint. It requires
typed configuration and PostgreSQL, and reports Redis state through headers
without making Redis an authority for committed chat state.

`GET /health/ready/ephemeral-redis` is the explicit ephemeral-boundary probe.
It returns `REDIS_READY` with HTTP 200 or `REDIS_DEGRADED` with HTTP 503. Redis
absence, restart or authentication failure opens only the Redis circuit; the
application and PostgreSQL durable readiness remain available.

The local Redis container has persistence disabled. Redis loss must produce
zero changes to the PostgreSQL durable-state hash. Message and receipt publish
and subscribe wiring remains locked to CONNECT.13.
