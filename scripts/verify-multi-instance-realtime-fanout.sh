#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-realtime-fanout.properties"
TRANSPORT_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisRealtimeFanoutLifecycle.kt"
ENVELOPE_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeFanoutEnvelope.kt"
FANOUT_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/MultiInstanceRealtimeFanout.kt"
HUB_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/AuthorizedConversationEventHub.kt"
LOADER_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/AuthorisedDurableFanoutPayloadLoader.kt"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-13-fanout.XXXXXX")"
MUTATION_FILE="${TEMP_DIR}/mutation.properties"

fail() {
    printf 'MULTI_INSTANCE_REALTIME_FANOUT=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cleanup() {
    local status=$?
    trap - EXIT
    if [[ -e "$MUTATION_FILE" || -L "$MUTATION_FILE" ]]; then
        unlink "$MUTATION_FILE"
    fi
    rmdir "$TEMP_DIR" 2>/dev/null || status=1
    exit "$status"
}

trap cleanup EXIT

property_value() {
    local file="$1"
    local key="$2"
    awk -F= -v target="$key" '
        $1 == target { count++; value = substr($0, index($0, "=") + 1) }
        END { if (count != 1) exit 2; print value }
    ' "$file"
}

require_property() {
    local file="$1"
    local key="$2"
    local expected="$3"
    local actual
    actual="$(property_value "$file" "$key")" || return 1
    [[ "$actual" == "$expected" ]]
}

validate_contract() {
    local file="$1"
    [[ -f "$file" && ! -L "$file" ]] || return 1
    [[ "$(awk 'NF && $0 !~ /^#/ { count++ } END { print count + 0 }' "$file")" == "35" ]] || return 1
    require_property "$file" contract.version 1 || return 1
    require_property "$file" programme NEXO_CONNECT_LAB || return 1
    require_property "$file" phase CONNECT.13 || return 1
    require_property "$file" status IMPLEMENTED || return 1
    require_property "$file" durable.truth POSTGRESQL || return 1
    require_property "$file" transport REDIS_PUB_SUB || return 1
    require_property "$file" delivery.guarantee BEST_EFFORT_EPHEMERAL || return 1
    require_property "$file" exactly.once.claim false || return 1
    require_property "$file" publish.precondition POSTGRESQL_COMMIT_SUCCEEDED || return 1
    require_property "$file" publish.failure.changes.committed.outcome false || return 1
    require_property "$file" channel.schema.version 1 || return 1
    require_property "$file" channel.message.created nexo.connect.realtime.v1.message-created || return 1
    require_property "$file" channel.receipt.advanced nexo.connect.realtime.v1.receipt-advanced || return 1
    require_property "$file" envelope.payload.mode OPAQUE_DURABLE_REFERENCE_ONLY || return 1
    require_property "$file" envelope.max.bytes 2048 || return 1
    require_property "$file" message.body.in.redis false || return 1
    require_property "$file" credentials.in.redis false || return 1
    require_property "$file" origin.exclusion true || return 1
    require_property "$file" origin.exclusion.key originInstanceRef || return 1
    require_property "$file" dedupe.enabled true || return 1
    require_property "$file" dedupe.key eventId || return 1
    require_property "$file" dedupe.scope INSTANCE_LOCAL_BOUNDED_TTL || return 1
    require_property "$file" dedupe.capacity 10000 || return 1
    require_property "$file" dedupe.ttl.seconds 300 || return 1
    require_property "$file" consumer.reauthorisation true || return 1
    require_property "$file" durable.payload.reload POSTGRESQL_PER_AUTHORISED_PRINCIPAL || return 1
    require_property "$file" ordering.authority POSTGRESQL_CONVERSATION_SEQUENCE || return 1
    require_property "$file" receipt.authority POSTGRESQL_MONOTONIC_HIGH_WATER_MARK || return 1
    require_property "$file" repair.authority AUTHORISED_POSTGRESQL_CATCH_UP || return 1
    require_property "$file" sticky.sessions.required false || return 1
    require_property "$file" durable.duplicates 0 || return 1
    require_property "$file" cross.conversation.leak 0 || return 1
    require_property "$file" nexo.db.direct.access 0 || return 1
    require_property "$file" nexo.mutation forbidden || return 1
    require_property "$file" next.phase CONNECT.14 || return 1
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail REPOSITORY_IDENTITY_MISMATCH
for file in "$CONTRACT_FILE" "$TRANSPORT_FILE" "$ENVELOPE_FILE" "$FANOUT_FILE" "$HUB_FILE" "$LOADER_FILE"; do
    [[ -f "$file" && ! -L "$file" ]] || fail REQUIRED_FANOUT_FILE_MISSING_OR_UNSAFE
done
validate_contract "$CONTRACT_FILE" || fail FANOUT_CONTRACT_MISMATCH

grep -Fq 'StatefulRedisPubSubConnection' "$TRANSPORT_FILE" || fail REDIS_SUBSCRIBER_MISSING
grep -Fq 'connection.sync().publish' "$TRANSPORT_FILE" || fail REDIS_PUBLISHER_MISSING
grep -Fq 'originInstanceRef == activeTransport.localInstanceRef' "$FANOUT_FILE" || fail ORIGIN_EXCLUSION_MISSING
grep -Fq 'BoundedRealtimeFanoutDedupe' "$FANOUT_FILE" || fail BOUNDED_DEDUPE_MISSING
grep -Fq 'authorizer.authorize' "$HUB_FILE" || fail DESTINATION_REAUTHORISATION_MISSING
grep -Fq 'PostgresAuthorisedDurableFanoutPayloadLoader' "$LOADER_FILE" || fail DURABLE_PAYLOAD_RELOAD_MISSING
grep -Fq 'MAX_REALTIME_FANOUT_ENVELOPE_BYTES = 2_048' "$ENVELOPE_FILE" || fail ENVELOPE_BOUND_MISSING

if grep -Eiq '(messageBody|bearerToken|credential|accessToken|refreshToken|password|authorization)' "$ENVELOPE_FILE" ||
    grep -REn '(Jedis|Lettuce|RedisClient|RedisPubSub|redis:/[/])' \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend; then
    fail PAYLOAD_OR_IMPLEMENTATION_BOUNDARY_VIOLATION
fi

awk '$0 == "durable.truth=POSTGRESQL" { print "durable.truth=REDIS"; next } { print }' \
    "$CONTRACT_FILE" > "$MUTATION_FILE"
if validate_contract "$MUTATION_FILE"; then
    fail REDIS_DURABLE_TRUTH_MUTATION_ACCEPTED
fi
awk '$0 == "cross.conversation.leak=0" { print "cross.conversation.leak=1"; next } { print }' \
    "$CONTRACT_FILE" > "$MUTATION_FILE"
if validate_contract "$MUTATION_FILE"; then
    fail CROSS_CONVERSATION_MUTATION_ACCEPTED
fi

./gradlew --no-daemon test \
    --tests 'com.premierdarkcoffee.nexo.connect.lab.domain.realtime.RealtimeFanoutEnvelopeTest' \
    --tests 'com.premierdarkcoffee.nexo.connect.lab.application.realtime.MultiInstanceRealtimeFanoutTest' \
    --tests 'com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.RedisRealtimeFanoutConfigTest' \
    --rerun-tasks --console=plain

printf 'VERSIONED_PUBLISH_CONSUME=PASS\n'
printf 'MINIMIZED_DURABLE_REFERENCE_ENVELOPE=PASS\n'
printf 'ORIGIN_INSTANCE_EXCLUSION=PASS\n'
printf 'BOUNDED_INSTANCE_DEDUPE=PASS\n'
printf 'DESTINATION_REAUTHORISATION=PASS\n'
printf 'POSTGRES_DURABLE_PAYLOAD_RELOAD=PASS\n'
printf 'CROSS_CONVERSATION_LEAK=0\n'
printf 'DURABLE_DUPLICATES=0\n'
printf 'EXACTLY_ONCE_CLAIM=FALSE\n'
printf 'MULTI_INSTANCE_REALTIME_FANOUT=PASS\n'
