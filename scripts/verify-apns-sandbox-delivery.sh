#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-apns-sandbox-delivery.properties"
DECISION_FILE="${PROJECT_DIR}/docs/architecture/CONNECT_23_APNS_SANDBOX_DELIVERY.md"
APPLICATION_DIR="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push"
APNS_DIR="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns"
TOKEN_RESOLVER_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresPushDeliveryTokenResolver.kt"
WORKER_TEST="${PROJECT_DIR}/src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/push/NotificationOutboxDeliveryWorkerTest.kt"
APNS_TEST_DIR="${PROJECT_DIR}/src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/apns"
POSTGRES_TEST="${PROJECT_DIR}/src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresPushDeviceRegistryIntegrationTest.kt"

fail() {
    printf 'APNS_SANDBOX_DELIVERY_CONTRACT=FAIL\n' >&2
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

for required_path in \
    "$CONTRACT_FILE" "$DECISION_FILE" "$APPLICATION_DIR" "$APNS_DIR" \
    "$TOKEN_RESOLVER_FILE" "$WORKER_TEST" "$APNS_TEST_DIR" "$POSTGRES_TEST"; do
    [[ -e "$required_path" && ! -L "$required_path" ]] || fail REQUIRED_PATH_MISSING_OR_UNSAFE
done

require_property contract.version 1
require_property programme NEXO_CONNECT_LAB
require_property phase CONNECT.23
require_property status IMPLEMENTED
require_property provider APNS
require_property environment SANDBOX_ONLY
require_property sandbox.host api.sandbox.push.apple.com
require_property transport HTTPS_HTTP_2
require_property authentication ES256_PROVIDER_TOKEN
require_property authentication.private.key PKCS8_P256_FILE_BOUNDARY
require_property authentication.expired.token INVALIDATE_AND_RETRY
require_property request.push.type BACKGROUND
require_property request.priority 5
require_property request.payload.max.bytes 4096
require_property request.payload.message.body false
require_property request.payload.device.token false
require_property response.invalid.registration DEAD_LETTER_REGISTRATION_REVOKED
require_property response.429 RETRY_PROVIDER_RATE_LIMITED
require_property response.5xx RETRY_PROVIDER_UNAVAILABLE
require_property provider.outage.intent.loss.count 0
require_property observability CLOSED_SANITISED_EVENT
require_property observability.device.token.count 0
require_property observability.provider.credential.count 0
require_property runtime.scheduler DEFERRED_CONNECT_25
require_property invalid.token.cleanup DEFERRED_CONNECT_25
require_property nexo.db.direct.access 0
require_property next.phase CONNECT.24

for marker in \
    'api.sandbox.push.apple.com' \
    'HttpClient.Version.HTTP_2' \
    'SHA256withECDSAinP1363Format' \
    'apns-push-type", "background' \
    'apns-priority", "5' \
    'ExpiredProviderToken' \
    'statusCode == 429' \
    'BadDeviceToken' \
    'DeviceTokenNotForTopic'; do
    grep -RFq "$marker" "$APNS_DIR" || fail "APNS_PROTOCOL_MARKER_MISSING:${marker}"
done

for marker in \
    'RecordNotificationFailureRequest' \
    'DeadLetterNotificationRequest' \
    'MarkNotificationDeliveredRequest' \
    'SanitizedNotificationDeliveryEvent'; do
    grep -RFq "$marker" "$APPLICATION_DIR" || fail "OUTBOX_WORKER_MARKER_MISSING:${marker}"
done

for marker in \
    'organization_scope_ref IS NOT DISTINCT FROM ?' \
    "status = 'ACTIVE'" \
    'tokenCodec.revealForDelivery' \
    'revealed.fill(0)'; do
    grep -Fq "$marker" "$TOKEN_RESOLVER_FILE" || fail "TOKEN_RESOLUTION_MARKER_MISSING:${marker}"
done

for marker in \
    'provider outage schedules durable retry' \
    'observability stays sanitised' \
    'APNs response taxonomy is closed and deterministic' \
    'provider token uses ES256 P1363' \
    'redacts device and authorization secrets' \
    'delivery token resolver decrypts only the exact active owner scoped registration'; do
    grep -RFq "$marker" "$WORKER_TEST" "$APNS_TEST_DIR" "$POSTGRES_TEST" ||
        fail "ACCEPTANCE_TEST_MARKER_MISSING:${marker}"
done

if grep -REn '(println\(|printStackTrace\(|logger\.|[.]log\()' "$APPLICATION_DIR" "$APNS_DIR" "$TOKEN_RESOLVER_FILE"; then
    fail UNSANITISED_RUNTIME_LOGGING_DETECTED
fi

if grep -REn '(BEGIN [A-Z ]*PRIVATE KEY|provider-jwt-secret-value|private message body)' \
    "$APPLICATION_DIR" "$APNS_DIR" "$TOKEN_RESOLVER_FILE"; then
    fail SECRET_OR_PRIVATE_BODY_LITERAL_DETECTED
fi

./gradlew --no-daemon apnsSandboxAdapterTest --rerun-tasks --console=plain

printf 'APNS_HTTP2_SANDBOX_CONTRACT=PASS\n'
printf 'APNS_RESPONSE_TAXONOMY=PASS\n'
printf 'PROVIDER_OUTAGE_INTENT_LOSS=0\n'
printf 'DEVICE_TOKEN_LOG_DISCLOSURE=0\n'
printf 'PROVIDER_SECRET_LOG_DISCLOSURE=0\n'
printf 'NEXO_DB_DIRECT_ACCESS=0\n'
printf 'APNS_SANDBOX_DELIVERY_CONTRACT=PASS\n'
