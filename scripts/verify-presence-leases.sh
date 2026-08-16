#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-presence-leases.properties"
DECISION_FILE="${PROJECT_DIR}/docs/architecture/CONNECT_17_EPHEMERAL_PRESENCE_LEASES.md"
STORE_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisPresenceLeaseStore.kt"
ROUTE_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt"
MIGRATION_DIR="${PROJECT_DIR}/src/main/resources/db/migration"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-17-presence.XXXXXX")"
TTL_MUTATION="${TEMP_DIR}/ttl.properties"
OWNER_MUTATION="${TEMP_DIR}/owner.properties"
DURABLE_MUTATION="${TEMP_DIR}/durable.properties"

fail() {
    printf 'PRESENCE_LEASE_CONTRACT=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cleanup() {
    local status=$?
    trap - EXIT
    [[ ! -e "$TTL_MUTATION" ]] || unlink "$TTL_MUTATION"
    [[ ! -e "$OWNER_MUTATION" ]] || unlink "$OWNER_MUTATION"
    [[ ! -e "$DURABLE_MUTATION" ]] || unlink "$DURABLE_MUTATION"
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
    [[ "$(awk 'NF && $0 !~ /^#/ { count++ } END { print count + 0 }' "$file")" == "46" ]] || return 1
    contract_equals "$file" contract.version 1 || return 1
    contract_equals "$file" programme NEXO_CONNECT_LAB || return 1
    contract_equals "$file" phase CONNECT.17 || return 1
    contract_equals "$file" status IMPLEMENTED || return 1
    contract_equals "$file" presence.role EPHEMERAL_AUTHORISED_PROJECTION_ONLY || return 1
    contract_equals "$file" lease.backend REDIS_TTL || return 1
    contract_equals "$file" lease.namespace nexo-connect-lab:presence:v1 || return 1
    contract_equals "$file" lease.key.schema nexo-connect-lab:presence:v1:s:SUBJECT_DIGEST:d:DEVICE_DIGEST || return 1
    contract_equals "$file" lease.key.subject.component SHA256_OPAQUE_DIGEST || return 1
    contract_equals "$file" lease.key.device.component SHA256_OPAQUE_DIGEST || return 1
    contract_equals "$file" lease.key.raw.subject false || return 1
    contract_equals "$file" lease.key.raw.device false || return 1
    contract_equals "$file" lease.key.max.bytes 160 || return 1
    contract_equals "$file" lease.value.schema 'INSTANCE_REF|LEASE_REF' || return 1
    contract_equals "$file" lease.value.instance.ownership REQUIRED || return 1
    contract_equals "$file" lease.value.max.bytes 192 || return 1
    contract_equals "$file" lease.ttl.seconds 45 || return 1
    contract_equals "$file" lease.refresh.interval.seconds 15 || return 1
    contract_equals "$file" lease.acquire.operation ATOMIC_SET_WITH_PX || return 1
    contract_equals "$file" lease.refresh.operation COMPARE_OWNER_AND_PEXPIRE || return 1
    contract_equals "$file" lease.release.operation COMPARE_OWNER_AND_DELETE || return 1
    contract_equals "$file" lease.stale.owner.behaviour REJECT_MUTATION || return 1
    contract_equals "$file" reconnect.behaviour ROTATE_OWNER_AND_RENEW_TTL || return 1
    contract_equals "$file" crash.behaviour TTL_EXPIRY_WITHOUT_CLEANUP_WRITE || return 1
    contract_equals "$file" stale.expiry REDIS_NATIVE_TTL || return 1
    contract_equals "$file" bounded.cardinality ACTIVE_CONNECTION_LIMIT_X_TTL_WINDOW || return 1
    contract_equals "$file" local.connection.capacity 10000 || return 1
    contract_equals "$file" redis.persistence.required false || return 1
    contract_equals "$file" redis.outage.chat.behaviour CONTINUE_WITHOUT_PRESENCE || return 1
    contract_equals "$file" redis.recovery.behaviour REACQUIRE_OR_REFRESH || return 1
    contract_equals "$file" application.heartbeat.source AUTHENTICATED_WEBSOCKET || return 1
    contract_equals "$file" postgres.presence.persistence forbidden || return 1
    contract_equals "$file" postgres.migration.count 5 || return 1
    contract_equals "$file" durable.message.history.includes.presence false || return 1
    contract_equals "$file" durable.receipt.history.includes.presence false || return 1
    contract_equals "$file" durable.outbox.includes.presence false || return 1
    contract_equals "$file" presence.exact.last.seen.exposed false || return 1
    contract_equals "$file" device.topology.exposed false || return 1
    contract_equals "$file" instance.topology.exposed false || return 1
    contract_equals "$file" runtime.redis.probe REAL_ISOLATED_REDIS || return 1
    contract_equals "$file" runtime.postgres.hash PRESERVED || return 1
    contract_equals "$file" runtime.stale.key.count 0 || return 1
    contract_equals "$file" metrics.identifiers forbidden || return 1
    contract_equals "$file" nexo.db.direct.access 0 || return 1
    contract_equals "$file" nexo.mutation forbidden || return 1
    contract_equals "$file" next.phase CONNECT.18 || return 1
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail REPOSITORY_IDENTITY_MISMATCH
[[ -f "$DECISION_FILE" && ! -L "$DECISION_FILE" ]] || fail DECISION_MISSING_OR_UNSAFE
[[ -f "$STORE_FILE" && -f "$ROUTE_FILE" ]] || fail IMPLEMENTATION_MISSING
validate_contract "$CONTRACT_FILE" || fail PRESENCE_LEASE_PROPERTIES_MISMATCH

for heading in \
    '## Scope and authority' \
    '## Lease identity and bounded keys' \
    '## Acquire, refresh, and release' \
    '## Crash and reconnect semantics' \
    '## Application lifecycle' \
    '## Durable isolation' \
    '## Acceptance evidence' \
    '## Phase boundary'; do
    grep -Fqx "$heading" "$DECISION_FILE" || fail "DECISION_SECTION_MISSING:${heading#\#\# }"
done

for source_contract in \
    'val DEFAULT_LEASE_TTL: Duration = Duration.ofSeconds(45)' \
    'val DEFAULT_REFRESH_INTERVAL: Duration = Duration.ofSeconds(15)' \
    'const val MAX_KEY_BYTES = 160' \
    'COMPARE_AND_REFRESH_SCRIPT' \
    'COMPARE_AND_DELETE_SCRIPT' \
    'current.setWithTtl' \
    'PresenceLeaseMutationResult.NOT_OWNER'; do
    grep -Fq "$source_contract" "$STORE_FILE" || fail IMPLEMENTATION_CONTRACT_MISSING
done

for route_contract in \
    'acquirePresenceLease(runtime.presenceLeaseStore, presenceTarget)' \
    'refreshPresenceLease(' \
    'registryRefreshJob.cancelAndJoin()' \
    'releasePresenceLease(runtime.presenceLeaseStore, presenceHandle)'; do
    grep -Fq "$route_contract" "$ROUTE_FILE" || fail WEBSOCKET_HEARTBEAT_CONTRACT_MISSING
done

grep -Fq \
    'RedisPresenceLeaseLifecycleKt.configureRedisPresenceLeaseLifecycle' \
    src/main/resources/application.yaml || fail PRESENCE_LEASE_LIFECYCLE_NOT_INSTALLED

for acl_command in '+set' '+get' '+del' '+pttl' '+pexpire' '+eval'; do
    grep -Fq -- "$acl_command" docker/redis/start-ephemeral-redis.sh ||
        fail "REDIS_APP_ACL_COMMAND_MISSING:${acl_command#+}"
done
grep -Fq '~nexo-connect-lab:*' docker/redis/start-ephemeral-redis.sh || fail REDIS_KEY_NAMESPACE_ACL_MISSING

migration_count="$(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' -print | wc -l | tr -d '[:space:]')"
[[ "$migration_count" =~ ^[0-9]+$ && "$migration_count" -ge 5 ]] || fail POSTGRES_MIGRATION_BASELINE_MISSING
if grep -REin \
    '(^|[^[:alnum:]_])(presence|last_seen|last_seen_at|online_status)([^[:alnum:]_]|$)' \
    "$MIGRATION_DIR"; then
    fail PRESENCE_DURABLE_MIGRATION_DETECTED
fi
if grep -REin \
    '(Presence|LastSeen)(Repository|Record|Entity)' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/persistence; then
    fail PRESENCE_DURABLE_REPOSITORY_DETECTED
fi

awk '$0 == "lease.ttl.seconds=45" { print "lease.ttl.seconds=0"; next } { print }' \
    "$CONTRACT_FILE" > "$TTL_MUTATION"
validate_contract "$TTL_MUTATION" && fail ZERO_TTL_MUTATION_ACCEPTED
awk '$0 == "lease.value.instance.ownership=REQUIRED" { print "lease.value.instance.ownership=OPTIONAL"; next } { print }' \
    "$CONTRACT_FILE" > "$OWNER_MUTATION"
validate_contract "$OWNER_MUTATION" && fail OPTIONAL_OWNERSHIP_MUTATION_ACCEPTED
awk '$0 == "postgres.presence.persistence=forbidden" { print "postgres.presence.persistence=allowed"; next } { print }' \
    "$CONTRACT_FILE" > "$DURABLE_MUTATION"
validate_contract "$DURABLE_MUTATION" && fail DURABLE_PRESENCE_MUTATION_ACCEPTED

./gradlew --no-daemon presenceLeaseTest --rerun-tasks --console=plain

printf 'PRESENCE_LEASE_ACQUIRE_REFRESH_RELEASE=PASS\n'
printf 'PRESENCE_LEASE_INSTANCE_OWNERSHIP=PASS\n'
printf 'PRESENCE_LEASE_CRASH_EXPIRY=PASS\n'
printf 'PRESENCE_LEASE_RECONNECT_RENEWAL=PASS\n'
printf 'PRESENCE_LEASE_KEYS_BOUNDED=PASS\n'
printf 'PRESENCE_MUTABLE_POSTGRES_WRITES=0\n'
printf 'PRESENCE_LEASE_MUTATIONS_REJECTED=PASS\n'
printf 'NEXO_DB_DIRECT_ACCESS=0\n'
printf 'PRESENCE_LEASE_CONTRACT=PASS\n'
