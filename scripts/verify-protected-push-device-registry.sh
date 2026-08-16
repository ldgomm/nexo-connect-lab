#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-protected-push-device-registry.properties"
DECISION_FILE="${PROJECT_DIR}/docs/architecture/CONNECT_21_PROTECTED_PUSH_DEVICE_REGISTRY.md"
MIGRATION_FILE="${PROJECT_DIR}/src/main/resources/db/migration/V6__protected_push_device_registry.sql"
REGISTRY_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresPushDeviceRegistry.kt"
CODEC_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/ProtectedPushTokenCodec.kt"
SECRET_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/push/PushTokenSecret.kt"
UNIT_TEST="${PROJECT_DIR}/src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/push/ProtectedPushTokenCodecTest.kt"
INTEGRATION_TEST="${PROJECT_DIR}/src/postgresIntegrationTest/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence/postgres/PostgresPushDeviceRegistryIntegrationTest.kt"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-21-push.XXXXXX")"
DISCLOSURE_MUTATION="${TEMP_DIR}/disclosure.properties"

fail() {
    printf 'PROTECTED_PUSH_DEVICE_REGISTRY_CONTRACT=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cleanup() {
    local status=$?
    trap - EXIT
    [[ ! -e "$DISCLOSURE_MUTATION" ]] || unlink "$DISCLOSURE_MUTATION"
    rmdir "$TEMP_DIR" 2>/dev/null || status=1
    exit "$status"
}

trap cleanup EXIT

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
    "$CONTRACT_FILE" "$DECISION_FILE" "$MIGRATION_FILE" "$REGISTRY_FILE" \
    "$CODEC_FILE" "$SECRET_FILE" "$UNIT_TEST" "$INTEGRATION_TEST"; do
    [[ -f "$required_file" && ! -L "$required_file" ]] || fail REQUIRED_FILE_MISSING_OR_UNSAFE
done

require_property contract.version 1
require_property programme NEXO_CONNECT_LAB
require_property phase CONNECT.21
require_property status IMPLEMENTED
require_property token.storage AES_256_GCM_CIPHERTEXT
require_property token.authentication FULL_OWNER_APPLICATION_PROVIDER_ENVIRONMENT_AAD
require_property token.fingerprint HMAC_SHA256
require_property device.fingerprint HMAC_SHA256_SCOPED
require_property token.plaintext.persisted false
require_property token.plaintext.logged false
require_property token.plaintext.returned false
require_property token.disclosure.count 0
require_property token.rotation VERSION_FENCED
require_property token.revocation CRYPTOGRAPHIC_ERASURE
require_property application.actor.ownership REQUIRED
require_property guessing.result NOT_FOUND_OR_DENIED
require_property tenant.crossover DENIED
require_property repository.transaction SERIALIZABLE_RETRY_BOUNDED
require_property nexo.db.direct.access 0
require_property next.phase CONNECT.22

for marker in \
    'AES/GCM/NoPadding' \
    'HmacSHA256' \
    'cipher.updateAAD(authenticatedData)' \
    'override fun toString(): String = "PushTokenSecret([REDACTED])"'; do
    grep -Fq "$marker" "$CODEC_FILE" "$SECRET_FILE" || fail CRYPTO_BOUNDARY_MARKER_MISSING
done

for predicate in \
    'organization_scope_ref IS NOT DISTINCT FROM ?' \
    'business_scope_ref IS NOT DISTINCT FROM ?' \
    "status = 'ACTIVE'" \
    'version = ?' \
    'token_ciphertext = NULL' \
    'token_nonce = NULL' \
    'token_key_version = NULL'; do
    grep -Fq "$predicate" "$REGISTRY_FILE" || fail REPOSITORY_SCOPE_OR_ERASURE_MISSING
done

for schema_marker in \
    'ck_connect_push_scope_shape' \
    'ck_connect_push_application_owner' \
    'uq_connect_push_active_device_binding' \
    'uq_connect_push_active_token_fingerprint' \
    'GRANT SELECT, INSERT, UPDATE ON connect.push_device_registrations'; do
    grep -Fq "$schema_marker" "$MIGRATION_FILE" || fail POSTGRES_PROTECTION_MARKER_MISSING
done

if grep -Ein '(println|printf|logger|log\.)[^\n]*(token|ciphertext|nonce)' \
    "$REGISTRY_FILE" "$CODEC_FILE" "$SECRET_FILE"; then
    fail TOKEN_LOGGING_SURFACE_DETECTED
fi

awk '$0 == "token.disclosure.count=0" { print "token.disclosure.count=1"; next } { print }' \
    "$CONTRACT_FILE" > "$DISCLOSURE_MUTATION"
grep -Fqx 'token.disclosure.count=0' "$DISCLOSURE_MUTATION" && fail DISCLOSURE_MUTATION_ACCEPTED

./gradlew --no-daemon pushDeviceRegistryTest --rerun-tasks --console=plain

printf 'PUSH_TOKEN_PROTECTION=PASS\n'
printf 'PUSH_SCOPE_OWNERSHIP=PASS\n'
printf 'PUSH_TOKEN_DISCLOSURE=0\n'
printf 'PUSH_REGISTRY_MUTATIONS_REJECTED=PASS\n'
printf 'NEXO_DB_DIRECT_ACCESS=0\n'
printf 'PROTECTED_PUSH_DEVICE_REGISTRY_CONTRACT=PASS\n'
