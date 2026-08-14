#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"

fail() {
    printf 'SEMANTIC_ACCEPTANCE=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cd "$PROJECT_DIR"

for required_command in git grep; do
    command -v "$required_command" >/dev/null 2>&1 || fail "REQUIRED_COMMAND_MISSING:${required_command}"
done

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail "REPOSITORY_IDENTITY_MISMATCH"
[[ -x ./gradlew && ! -L ./gradlew ]] || fail "GRADLE_WRAPPER_MISSING_OR_UNSAFE"

required_files=(
    docs/governance/SEMANTIC_ACCEPTANCE_GATES.md
    scripts/verify-postgres-repository.sh
    scripts/verify-postgres-schema.sh
    src/main/resources/db/migration/V1__connect_durable_text_schema.sql
    src/main/resources/db/migration/V2__connect_application_role_grants.sql
    src/main/resources/db/migration/V3__business_client_conversation_keys.sql
    src/main/resources/db/migration/V4__durable_conversation_activity_listing.sql
    src/main/resources/db/migration/V5__durable_receipt_cursors.sql
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/acceptance/SemanticAcceptanceGateTest.kt
)

for required_file in "${required_files[@]}"; do
    [[ -f "$required_file" && ! -L "$required_file" ]] ||
        fail "SEMANTIC_GATE_FILE_MISSING_OR_UNSAFE:${required_file}"
done

# Negative scans are defence in depth only. Positive acceptance is determined
# by compiled tests and the PostgreSQL/runtime probes executed by ci-verify.sh.
if grep -REn 'route\(|webSocket|WebSocket|/messages|/conversations' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/conversation \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/message; then
    fail "TRANSPORT_LEAKED_INTO_DOMAIN_OR_PERSISTENCE"
fi

if grep -REn 'OFFSET|INSERT[[:space:]]|UPDATE[[:space:]]|DELETE[[:space:]]|TRUNCATE[[:space:]]' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/DurableMessageHistoryRepository.kt \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/message/DurableMessageHistory.kt \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableMessageHistoryRepository.kt; then
    fail "READ_MODEL_CONTAINS_FORBIDDEN_MUTATION_OR_OFFSET"
fi

if grep -REn '(Jedis|Lettuce|RedisClient|redis:/[/]|PRESENCE_CHANGED)' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/realtime \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime; then
    fail "UNAUTHORISED_REALTIME_SCOPE_PRESENT"
fi

if grep -En '(token|bearerToken|credential)[[:space:]]*:' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt; then
    fail "SECRET_FIELD_PRESENT_IN_PROTOCOL_MODEL"
fi

./gradlew --no-daemon semanticAcceptanceTest --rerun-tasks --console=plain

printf 'SEMANTIC_ACCEPTANCE=PASS\n'
printf 'REFORMATTED_FIXTURE=PASS\n'
printf 'SEMANTIC_MUTATION_REJECTED=PASS\n'
printf 'MIGRATION_PRIMARY_GATE=POSTGRES_EXECUTION\n'
printf 'PROTOCOL_PRIMARY_GATE=BEHAVIOURAL_TESTS\n'
printf 'SECURITY_PRIMARY_GATE=BEHAVIOURAL_TESTS\n'
printf 'RUNTIME_PRIMARY_GATE=BEHAVIOURAL_TESTS\n'
printf 'SOURCE_SCAN_ROLE=SECONDARY_DEFENCE\n'
