#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-offline-push-recovery.properties"

fail() {
    printf 'OFFLINE_PUSH_RECOVERY=FAIL\n' >&2
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
    docs/architecture/CONNECT_25_OFFLINE_PUSH_RECOVERY.md
    docs/architecture/connect-offline-push-recovery.properties
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/InvalidPushRegistrationRetirement.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/NotificationDeliveryRuntime.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/NotificationOutboxDeliveryWorker.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresInvalidPushRegistrationRetirer.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresPushDeliveryTokenResolver.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/NotificationDeliveryLifecycle.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/ProtectedPushTokenCodec.kt
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProvider.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/NotificationDeliveryRuntimeTest.kt
    src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/NotificationOutboxDeliveryWorkerTest.kt
    src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresNotificationOutboxRepositoryIntegrationTest.kt
)

for required_file in "${required_files[@]}"; do
    [[ -f "$required_file" && ! -L "$required_file" ]] || fail "REQUIRED_FILE_MISSING_OR_UNSAFE:${required_file}"
done

require_property contract.version 1
require_property programme NEXO_CONNECT_LAB
require_property phase CONNECT.25
require_property status IMPLEMENTED
require_property runtime.enabled.default false
require_property runtime.scheduler SINGLE_DAEMON_FIXED_DELAY
require_property runtime.failed.cycle.contained true
require_property runtime.shutdown APPLICATION_STOPPING
require_property outbox.truth POSTGRESQL
require_property provider.outage.durable.impact.count 0
require_property provider.outage.intent.loss.count 0
require_property retry.policy BOUNDED_EXPONENTIAL
require_property invalid.token.retirement VERSION_FENCED_CRYPTOGRAPHIC_ERASURE
require_property invalid.token.material.after.retirement.count 0
require_property token.rotation.winner REPLACEMENT_TOKEN
require_property token.rotation.outbox.result RETRY_PENDING
require_property duplicate.durable.message.count 0
require_property duplicate.visible.message.count 0
require_property offline.catch.up.source POSTGRESQL
require_property offline.catch.up.authorization ACTIVE_PARTICIPANT
require_property payload.message.body false
require_property payload.device.token false
require_property observability.device.token.count 0
require_property observability.provider.credential.count 0
require_property nexo.db.direct.access 0
require_property nexo.mutation FORBIDDEN
require_property commit.subject 'test(connect): [CONNECT.25] prove offline push recovery'
require_property next.phase CONNECT.26

grep -Fq 'Executors.newSingleThreadScheduledExecutor' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/NotificationDeliveryRuntime.kt ||
    fail SINGLE_THREAD_SCHEDULER_MISSING
grep -Fq 'scheduleWithFixedDelay' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/NotificationDeliveryRuntime.kt ||
    fail FIXED_DELAY_SCHEDULE_MISSING
grep -Fq 'A failed cycle leaves PostgreSQL truth intact' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/NotificationDeliveryRuntime.kt ||
    fail FAILED_CYCLE_CONTAINMENT_MISSING
grep -Fq 'ApplicationStopping' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/NotificationDeliveryLifecycle.kt ||
    fail ORDERED_RUNTIME_SHUTDOWN_MISSING
grep -Fq 'notificationDeliveryEnabled: ${CONNECT_LAB_NOTIFICATION_DELIVERY_ENABLED:false}' \
    src/main/resources/application.yaml || fail SAFE_DEFAULT_FLAG_MISSING
grep -Fq 'token_ciphertext, token_nonce, token_key_version, token_version' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresPushDeliveryTokenResolver.kt ||
    fail TOKEN_VERSION_RESOLUTION_MISSING
grep -Fq 'invalidTokenVersion = tokenVersion' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns/ApnsSandboxNotificationProvider.kt ||
    fail APNS_RETIREMENT_FENCE_MISSING
grep -Fq 'current.tokenVersion != request.expectedTokenVersion' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresInvalidPushRegistrationRetirer.kt ||
    fail ROTATION_FENCE_MISSING
for marker in \
    'token_fingerprint = NULL' \
    'token_ciphertext = NULL' \
    'token_nonce = NULL' \
    'token_key_version = NULL'; do
    grep -Fq "$marker" \
        src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresInvalidPushRegistrationRetirer.kt ||
        fail "CRYPTOGRAPHIC_ERASURE_MARKER_MISSING:${marker}"
done
grep -Fq 'InvalidPushRegistrationRetirementResult.TokenRotated' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/NotificationOutboxDeliveryWorker.kt ||
    fail ROTATION_RETRY_PATH_MISSING

for marker in \
    'failed cycle is contained so a later poll can recover' \
    'invalid current token is retired before its intent becomes dead letter' \
    'rotation that wins an invalid-token race schedules retry for the replacement token' \
    'invalid current token is cryptographically erased before dead letter settlement' \
    'outage rotation and reconnect preserve one durable message and one catch up event'; do
    grep -RFq "$marker" src/test src/postgresIntegrationTest ||
        fail "ACCEPTANCE_TEST_MARKER_MISSING:${marker}"
done

./gradlew --no-daemon offlinePushRecoveryTest --rerun-tasks --console=plain

printf 'OFFLINE_PUSH_RECOVERY=PASS\n'
printf 'PROVIDER_OUTAGE_DURABLE_IMPACT=0\n'
printf 'INVALID_TOKEN_MATERIAL_AFTER_RETIREMENT=0\n'
printf 'ROTATED_TOKEN_RETRY=PASS\n'
printf 'DUPLICATE_VISIBLE_MESSAGE_COUNT=0\n'
printf 'POSTGRESQL_CATCH_UP=PASS\n'
