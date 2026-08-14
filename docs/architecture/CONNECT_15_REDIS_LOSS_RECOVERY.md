# CONNECT.15 Redis loss recovery

Redis is an ephemeral notification accelerator, never durable chat truth. This
phase proves that a flush, a stopped Redis process and a non-responsive Redis
connection may interrupt live fan-out without invalidating an acknowledged
message or receipt.

The automated failure gate exercises four boundaries:

1. `FLUSHDB` while the stack is healthy;
2. a bounded non-responsive interval produced with `docker pause`;
3. a complete Redis stop and start;
4. rejoin followed by multi-instance message and receipt fan-out.

Throughout every injection, `/health/ready` remains durable-ready while the
explicit Redis probe reports degradation. PostgreSQL is hashed before and
after each failure boundary. The application container is not restarted.

Missed live notifications are repaired from authorised PostgreSQL history and
durable receipt cursors. The deterministic application contract persists each
event before attempting fan-out, repairs both loss windows, resumes live
delivery after rejoin and rejects duplicate durable delivery.

This phase does not claim exactly-once delivery. It proves at-least-once live
notification plus idempotent durable truth and authorised catch-up.
