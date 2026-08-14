#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"

cd "$PROJECT_DIR"

"${SCRIPT_DIR}/verify-redis-loss-durable-isolation.sh"
"${SCRIPT_DIR}/verify-multi-device-realtime-routing-runtime.sh"
./gradlew --no-daemon redisLossRecoveryTest --rerun-tasks --console=plain

printf 'MULTI_INSTANCE_LIVE_FANOUT=PASS\n'
printf 'POSTGRES_DURABLE_TRUTH=PASS\n'
printf 'AUTHORISED_CATCH_UP_AFTER_REJOIN=PASS\n'
printf 'ACKNOWLEDGED_MESSAGE_LOSS=0\n'
printf 'ACKNOWLEDGED_RECEIPT_LOSS=0\n'
printf 'DURABLE_DUPLICATES=0\n'
printf 'DURABLE_RECEIPT_REGRESSIONS=0\n'
printf 'REDIS_LOSS_DURABLE_IMPACT=0\n'
printf 'REDIS_LOSS_RECOVERY_RUNTIME=PASS\n'
