#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ADR_FILE="${PROJECT_DIR}/docs/architecture/ADR-001_CONNECT_MULTI_INSTANCE_EPHEMERAL_REDIS_FANOUT.md"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-multi-instance-fanout-contract.properties"
OWNERSHIP_FILE="${PROJECT_DIR}/docs/governance/connect-ownership.properties"
REDIS_URI_PATTERN='redis:'
REDIS_URI_PATTERN="${REDIS_URI_PATTERN}/"
REDIS_URI_PATTERN="${REDIS_URI_PATTERN}/"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-11-architecture.XXXXXX")"
DURABLE_MUTATION="${TEMP_DIR}/durable-truth.properties"
EXACTLY_ONCE_MUTATION="${TEMP_DIR}/exactly-once.properties"
LOSS_MUTATION="${TEMP_DIR}/redis-loss.properties"

fail() {
    printf 'MULTI_INSTANCE_FANOUT_ARCHITECTURE=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

unlink_if_present() {
    local target="$1"
    if [[ -e "$target" || -L "$target" ]]; then
        unlink "$target"
    fi
}

cleanup() {
    local status=$?
    trap - EXIT
    unlink_if_present "$DURABLE_MUTATION"
    unlink_if_present "$EXACTLY_ONCE_MUTATION"
    unlink_if_present "$LOSS_MUTATION"
    rmdir "$TEMP_DIR" 2>/dev/null || status=1
    exit "$status"
}

trap cleanup EXIT

property_value() {
    local file="$1"
    local key="$2"
    awk -F= -v target="$key" '
        $1 == target {
            count++
            value = substr($0, index($0, "=") + 1)
        }
        END {
            if (count != 1) exit 2
            print value
        }
    ' "$file"
}

contract_equals() {
    local file="$1"
    local key="$2"
    local expected="$3"
    local actual
    actual="$(property_value "$file" "$key")" || return 1
    [[ "$actual" == "$expected" ]]
}

validate_contract() {
    local file="$1"
    local property_count

    [[ -f "$file" && ! -L "$file" ]] || return 1
    property_count="$(awk 'NF && $0 !~ /^#/ { count++ } END { print count + 0 }' "$file")"
    [[ "$property_count" == "43" ]] || return 1

    contract_equals "$file" contract.version 1 || return 1
    contract_equals "$file" programme NEXO_CONNECT_LAB || return 1
    contract_equals "$file" phase CONNECT.11 || return 1
    contract_equals "$file" status FROZEN || return 1
    contract_equals "$file" durable.truth POSTGRESQL || return 1
    contract_equals "$file" redis.role EPHEMERAL_LIVE_FANOUT_ONLY || return 1
    contract_equals "$file" redis.transport PUB_SUB || return 1
    contract_equals "$file" redis.persistence.required false || return 1
    contract_equals "$file" redis.loss.durable.impact 0 || return 1
    contract_equals "$file" live.delivery.guarantee BEST_EFFORT_EPHEMERAL || return 1
    contract_equals "$file" exactly.once.claim false || return 1
    contract_equals "$file" repair.authority POSTGRESQL_SEQUENCE_AND_AUTHORISED_CATCH_UP || return 1
    contract_equals "$file" publish.precondition POSTGRESQL_COMMIT_SUCCEEDED || return 1
    contract_equals "$file" publish.failure.changes.committed.outcome false || return 1
    contract_equals "$file" channel.major.version 1 || return 1
    contract_equals "$file" channel.namespace nexo.connect.realtime.v1 || return 1
    contract_equals "$file" channel.message.created nexo.connect.realtime.v1.message-created || return 1
    contract_equals "$file" channel.receipt.advanced nexo.connect.realtime.v1.receipt-advanced || return 1
    contract_equals "$file" channel.presence.reserved nexo.connect.realtime.v1.presence-changed || return 1
    contract_equals "$file" channel.typing.reserved nexo.connect.realtime.v1.typing-changed || return 1
    contract_equals "$file" envelope.required.fields \
        schemaVersion,eventId,eventType,occurredAt,conversationRef,aggregateSequence,originInstanceRef,payloadRef || return 1
    contract_equals "$file" envelope.forbidden.fields \
        messageBody,bearerToken,credential,accessToken,refreshToken,password,authorization || return 1
    contract_equals "$file" envelope.payload.mode OPAQUE_DURABLE_REFERENCE_ONLY || return 1
    contract_equals "$file" dedupe.key eventId || return 1
    contract_equals "$file" dedupe.scope INSTANCE_LOCAL_BOUNDED_TTL || return 1
    contract_equals "$file" dedupe.durable.authority false || return 1
    contract_equals "$file" ordering.scope PER_CONVERSATION || return 1
    contract_equals "$file" ordering.authority POSTGRESQL_CONVERSATION_SEQUENCE || return 1
    contract_equals "$file" receipt.ordering POSTGRESQL_MONOTONIC_HIGH_WATER_MARK || return 1
    contract_equals "$file" gap.behaviour AUTHORISED_CATCH_UP || return 1
    contract_equals "$file" redis.replay.required false || return 1
    contract_equals "$file" reconnect.behaviour RESUBSCRIBE_THEN_POSTGRESQL_CATCH_UP || return 1
    contract_equals "$file" sticky.sessions.required false || return 1
    contract_equals "$file" consumer.reauthorisation.required true || return 1
    contract_equals "$file" origin.exclusion.key originInstanceRef || return 1
    contract_equals "$file" unknown.major.behaviour REJECT_AND_MEASURE || return 1
    contract_equals "$file" message.body.in.redis forbidden || return 1
    contract_equals "$file" credentials.in.redis forbidden || return 1
    contract_equals "$file" nexo.db.direct.access 0 || return 1
    contract_equals "$file" nexo.mutation forbidden || return 1
    contract_equals "$file" implementation.first.phase CONNECT.12 || return 1
    contract_equals "$file" message.receipt.fanout.first.phase CONNECT.13 || return 1
    contract_equals "$file" redis.loss.proof.phase CONNECT.15 || return 1
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail "REPOSITORY_IDENTITY_MISMATCH"
[[ -f "$ADR_FILE" && ! -L "$ADR_FILE" ]] || fail "ADR_MISSING_OR_UNSAFE"
[[ -f "$OWNERSHIP_FILE" && ! -L "$OWNERSHIP_FILE" ]] || fail "OWNERSHIP_MISSING_OR_UNSAFE"
validate_contract "$CONTRACT_FILE" || fail "FROZEN_CONTRACT_MISMATCH"

for heading in \
    '## Decision' \
    '## Channel namespace and compatibility' \
    '## Minimal envelope' \
    '## Dedupe and ordering' \
    '## Publish and consume lifecycle' \
    '## Failure modes' \
    '## Security and isolation' \
    '## Observability' \
    '## Consequences' \
    '## Phase boundaries'; do
    grep -Fqx "$heading" "$ADR_FILE" || fail "ADR_SECTION_MISSING:${heading#\#\# }"
done

for statement in \
    'Exactly-once delivery is not claimed.' \
    'Redis is used only as an ephemeral signal bus between Connect application' \
    '`aggregateSequence` is the PostgreSQL conversation sequence.' \
    'consuming instance reauthorises each local subscription before delivery.' \
    'WebSocket clients resubscribe with their last durable sequence; PostgreSQL' \
    '- CONNECT.12 implements the isolated Redis client and lifecycle.' \
    '- CONNECT.13 implements message and receipt fan-out.' \
    '- CONNECT.15 proves Redis loss, flush, partition and rejoin recovery.'; do
    grep -Fq -- "$statement" "$ADR_FILE" || fail "ADR_DECISION_MISSING"
done

contract_equals "$OWNERSHIP_FILE" connect.postgres.durable_truth true ||
    fail "OWNERSHIP_POSTGRES_DURABLE_TRUTH_MISMATCH"
contract_equals "$OWNERSHIP_FILE" connect.redis.durable_truth false ||
    fail "OWNERSHIP_REDIS_DURABLE_TRUTH_MISMATCH"
contract_equals "$OWNERSHIP_FILE" connect.nexo_db_direct_access 0 ||
    fail "OWNERSHIP_NEXO_DB_BOUNDARY_MISMATCH"

if grep -REn "(Jedis|Lettuce|Redisson|RedisClient|RedisPubSub|${REDIS_URI_PATTERN})" \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend; then
    fail "REDIS_IMPLEMENTATION_ESCAPED_INFRASTRUCTURE_BOUNDARY"
fi
grep -Fq 'implementation(libs.lettuce.core)' build.gradle.kts ||
    fail "CONNECT_12_REDIS_CLIENT_DEPENDENCY_MISSING"
[[ -f src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisEphemeralConfig.kt ]] ||
    fail "CONNECT_12_REDIS_CONFIGURATION_MISSING"
[[ -f src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisEphemeralCircuit.kt ]] ||
    fail "CONNECT_12_REDIS_CIRCUIT_MISSING"
[[ -f src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisEphemeralLifecycle.kt ]] ||
    fail "CONNECT_12_REDIS_LIFECYCLE_MISSING"

awk '
    $0 == "durable.truth=POSTGRESQL" { print "durable.truth=REDIS"; next }
    { print }
' "$CONTRACT_FILE" > "$DURABLE_MUTATION"
if validate_contract "$DURABLE_MUTATION"; then
    fail "DURABLE_TRUTH_MUTATION_ACCEPTED"
fi

awk '
    $0 == "exactly.once.claim=false" { print "exactly.once.claim=true"; next }
    { print }
' "$CONTRACT_FILE" > "$EXACTLY_ONCE_MUTATION"
if validate_contract "$EXACTLY_ONCE_MUTATION"; then
    fail "EXACTLY_ONCE_MUTATION_ACCEPTED"
fi

awk '
    $0 == "redis.loss.durable.impact=0" { print "redis.loss.durable.impact=1"; next }
    { print }
' "$CONTRACT_FILE" > "$LOSS_MUTATION"
if validate_contract "$LOSS_MUTATION"; then
    fail "REDIS_LOSS_MUTATION_ACCEPTED"
fi

printf 'MULTI_INSTANCE_FANOUT_ADR=PASS\n'
printf 'POSTGRES_DURABLE_TRUTH=PASS\n'
printf 'REDIS_EPHEMERAL_ONLY=PASS\n'
printf 'CHANNEL_VERSIONING=PASS\n'
printf 'MINIMIZED_ENVELOPE=PASS\n'
printf 'CONSUMER_REAUTHORISATION=PASS\n'
printf 'DEDUPE_CONTRACT=PASS\n'
printf 'PER_CONVERSATION_ORDERING=PASS\n'
printf 'POSTGRES_CATCH_UP_REPAIR=PASS\n'
printf 'REDIS_LOSS_DURABLE_IMPACT=0\n'
printf 'EXACTLY_ONCE_CLAIM=FALSE\n'
printf 'ARCHITECTURE_MUTATION_REJECTED=PASS\n'
printf 'REDIS_IMPLEMENTATION_ISOLATED_IN_CONNECT_12=PASS\n'
printf 'MULTI_INSTANCE_FANOUT_ARCHITECTURE=PASS\n'
