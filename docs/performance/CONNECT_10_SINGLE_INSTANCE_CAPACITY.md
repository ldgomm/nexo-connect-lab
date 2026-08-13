# CONNECT.10 single-instance capacity baseline

## Purpose

This phase measures a reproducible, bounded local envelope before distributed
fan-out is designed. It is evidence about one isolated Connect Lab instance,
not a production capacity promise or an autoscaling target.

## Harness

`scripts/verify-realtime-capacity-baseline.sh` starts from the already healthy
CI stack and runs the dedicated Gradle runtime task. The harness measures:

- concurrent authenticated websocket connection readiness at baseline and
  pressure tiers;
- commit-to-live delivery percentiles for durable text messages;
- durable catch-up percentiles and exact ascending sequence recovery;
- a controlled slow-reader mix and the first deliberately degraded tier;
- harness JVM memory plus sampled application-container CPU and memory;
- PostgreSQL message, identity and last-sequence oracles.

The committed workload is intentionally bounded: 4 baseline connections, 16
pressure connections, 12 durable messages and a 25 percent slow-reader mix.
The pressure tier injects a 750 ms client read delay. This creates a measurable
degradation point without pretending that the tier is the maximum supported
production load.

## Report contract

Each execution writes
`build/reports/realtime-capacity/connect-10-single-instance.properties`. The
report contains workload parameters, p50/p95/p99 latency measurements, resource
samples, the measured degradation tier and durable-loss count. It contains no
tokens, message bodies, actor identifiers or machine-specific filesystem paths.

PASS requires every connection to become ready, all 12 committed messages to
arrive live and through catch-up in exact order, PostgreSQL to retain exactly 12
messages and identities, durable loss to equal zero, and the controlled
pressure tier to be reported as degraded. The generated report is runtime
evidence and remains outside version control.
