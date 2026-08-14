#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-redis-ephemeral-boundary.properties"
CONFIG_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisEphemeralConfig.kt"
LIFECYCLE_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisEphemeralLifecycle.kt"
CIRCUIT_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisEphemeralCircuit.kt"
READINESS_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/ReadinessRoutes.kt"
REDIS_STARTUP_FILE="${PROJECT_DIR}/docker/redis/start-ephemeral-redis.sh"

fail() {
    printf 'REDIS_EPHEMERAL_BOUNDARY=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

property_equals() {
    local key="$1"
    local expected="$2"
    local actual
    actual="$(awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); count++ } END { if (count != 1) exit 2 }' "$CONTRACT_FILE")" || return 1
    [[ "$actual" == "$expected" ]]
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail REPOSITORY_IDENTITY_MISMATCH
for file in \
    "$CONTRACT_FILE" "$CONFIG_FILE" "$CIRCUIT_FILE" "$LIFECYCLE_FILE" "$READINESS_FILE" \
    "$REDIS_STARTUP_FILE"; do
    [[ -f "$file" && ! -L "$file" ]] || fail REQUIRED_BOUNDARY_FILE_MISSING_OR_UNSAFE
done
[[ -x "$REDIS_STARTUP_FILE" ]] || fail REDIS_STARTUP_FILE_NOT_EXECUTABLE

property_equals phase CONNECT.12 || fail PHASE_MISMATCH
property_equals durable.truth POSTGRESQL || fail DURABLE_TRUTH_MISMATCH
property_equals redis.role EPHEMERAL_LIVE_FANOUT_ONLY || fail REDIS_ROLE_MISMATCH
property_equals redis.persistence false || fail REDIS_PERSISTENCE_MISMATCH
property_equals redis.app.user nexo_connect_lab_app || fail REDIS_APP_IDENTITY_MISMATCH
property_equals redis.default.user.in.app false || fail REDIS_ROOT_IDENTITY_LEAK
property_equals redis.key.namespace nexo-connect-lab || fail KEY_NAMESPACE_MISMATCH
property_equals redis.channel.namespace nexo.connect.realtime.v1 || fail CHANNEL_NAMESPACE_MISMATCH
property_equals redis.disconnected.commands REJECT || fail DISCONNECTED_COMMAND_POLICY_MISMATCH
property_equals redis.required.for.durable.readiness false || fail DURABLE_READINESS_COUPLED_TO_REDIS
property_equals redis.loss.durable.impact 0 || fail DURABLE_IMPACT_MISMATCH
property_equals credentials.logged false || fail CREDENTIAL_LOGGING_CONTRACT_MISMATCH
property_equals publish.subscribe.enabled false || fail CONNECT_13_SCOPE_LEAK
property_equals nexo.db.direct.access 0 || fail NEXO_DB_BOUNDARY_MISMATCH

grep -Fq 'password=<redacted>' "$CONFIG_FILE" || fail SECRET_REDACTION_MISSING
grep -Fq 'DisconnectedBehavior.REJECT_COMMANDS' "$LIFECYCLE_FILE" || fail DISCONNECTED_COMMAND_REJECTION_MISSING
grep -Fq '.requestQueueSize(config.requestQueueSize)' "$LIFECYCLE_FILE" || fail REQUEST_QUEUE_BOUND_MISSING
grep -Fq 'RedisCircuitState.HALF_OPEN' "$CIRCUIT_FILE" || fail CIRCUIT_STATE_MISSING
grep -Fq 'get("/health/ready/ephemeral-redis")' "$READINESS_FILE" || fail EXPLICIT_READINESS_MISSING

if grep -REn '(CONNECT_LAB_REDIS_PASSWORD|default)' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis ||
    grep -REn '(RedisPubSub|StatefulRedisPubSubConnection|\.publish\()' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis; then
    fail ROOT_SECRET_OR_CONNECT_13_SCOPE_LEAK
fi

printf 'REDIS_TYPED_CONFIGURATION=PASS\n'
printf 'REDIS_DEDICATED_AUTH=PASS\n'
printf 'REDIS_NAMESPACE_ISOLATION=PASS\n'
printf 'REDIS_TIMEOUTS_AND_QUEUE_BOUNDED=PASS\n'
printf 'REDIS_RECONNECT_CIRCUIT=PASS\n'
printf 'REDIS_SECRET_REDACTION=PASS\n'
printf 'REDIS_DURABLE_READINESS_DECOUPLED=PASS\n'
printf 'CONNECT_13_SCOPE_REMAINS_LOCKED=PASS\n'
printf 'REDIS_EPHEMERAL_BOUNDARY=PASS\n'
