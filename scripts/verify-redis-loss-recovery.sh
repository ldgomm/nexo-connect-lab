#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-redis-loss-recovery.properties"
DOCUMENT_FILE="${PROJECT_DIR}/docs/architecture/CONNECT_15_REDIS_LOSS_RECOVERY.md"
TEST_FILE="${PROJECT_DIR}/src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisLossRecoveryContractTest.kt"
FANOUT_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/MultiInstanceRealtimeFanout.kt"
REDIS_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisRealtimeFanoutLifecycle.kt"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-15-contract.XXXXXX")"
MUTATION_FILE="${TEMP_DIR}/mutation.properties"

fail() {
    printf 'REDIS_LOSS_RECOVERY=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cleanup() {
    local status=$?
    trap - EXIT
    [[ ! -e "$MUTATION_FILE" && ! -L "$MUTATION_FILE" ]] || unlink "$MUTATION_FILE"
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
    [[ "$(awk 'NF && $0 !~ /^#/ { count++ } END { print count + 0 }' "$file")" == "36" ]] || return 1
    require_property "$file" contract.version 1 || return 1
    require_property "$file" programme NEXO_CONNECT_LAB || return 1
    require_property "$file" phase CONNECT.15 || return 1
    require_property "$file" status IMPLEMENTED || return 1
    require_property "$file" redis.role EPHEMERAL_LIVE_FANOUT_ONLY || return 1
    require_property "$file" durable.truth POSTGRESQL || return 1
    require_property "$file" failure.injection.flushdb true || return 1
    require_property "$file" failure.injection.partition DOCKER_PAUSE || return 1
    require_property "$file" failure.injection.stop true || return 1
    require_property "$file" failure.injection.rejoin true || return 1
    require_property "$file" degraded.state.explicit true || return 1
    require_property "$file" durable.readiness.during.redis.loss true || return 1
    require_property "$file" application.restart.during.redis.loss 0 || return 1
    require_property "$file" postgres.hash.before.after.required true || return 1
    require_property "$file" fanout.publish.failure.nonfatal true || return 1
    require_property "$file" message.persist.before.fanout true || return 1
    require_property "$file" receipt.persist.before.fanout true || return 1
    require_property "$file" message.repair AUTHORISED_POSTGRES_SEQUENCE_CATCH_UP || return 1
    require_property "$file" receipt.repair AUTHORISED_DURABLE_CURSOR_RELOAD || return 1
    require_property "$file" rejoin.live.fanout.required true || return 1
    require_property "$file" multi.device.rejoin.required true || return 1
    require_property "$file" cross.instance.rejoin.required true || return 1
    require_property "$file" flush.affects.durable.state false || return 1
    require_property "$file" partition.affects.durable.state false || return 1
    require_property "$file" stop.affects.durable.state false || return 1
    require_property "$file" redis.persistence.required false || return 1
    require_property "$file" acknowledged.message.loss 0 || return 1
    require_property "$file" acknowledged.receipt.loss 0 || return 1
    require_property "$file" durable.message.duplicates 0 || return 1
    require_property "$file" durable.receipt.regressions 0 || return 1
    require_property "$file" cross.conversation.leak 0 || return 1
    require_property "$file" redis.loss.durable.impact 0 || return 1
    require_property "$file" exactly.once.claim false || return 1
    require_property "$file" nexo.db.direct.access 0 || return 1
    require_property "$file" nexo.mutation forbidden || return 1
    require_property "$file" next.phase CONNECT.16 || return 1
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail REPOSITORY_IDENTITY_MISMATCH
for file in "$CONTRACT_FILE" "$DOCUMENT_FILE" "$TEST_FILE" "$FANOUT_FILE" "$REDIS_FILE"; do
    [[ -f "$file" && ! -L "$file" ]] || fail REQUIRED_RECOVERY_FILE_MISSING_OR_UNSAFE
done
validate_contract "$CONTRACT_FILE" || fail REDIS_LOSS_RECOVERY_CONTRACT_MISMATCH

grep -Fq 'FailureMode.FLUSHED' "$TEST_FILE" || fail FLUSH_INJECTION_MODEL_MISSING
grep -Fq 'FailureMode.LOST' "$TEST_FILE" || fail LOSS_INJECTION_MODEL_MISSING
grep -Fq 'FailureMode.PARTITIONED' "$TEST_FILE" || fail PARTITION_INJECTION_MODEL_MISSING
grep -Fq 'DurableConversationCatchUp(store.historyRepository())' "$TEST_FILE" ||
    fail DURABLE_MESSAGE_REPAIR_MISSING
grep -Fq 'DurableReceiptCursorService(store.receiptRepository(), null)' "$TEST_FILE" ||
    fail DURABLE_RECEIPT_REPAIR_MISSING
grep -Fq 'messages.putIfAbsent' "$TEST_FILE" || fail DURABLE_DUPLICATE_GUARD_MISSING
grep -Fq 'PostgreSQL already committed; authorised catch-up repairs missed live fan-out.' "$FANOUT_FILE" ||
    fail NONFATAL_FANOUT_FAILURE_BOUNDARY_MISSING
grep -Fq '.autoReconnect(true)' "$REDIS_FILE" || fail REDIS_AUTORECONNECT_MISSING
grep -Fq 'DisconnectedBehavior.REJECT_COMMANDS' "$REDIS_FILE" || fail DISCONNECTED_COMMAND_REJECTION_MISSING

awk '$0 == "acknowledged.message.loss=0" { print "acknowledged.message.loss=1"; next } { print }' \
    "$CONTRACT_FILE" > "$MUTATION_FILE"
if validate_contract "$MUTATION_FILE"; then
    fail ACKNOWLEDGED_LOSS_MUTATION_ACCEPTED
fi

./gradlew --no-daemon redisLossRecoveryTest --rerun-tasks --console=plain

printf 'REDIS_FAILURE_INJECTION_MODEL=PASS\n'
printf 'DURABLE_CATCH_UP_REPAIR=PASS\n'
printf 'DURABLE_RECEIPT_REPAIR=PASS\n'
printf 'ACKNOWLEDGED_MESSAGE_LOSS=0\n'
printf 'ACKNOWLEDGED_RECEIPT_LOSS=0\n'
printf 'DURABLE_DUPLICATES=0\n'
printf 'DURABLE_RECEIPT_REGRESSIONS=0\n'
printf 'CROSS_CONVERSATION_LEAK=0\n'
printf 'REDIS_LOSS_DURABLE_IMPACT=0\n'
printf 'REDIS_LOSS_RECOVERY=PASS\n'
