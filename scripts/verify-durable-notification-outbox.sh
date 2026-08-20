#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-durable-notification-outbox.properties"
DECISION_FILE="${PROJECT_DIR}/docs/architecture/CONNECT_22_DURABLE_NOTIFICATION_OUTBOX.md"
MIGRATION_FILE="${PROJECT_DIR}/src/main/resources/db/migration/V7__durable_notification_outbox.sql"
WRITE_CONTRACT_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/DurableTextWriteContract.kt"
MESSAGE_REPOSITORY_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresDurableTextRepository.kt"
OUTBOX_REPOSITORY_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresNotificationOutboxRepository.kt"
DOMAIN_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/push/NotificationOutboxIntent.kt"
UNIT_TEST="${PROJECT_DIR}/src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/push/NotificationOutboxIntentTest.kt"
INTEGRATION_TEST="${PROJECT_DIR}/src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresNotificationOutboxRepositoryIntegrationTest.kt"

fail() {
    printf 'DURABLE_NOTIFICATION_OUTBOX_CONTRACT=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

property_value() {
    local key="$1"
    awk -F= -v target="$key" '
        $1 == target { count++; value = substr($0, index($0, "=") + 1) }
        END { if (count != 1) exit 2; print value }
    ' "$CONTRACT_FILE"
}

require_property() {
    local key="$1"
    local expected="$2"
    local actual
    actual="$(property_value "$key")" || fail "PROPERTY_MISSING_OR_DUPLICATE:${key}"
    [[ "$actual" == "$expected" ]] || fail "PROPERTY_VALUE_MISMATCH:${key}"
}

cd "$PROJECT_DIR"
[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail REPOSITORY_IDENTITY_MISMATCH

for required_file in \
    "$CONTRACT_FILE" "$DECISION_FILE" "$MIGRATION_FILE" "$WRITE_CONTRACT_FILE" \
    "$MESSAGE_REPOSITORY_FILE" "$OUTBOX_REPOSITORY_FILE" "$DOMAIN_FILE" \
    "$UNIT_TEST" "$INTEGRATION_TEST"; do
    [[ -f "$required_file" && ! -L "$required_file" ]] || fail REQUIRED_FILE_MISSING_OR_UNSAFE
done

require_property contract.version 1
require_property programme NEXO_CONNECT_LAB
require_property phase CONNECT.22
require_property status IMPLEMENTED
require_property outbox.truth POSTGRESQL
require_property message.outbox.transaction ATOMIC
require_property intent.duplicate.count 0
require_property payload.message.body false
require_property payload.provider.token false
require_property payload.refs.only true
require_property delivery AT_LEAST_ONCE
require_property exactly.once.claim false
require_property claim.strategy FOR_UPDATE_SKIP_LOCKED
require_property lease.version.fencing REQUIRED
require_property retry.error CLOSED_GENERIC_TAXONOMY
require_property dead.letter REQUIRED
require_property provider.delivery DEFERRED_CONNECT_23
require_property nexo.db.direct.access 0
require_property next.phase CONNECT.23

for marker in \
    'INSERT_NOTIFICATION_OUTBOX_INTENTS' \
    'insertNotificationOutboxIntents' \
    'INSERT INTO connect.notification_outbox' \
    'notificationIntentRef' \
    "registration.status = 'ACTIVE'" \
    "participant.status = 'ACTIVE'"; do
    grep -Fq "$marker" "$WRITE_CONTRACT_FILE" "$MESSAGE_REPOSITORY_FILE" ||
        fail MESSAGE_OUTBOX_TRANSACTION_MARKER_MISSING
done

for marker in \
    'FOR UPDATE SKIP LOCKED' \
    "status = 'CLAIMED'" \
    'lease_owner = ?' \
    'lease_expires_at > ?' \
    "status = 'DEAD_LETTER'" \
    'attempt_count >= max_attempts'; do
    grep -Fq "$marker" "$OUTBOX_REPOSITORY_FILE" || fail OUTBOX_LEASE_RETRY_MARKER_MISSING
done

for marker in \
    'uq_connect_notification_message_target' \
    'fk_connect_notification_message' \
    'fk_connect_notification_registration' \
    'ix_connect_notification_claimable' \
    'ix_connect_notification_expired_lease' \
    'GRANT SELECT, INSERT, UPDATE ON connect.notification_outbox'; do
    grep -Fq "$marker" "$MIGRATION_FILE" || fail POSTGRES_OUTBOX_MARKER_MISSING
done

if grep -Ein '^[[:space:]]*(body|token|token_fingerprint|token_ciphertext|token_nonce|credentials)[[:space:]]+[^=]' "$MIGRATION_FILE"; then
    fail NON_MINIMISED_OUTBOX_COLUMN_DETECTED
fi

for test_marker in \
    'outbox insertion failure rolls back message identity and sequence' \
    'claim lease fences workers' \
    'expired final lease becomes an auditable dead letter' \
    'bounded retry becomes dead letter' \
    'notification_outbox'; do
    grep -Fq "$test_marker" "$INTEGRATION_TEST" || fail OUTBOX_INTEGRATION_TEST_MARKER_MISSING
done

./gradlew --no-daemon notificationOutboxTest --rerun-tasks --console=plain

printf 'MESSAGE_OUTBOX_ATOMICITY=PASS\n'
printf 'DUPLICATE_NOTIFICATION_INTENTS=0\n'
printf 'NOTIFICATION_PAYLOAD_MINIMISED=PASS\n'
printf 'CLAIM_LEASE_RETRY_DEAD_LETTER=PASS\n'
printf 'NEXO_DB_DIRECT_ACCESS=0\n'
printf 'DURABLE_NOTIFICATION_OUTBOX_CONTRACT=PASS\n'
