#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-typing-signals.properties"
DECISION_FILE="${PROJECT_DIR}/docs/architecture/CONNECT_18_BOUNDED_TYPING_SIGNALS.md"
PROTOCOL_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt"
ROUTE_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt"
STORE_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisTypingLeaseStore.kt"
SEMANTIC_GATE_FILE="${PROJECT_DIR}/scripts/verify-semantic-acceptance.sh"
MIGRATION_DIR="${PROJECT_DIR}/src/main/resources/db/migration"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-18-typing.XXXXXX")"
TTL_MUTATION="${TEMP_DIR}/ttl.properties"
RATE_MUTATION="${TEMP_DIR}/rate.properties"
HISTORY_MUTATION="${TEMP_DIR}/history.properties"

fail() {
    printf 'TYPING_SIGNAL_CONTRACT=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cleanup() {
    local status=$?
    trap - EXIT
    [[ ! -e "$TTL_MUTATION" ]] || unlink "$TTL_MUTATION"
    [[ ! -e "$RATE_MUTATION" ]] || unlink "$RATE_MUTATION"
    [[ ! -e "$HISTORY_MUTATION" ]] || unlink "$HISTORY_MUTATION"
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
    [[ -f "$file" && ! -L "$file" ]] || return 1
    [[ "$(awk 'NF && $0 !~ /^#/ { count++ } END { print count + 0 }' "$file")" == "51" ]] || return 1
    contract_equals "$file" contract.version 1 || return 1
    contract_equals "$file" programme NEXO_CONNECT_LAB || return 1
    contract_equals "$file" phase CONNECT.18 || return 1
    contract_equals "$file" status IMPLEMENTED || return 1
    contract_equals "$file" typing.role EPHEMERAL_AUTHORISED_SIGNAL_ONLY || return 1
    contract_equals "$file" typing.client.start TYPING_START || return 1
    contract_equals "$file" typing.client.stop TYPING_STOP || return 1
    contract_equals "$file" typing.client.refresh REPEATED_TYPING_START || return 1
    contract_equals "$file" typing.server.frame TYPING_STATE_CHANGED || return 1
    contract_equals "$file" typing.schema.version 1 || return 1
    contract_equals "$file" typing.unknown.schema REJECT || return 1
    contract_equals "$file" typing.lease.backend REDIS_TTL || return 1
    contract_equals "$file" typing.lease.namespace nexo-connect-lab:typing:v1 || return 1
    contract_equals "$file" typing.lease.key.raw.conversation false || return 1
    contract_equals "$file" typing.lease.key.raw.subject false || return 1
    contract_equals "$file" typing.lease.key.raw.device false || return 1
    contract_equals "$file" typing.lease.key.max.bytes 224 || return 1
    contract_equals "$file" typing.lease.ttl.seconds 6 || return 1
    contract_equals "$file" typing.refresh.recommended.seconds 2 || return 1
    contract_equals "$file" typing.rate.limit.signals 6 || return 1
    contract_equals "$file" typing.rate.limit.window.seconds 3 || return 1
    contract_equals "$file" typing.rate.limit.scope WEBSOCKET_CONNECTION || return 1
    contract_equals "$file" typing.sender.must.be.subscribed true || return 1
    contract_equals "$file" typing.sender.authorization FRESH_CONVERSATION_SCOPE || return 1
    contract_equals "$file" typing.recipient.authorization FRESH_BEFORE_DELIVERY || return 1
    contract_equals "$file" typing.cross.conversation.leak 0 || return 1
    contract_equals "$file" typing.transport.channel nexo.connect.realtime.v1.typing-state || return 1
    contract_equals "$file" typing.history.persistence false || return 1
    contract_equals "$file" typing.outbox.persistence false || return 1
    contract_equals "$file" typing.postgres.mutable.writes 0 || return 1
    contract_equals "$file" postgres.migration.count 5 || return 1
    contract_equals "$file" nexo.db.direct.access 0 || return 1
    contract_equals "$file" nexo.mutation forbidden || return 1
    contract_equals "$file" next.phase CONNECT.19 || return 1
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail REPOSITORY_IDENTITY_MISMATCH
[[ -f "$DECISION_FILE" && ! -L "$DECISION_FILE" ]] || fail DECISION_MISSING_OR_UNSAFE
[[ -f "$PROTOCOL_FILE" && -f "$ROUTE_FILE" && -f "$STORE_FILE" && -f "$SEMANTIC_GATE_FILE" ]] ||
    fail IMPLEMENTATION_MISSING
validate_contract "$CONTRACT_FILE" || fail TYPING_PROPERTIES_MISMATCH

if grep -Fq 'TYPING_START|TYPING_STOP' "$SEMANTIC_GATE_FILE"; then
    fail SEMANTIC_GATE_REJECTS_AUTHORISED_TYPING
fi

for heading in \
    '## Scope and authority' \
    '## Versioned client contract' \
    '## Start, refresh, stop and expiry' \
    '## Flood control' \
    '## Conversation authorization' \
    '## Multi-instance delivery' \
    '## Durable isolation and privacy' \
    '## Acceptance evidence' \
    '## Phase boundary'; do
    grep -Fqx "$heading" "$DECISION_FILE" || fail "DECISION_SECTION_MISSING:${heading#\#\# }"
done

for protocol_contract in \
    'const val TYPING_START = "TYPING_START"' \
    'const val TYPING_STOP = "TYPING_STOP"' \
    'const val TYPING_STATE_CHANGED = "TYPING_STATE_CHANGED"' \
    'const val TYPING_SCHEMA_VERSION = 1' \
    'INCOMPATIBLE_TYPING_SCHEMA_VERSION'; do
    grep -Fq "$protocol_contract" "$PROTOCOL_FILE" || fail VERSIONED_PROTOCOL_CONTRACT_MISSING
done

for route_contract in \
    'rateLimiter.tryAcquire()' \
    'TYPING_RATE_LIMITED' \
    'conversationRef !in subscribedConversationRefs' \
    'authorizeConversation(runtime, authenticated, conversationRef)' \
    'multiInstanceFanout.publishTyping' \
    'releaseTypingLeases(runtime, registration, typingHandles)'; do
    grep -Fq "$route_contract" "$ROUTE_FILE" || fail ROUTE_CONTRACT_MISSING
done

for store_contract in \
    'val DEFAULT_TYPING_TTL: Duration = Duration.ofSeconds(6)' \
    'current.setWithTtl' \
    'compareOwnerAndRefresh' \
    'compareOwnerAndDelete' \
    'const val MAX_KEY_BYTES = 224'; do
    grep -Fq "$store_contract" "$STORE_FILE" || fail REDIS_TYPING_LEASE_CONTRACT_MISSING
done

grep -Fq 'RedisTypingLeaseLifecycleKt.configureRedisTypingLeaseLifecycle' \
    src/main/resources/application.yaml || fail TYPING_LIFECYCLE_NOT_INSTALLED
grep -Fq 'TYPING_STATE_CHANGED' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/MultiInstanceRealtimeFanout.kt ||
    fail TYPING_MULTI_INSTANCE_FANOUT_MISSING
grep -Fq 'nexo.connect.realtime.v1.typing-state' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisRealtimeFanoutLifecycle.kt ||
    fail TYPING_CHANNEL_MISSING

migration_count="$(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' -print | wc -l | tr -d '[:space:]')"
[[ "$migration_count" =~ ^[0-9]+$ && "$migration_count" -ge 5 ]] || fail POSTGRES_MIGRATION_BASELINE_MISSING
if grep -REin '(^|[^[:alnum:]_])typing([^[:alnum:]_]|$)' "$MIGRATION_DIR"; then
    fail TYPING_DURABLE_MIGRATION_DETECTED
fi
if grep -REin '(Typing)(Repository|Record|Entity)' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/persistence; then
    fail TYPING_DURABLE_REPOSITORY_DETECTED
fi

awk '$0 == "typing.lease.ttl.seconds=6" { print "typing.lease.ttl.seconds=0"; next } { print }' \
    "$CONTRACT_FILE" > "$TTL_MUTATION"
validate_contract "$TTL_MUTATION" && fail ZERO_TTL_MUTATION_ACCEPTED
awk '$0 == "typing.rate.limit.signals=6" { print "typing.rate.limit.signals=0"; next } { print }' \
    "$CONTRACT_FILE" > "$RATE_MUTATION"
validate_contract "$RATE_MUTATION" && fail DISABLED_RATE_LIMIT_MUTATION_ACCEPTED
awk '$0 == "typing.history.persistence=false" { print "typing.history.persistence=true"; next } { print }' \
    "$CONTRACT_FILE" > "$HISTORY_MUTATION"
validate_contract "$HISTORY_MUTATION" && fail DURABLE_TYPING_MUTATION_ACCEPTED

./gradlew --no-daemon typingSignalTest --rerun-tasks --console=plain

printf 'TYPING_START_REFRESH_EXPIRY=PASS\n'
printf 'TYPING_RATE_LIMIT=PASS\n'
printf 'TYPING_FRAME_VERSIONED=PASS\n'
printf 'TYPING_CONVERSATION_AUTHORIZATION=PASS\n'
printf 'TYPING_HISTORY_WRITES=0\n'
printf 'TYPING_CROSS_CONVERSATION_LEAK=0\n'
printf 'TYPING_MUTATIONS_REJECTED=PASS\n'
printf 'TYPING_SEMANTIC_ACCEPTANCE_COMPATIBLE=PASS\n'
printf 'NEXO_DB_DIRECT_ACCESS=0\n'
printf 'TYPING_SIGNAL_CONTRACT=PASS\n'
