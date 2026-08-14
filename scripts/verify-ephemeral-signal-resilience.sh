#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-ephemeral-signal-resilience.properties"
DECISION_FILE="${PROJECT_DIR}/docs/architecture/CONNECT_20_EPHEMERAL_SIGNAL_RESILIENCE.md"
PRESENCE_STORE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisPresenceLeaseStore.kt"
TYPING_STORE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisTypingLeaseStore.kt"
PRESENCE_TEST="${PROJECT_DIR}/src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisPresenceLeaseStoreTest.kt"
TYPING_TEST="${PROJECT_DIR}/src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisTypingLeaseStoreTest.kt"
RUNTIME_TEST="${PROJECT_DIR}/src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisEphemeralSignalResilienceIntegrationTest.kt"
MIGRATION_DIR="${PROJECT_DIR}/src/main/resources/db/migration"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-20-resilience.XXXXXX")"
FLUSH_MUTATION="${TEMP_DIR}/flush.properties"
CLOCK_MUTATION="${TEMP_DIR}/clock.properties"
DURABLE_MUTATION="${TEMP_DIR}/durable.properties"
STALE_MUTATION="${TEMP_DIR}/stale.properties"

fail() {
    printf 'EPHEMERAL_SIGNAL_RESILIENCE_CONTRACT=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cleanup() {
    local status=$?
    trap - EXIT
    [[ ! -e "$FLUSH_MUTATION" ]] || unlink "$FLUSH_MUTATION"
    [[ ! -e "$CLOCK_MUTATION" ]] || unlink "$CLOCK_MUTATION"
    [[ ! -e "$DURABLE_MUTATION" ]] || unlink "$DURABLE_MUTATION"
    [[ ! -e "$STALE_MUTATION" ]] || unlink "$STALE_MUTATION"
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
    [[ "$(awk 'NF && $0 !~ /^#/ { count++ } END { print count + 0 }' "$file")" == "56" ]] || return 1
    contract_equals "$file" contract.version 1 || return 1
    contract_equals "$file" programme NEXO_CONNECT_LAB || return 1
    contract_equals "$file" phase CONNECT.20 || return 1
    contract_equals "$file" status IMPLEMENTED || return 1
    contract_equals "$file" scope PRESENCE_AND_TYPING_EPHEMERAL_SIGNALS || return 1
    contract_equals "$file" suite.kind REAL_REDIS_MULTI_INSTANCE_FAILURE_INJECTION || return 1
    contract_equals "$file" runtime.instance.count 2 || return 1
    contract_equals "$file" redis.role EPHEMERAL_ONLY || return 1
    contract_equals "$file" redis.persistence DISABLED || return 1
    contract_equals "$file" redis.required.for.durable.chat false || return 1
    contract_equals "$file" failure.redis.flushdb INJECTED || return 1
    contract_equals "$file" failure.clock.skew.seconds 315360000 || return 1
    contract_equals "$file" failure.duplicate.refresh INJECTED || return 1
    contract_equals "$file" failure.instance.crash INJECTED || return 1
    contract_equals "$file" failure.rapid.reconnect INJECTED || return 1
    contract_equals "$file" clock.source REDIS_SERVER_RELATIVE_TTL || return 1
    contract_equals "$file" client.clock.trusted false || return 1
    contract_equals "$file" application.clock.offset.effect NONE || return 1
    contract_equals "$file" presence.lease.clock REDIS_PX || return 1
    contract_equals "$file" typing.lease.clock REDIS_PX || return 1
    contract_equals "$file" duplicate.presence.refresh IDEMPOTENT_OWNER_BOUND || return 1
    contract_equals "$file" duplicate.typing.refresh IDEMPOTENT_OWNER_BOUND || return 1
    contract_equals "$file" duplicate.refresh.key.cardinality 1 || return 1
    contract_equals "$file" duplicate.refresh.ttl.bound CONFIGURED_LEASE_TTL || return 1
    contract_equals "$file" flush.old.presence.handle.refresh NOT_OWNER || return 1
    contract_equals "$file" flush.old.presence.handle.release NOT_OWNER || return 1
    contract_equals "$file" flush.old.typing.handle.refresh NOT_OWNER || return 1
    contract_equals "$file" flush.old.typing.handle.release NOT_OWNER || return 1
    contract_equals "$file" flush.presence.snapshot OFFLINE || return 1
    contract_equals "$file" reconnect.new.presence.owner REQUIRED || return 1
    contract_equals "$file" reconnect.new.typing.owner REQUIRED || return 1
    contract_equals "$file" reconnect.old.handle.authority NONE || return 1
    contract_equals "$file" crash.cleanup.mode TTL_ONLY || return 1
    contract_equals "$file" crash.presence.deadline LEASE_PLUS_RECENT_WINDOW || return 1
    contract_equals "$file" crash.typing.deadline TYPING_TTL || return 1
    contract_equals "$file" stale.presence.device.key.count 0 || return 1
    contract_equals "$file" stale.presence.marker.key.count 0 || return 1
    contract_equals "$file" stale.typing.key.count 0 || return 1
    contract_equals "$file" durable.truth POSTGRESQL || return 1
    contract_equals "$file" durable.hash PRESERVED || return 1
    contract_equals "$file" durable.messages.changed 0 || return 1
    contract_equals "$file" durable.receipts.changed 0 || return 1
    contract_equals "$file" durable.message.duplicates 0 || return 1
    contract_equals "$file" postgres.presence.writes 0 || return 1
    contract_equals "$file" postgres.typing.writes 0 || return 1
    contract_equals "$file" postgres.migration.count 5 || return 1
    contract_equals "$file" redis.admin.credential.scope RUNTIME_TEST_ONLY || return 1
    contract_equals "$file" runtime.redis.probe REAL_ISOLATED_REDIS || return 1
    contract_equals "$file" runtime.postgres.probe SCHEMA_HASH_BEFORE_AFTER || return 1
    contract_equals "$file" runtime.clock.skew.probe EXTREME_OFFSET_IGNORED || return 1
    contract_equals "$file" runtime.cleanup ZERO_EPHEMERAL_KEYS || return 1
    contract_equals "$file" failure.redis.unavailable SILENT_EPHEMERAL_DEGRADATION || return 1
    contract_equals "$file" nexo.db.direct.access 0 || return 1
    contract_equals "$file" nexo.mutation FORBIDDEN || return 1
    contract_equals "$file" commit.subject 'test(connect): [CONNECT.20] prove ephemeral signal resilience' || return 1
    contract_equals "$file" next.phase CONNECT.21 || return 1
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail REPOSITORY_IDENTITY_MISMATCH
[[ -f "$DECISION_FILE" && ! -L "$DECISION_FILE" ]] || fail DECISION_MISSING_OR_UNSAFE
[[ -f "$PRESENCE_STORE" && -f "$TYPING_STORE" ]] || fail EPHEMERAL_STORES_MISSING
[[ -f "$PRESENCE_TEST" && -f "$TYPING_TEST" && -f "$RUNTIME_TEST" ]] || fail RESILIENCE_TESTS_MISSING
validate_contract "$CONTRACT_FILE" || fail RESILIENCE_PROPERTIES_MISMATCH

for heading in \
    '## Scope and authority' \
    '## Failure matrix' \
    '## Flush and ownership recovery' \
    '## Relative time and clock skew' \
    '## Crash expiry' \
    '## Durable isolation' \
    '## Acceptance evidence' \
    '## Phase boundary'; do
    grep -Fqx "$heading" "$DECISION_FILE" || fail "DECISION_SECTION_MISSING:${heading#\#\# }"
done

for deterministic_probe in \
    'fun `flush rejects stale handles and rapid reconnect establishes one owner`' \
    'fun `flush rejects stale handles and rapid reconnect keeps one typing lease`' \
    'state.flush()' \
    'assertEquals(1, state.countMatching'; do
    grep -Fq "$deterministic_probe" "$PRESENCE_TEST" "$TYPING_TEST" ||
        fail DETERMINISTIC_FAILURE_PROBE_MISSING
done

for runtime_probe in \
    'adminConnection.sync().flushdb()' \
    'CONNECT_LAB_TEST_CLOCK_OFFSET_SECONDS' \
    'PresenceLeaseMutationResult.NOT_OWNER' \
    'TypingLeaseRefreshResult.NotOwner' \
    'delay(LEASE_TTL_MILLIS + RECENTLY_ONLINE_MILLIS + EXPIRY_MARGIN_MILLIS)' \
    'assertEquals(0, countKeys(adminConnection, PRESENCE_PATTERN))' \
    'assertEquals(0, countKeys(adminConnection, TYPING_PATTERN))'; do
    grep -Fq "$runtime_probe" "$RUNTIME_TEST" || fail REAL_REDIS_FAILURE_PROBE_MISSING
done

if grep -Ein 'Instant\.now|Clock\.|System\.currentTimeMillis|LocalDateTime\.now' "$PRESENCE_STORE" "$TYPING_STORE"; then
    fail APPLICATION_CLOCK_DEPENDENCY_DETECTED
fi
grep -Fq 'SetArgs.Builder.px(ttlMillis)' "$PRESENCE_STORE" || fail REDIS_RELATIVE_TTL_MISSING
grep -Fq 'PEXPIRE' "$PRESENCE_STORE" || fail REDIS_RELATIVE_REFRESH_MISSING
[[ "$(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' -print | wc -l | tr -d '[:space:]')" == "5" ]] ||
    fail POSTGRES_MIGRATION_SET_CHANGED

awk '$0 == "failure.redis.flushdb=INJECTED" { print "failure.redis.flushdb=IGNORED"; next } { print }' \
    "$CONTRACT_FILE" > "$FLUSH_MUTATION"
validate_contract "$FLUSH_MUTATION" && fail FLUSH_MUTATION_ACCEPTED
awk '$0 == "client.clock.trusted=false" { print "client.clock.trusted=true"; next } { print }' \
    "$CONTRACT_FILE" > "$CLOCK_MUTATION"
validate_contract "$CLOCK_MUTATION" && fail CLOCK_MUTATION_ACCEPTED
awk '$0 == "durable.hash=PRESERVED" { print "durable.hash=CHANGED"; next } { print }' \
    "$CONTRACT_FILE" > "$DURABLE_MUTATION"
validate_contract "$DURABLE_MUTATION" && fail DURABLE_MUTATION_ACCEPTED
awk '$0 == "stale.typing.key.count=0" { print "stale.typing.key.count=1"; next } { print }' \
    "$CONTRACT_FILE" > "$STALE_MUTATION"
validate_contract "$STALE_MUTATION" && fail STALE_KEY_MUTATION_ACCEPTED

./gradlew --no-daemon ephemeralSignalResilienceTest --rerun-tasks --console=plain

printf 'REDIS_FLUSH_CONTRACT=PASS\n'
printf 'APPLICATION_CLOCK_SKEW_TOLERANCE=PASS\n'
printf 'DUPLICATE_REFRESH_IDEMPOTENCE=PASS\n'
printf 'INSTANCE_CRASH_EXPIRY_CONTRACT=PASS\n'
printf 'RAPID_RECONNECT_OWNERSHIP=PASS\n'
printf 'DURABLE_SIGNAL_ISOLATION=PASS\n'
printf 'RESILIENCE_MUTATIONS_REJECTED=PASS\n'
printf 'NEXO_DB_DIRECT_ACCESS=0\n'
printf 'EPHEMERAL_SIGNAL_RESILIENCE_CONTRACT=PASS\n'
