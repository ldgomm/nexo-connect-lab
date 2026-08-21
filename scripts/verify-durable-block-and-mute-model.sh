#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-durable-block-and-mute-model.properties"

fail() {
    printf 'DURABLE_BLOCK_AND_MUTE_MODEL=FAIL\n' >&2
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

required_files=(
    docs/architecture/CONNECT_26_DURABLE_BLOCK_AND_MUTE_MODEL.md
    docs/architecture/connect-durable-block-and-mute-model.properties
    scripts/verify-database-lifecycle.sh
    scripts/verify-postgres-repository.sh
    scripts/verify-postgres-schema.sh
    src/main/resources/db/migration/V9__durable_block_and_mute_model.sql
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/safety/ConversationSafetyModel.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence/ConversationBlockRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/safety/ConversationBlockAuthorization.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationBlockRepository.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresPushNotificationPreferenceRepository.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/safety/ConversationSafetyModelTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/safety/ConversationBlockAuthorizationTest.kt
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationSafetyRepositoryIntegrationTest.kt
)

for required_file in "${required_files[@]}"; do
    [[ -f "$required_file" && ! -L "$required_file" ]] || fail "REQUIRED_FILE_MISSING_OR_UNSAFE:${required_file}"
done

require_property contract.version 1
require_property programme NEXO_CONNECT_LAB
require_property phase CONNECT.26
require_property status IMPLEMENTED
require_property block.truth POSTGRESQL
require_property block.scope CONVERSATION_PLATFORM_ORGANIZATION_BUSINESS
require_property block.direction DIRECTIONAL_RECIPROCAL_INDEPENDENT
require_property block.enforcement EITHER_ACTIVE_DIRECTION_DENIES
require_property block.version.fencing REQUIRED
require_property block.replay UNCHANGED_NO_AUDIT_DUPLICATE
require_property block.denial NOT_FOUND_OR_DENIED
require_property block.authorization.default DENY
require_property block.authorization.unavailable DENY
require_property block.audit APPEND_ONLY_TRANSACTIONAL
require_property block.delete.path false
require_property mute.truth POSTGRESQL_PUSH_NOTIFICATION_PREFERENCE
require_property mute.scope CONVERSATION_AND_REGISTERED_DEVICE
require_property mute.audit APPEND_ONLY_TRANSITIONS
require_property mute.outbox.intent.count 0
require_property mute.durable.delivery.impact.count 0
require_property audit.message.body false
require_property audit.device.token false
require_property audit.free.text.reason false
require_property runtime.block.enforcement DEFERRED_TO_CONNECT_27
require_property nexo.db.direct.access 0
require_property nexo.mutation FORBIDDEN
require_property commit.subject 'feat(connect): [CONNECT.26] add durable block and mute model'
require_property next.phase CONNECT.27

migration=src/main/resources/db/migration/V9__durable_block_and_mute_model.sql
grep -Fq 'CREATE TABLE connect.conversation_blocks' "$migration" || fail BLOCK_TABLE_MISSING
grep -Fq 'uq_connect_conversation_block_direction' "$migration" || fail DIRECTION_UNIQUENESS_MISSING
grep -Fq "scope_type = 'CONVERSATION'" "$migration" || fail EXPLICIT_BLOCK_SCOPE_MISSING
grep -Fq 'CREATE TABLE connect.conversation_block_audit_events' "$migration" || fail BLOCK_AUDIT_MISSING
grep -Fq 'CREATE TABLE connect.notification_mute_audit_events' "$migration" || fail MUTE_AUDIT_MISSING
grep -Fq 'REVOKE UPDATE, DELETE ON connect.conversation_block_audit_events' "$migration" ||
    fail BLOCK_AUDIT_IMMUTABILITY_MISSING
grep -Fq 'REVOKE UPDATE, DELETE ON connect.notification_mute_audit_events' "$migration" ||
    fail MUTE_AUDIT_IMMUTABILITY_MISSING
grep -Fq "version = '9' AND success" scripts/verify-postgres-schema.sh ||
    fail FLYWAY_NINE_SCHEMA_GATE_MISSING
grep -Fq 'POSTGRES_CONVERSATION_SAFETY_SCHEMA=PASS' scripts/verify-postgres-schema.sh ||
    fail CONVERSATION_SAFETY_SCHEMA_GATE_MISSING
grep -Fq 'POSTGRES_CONVERSATION_SAFETY_INTEGRATION=PASS' scripts/verify-postgres-repository.sh ||
    fail CONVERSATION_SAFETY_REPOSITORY_GATE_MISSING

lifecycle=scripts/verify-database-lifecycle.sh
grep -Fq 'EXPECTED_FLYWAY_MIGRATION_COUNT="9"' "$lifecycle" ||
    fail DATABASE_LIFECYCLE_FLYWAY_COUNT_STALE
grep -Fq 'EXPECTED_APP_READ_WRITE_TABLE_COUNT="10"' "$lifecycle" ||
    fail DATABASE_LIFECYCLE_APP_GRANT_COUNT_STALE

authorization=src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/safety/ConversationBlockAuthorization.kt
grep -Fq 'ConversationBlockLookupResult.Unavailable' "$authorization" || fail UNAVAILABLE_BLOCK_STATE_MISSING
grep -Fq 'DENY_AUTHORITY_UNAVAILABLE' "$authorization" || fail FAIL_CLOSED_DECISION_MISSING
grep -Fq 'catch (_: Exception)' "$authorization" || fail LOOKUP_FAILURE_CONTAINMENT_MISSING
if grep -Eiq '(notification|mute)' "$authorization"; then
    fail NOTIFICATION_MUTE_COUPLED_TO_DURABLE_AUTHORIZATION
fi

repository=src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationBlockRepository.kt
grep -Fq 'relationship_block.blocker_subject_ref = ?' "$repository" || fail FORWARD_DIRECTION_LOOKUP_MISSING
grep -Fq 'relationship_block.blocked_subject_ref = ?' "$repository" || fail REVERSE_DIRECTION_LOOKUP_MISSING
grep -Fq 'Connection.TRANSACTION_SERIALIZABLE' "$repository" || fail SERIALIZABLE_BLOCK_MUTATION_MISSING
grep -Fq 'appendAudit(connection, updated' "$repository" || fail TRANSACTIONAL_BLOCK_AUDIT_MISSING

preference_repository=src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresPushNotificationPreferenceRepository.kt
grep -Fq 'appendMuteAudit' "$preference_repository" || fail TRANSACTIONAL_MUTE_AUDIT_MISSING
grep -Fq 'existing.muted != updated.muted' "$preference_repository" || fail MUTE_TRANSITION_FENCE_MISSING

integration_test=src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresConversationSafetyRepositoryIntegrationTest.kt
for marker in \
    'directional blocks are version fenced audited and deny communication in either direction' \
    'scope guessing is uniform and an unresolved block authority never allows' \
    'notification mute appends immutable audit without changing durable delivery truth'; do
    grep -Fq "$marker" "$integration_test" || fail "ACCEPTANCE_TEST_MARKER_MISSING:${marker}"
done
grep -Fq 'SELECT count(*) FROM connect.messages' "$integration_test" || fail DURABLE_MESSAGE_PROOF_MISSING
grep -Fq 'SELECT count(*) FROM connect.notification_outbox' "$integration_test" || fail MUTED_ZERO_INTENT_PROOF_MISSING

audit_schema="$({
    sed -n '/CREATE TABLE connect.conversation_block_audit_events/,/^);/p' "$migration"
    sed -n '/CREATE TABLE connect.notification_mute_audit_events/,/^);/p' "$migration"
} || true)"
if grep -Eiq '(message_body|device_token|token_ciphertext|provider_credential|reason_text)' <<<"$audit_schema"; then
    fail PRIVATE_OR_FREE_TEXT_AUDIT_FIELD_PRESENT
fi

./gradlew --no-daemon durableBlockAndMuteModelTest --rerun-tasks --console=plain

printf 'DURABLE_BLOCK_AND_MUTE_MODEL=PASS\n'
printf 'BLOCK_AUTHORIZATION_DEFAULT=DENY\n'
printf 'BLOCK_AUDIT=APPEND_ONLY\n'
printf 'MUTE_DURABLE_DELIVERY_IMPACT=0\n'
printf 'NEXT_PHASE=CONNECT.27\n'
