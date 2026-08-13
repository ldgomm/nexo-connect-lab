#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"

cd "$PROJECT_DIR"

./gradlew --no-daemon test \
    --tests 'com.premierdarkcoffee.nexo.connect.lab.backend.realtime.RealtimeTransportHardeningTest' \
    --rerun-tasks --console=plain

printf 'REALTIME_OUTBOUND_QUEUE_BOUNDED=PASS\n'
printf 'REALTIME_OUTBOUND_SERIALIZATION=PASS\n'
printf 'REALTIME_SLOW_CONSUMER_POLICY=PASS\n'
printf 'REALTIME_CONNECTION_LIMIT=PASS\n'
printf 'REALTIME_CONNECTION_CLEANUP=PASS\n'
printf 'REALTIME_HEARTBEAT_IDLE_TIMEOUT=PASS\n'
printf 'REALTIME_PROTOCOL_ERROR_POLICY=PASS\n'
printf 'REALTIME_LIVE_FAN_OUT_SCOPE=SINGLE_APPLICATION_INSTANCE\n'
printf 'REALTIME_REDIS_DURABLE_USAGE=0\n'
printf 'REALTIME_TRANSPORT_HARDENING_CONTRACT=PASS\n'
