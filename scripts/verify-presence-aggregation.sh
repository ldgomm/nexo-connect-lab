#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-presence-aggregation.properties"
DECISION_FILE="${PROJECT_DIR}/docs/architecture/CONNECT_19_PRIVACY_AWARE_PRESENCE_AGGREGATION.md"
AGGREGATOR_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/presence/PrivacyAwarePresenceAggregator.kt"
STORE_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/redis/RedisPresenceLeaseStore.kt"
MIGRATION_DIR="${PROJECT_DIR}/src/main/resources/db/migration"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-19-presence.XXXXXX")"
AGGREGATION_MUTATION="${TEMP_DIR}/aggregation.properties"
TOPOLOGY_MUTATION="${TEMP_DIR}/topology.properties"
CLOCK_MUTATION="${TEMP_DIR}/clock.properties"
DENIAL_MUTATION="${TEMP_DIR}/denial.properties"

fail() {
    printf 'PRESENCE_AGGREGATION_CONTRACT=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cleanup() {
    local status=$?
    trap - EXIT
    [[ ! -e "$AGGREGATION_MUTATION" ]] || unlink "$AGGREGATION_MUTATION"
    [[ ! -e "$TOPOLOGY_MUTATION" ]] || unlink "$TOPOLOGY_MUTATION"
    [[ ! -e "$CLOCK_MUTATION" ]] || unlink "$CLOCK_MUTATION"
    [[ ! -e "$DENIAL_MUTATION" ]] || unlink "$DENIAL_MUTATION"
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
    contract_equals "$file" phase CONNECT.19 || return 1
    contract_equals "$file" status IMPLEMENTED || return 1
    contract_equals "$file" aggregation.source REDIS_RELATIVE_TTL || return 1
    contract_equals "$file" aggregation.online.rule ANY_ACTIVE_DEVICE_LEASE || return 1
    contract_equals "$file" aggregation.recently.online.rule NO_ACTIVE_DEVICE_AND_RECENT_MARKER || return 1
    contract_equals "$file" aggregation.offline.rule NO_ACTIVE_DEVICE_AND_NO_RECENT_MARKER || return 1
    contract_equals "$file" aggregation.unavailable.rule SILENT_NO_FRAME || return 1
    contract_equals "$file" device.disconnect.rule REMAIN_ONLINE_WHILE_ANY_DEVICE_LEASE_EXISTS || return 1
    contract_equals "$file" final.device.disconnect.rule RECENTLY_ONLINE || return 1
    contract_equals "$file" device.topology.exposed false || return 1
    contract_equals "$file" device.count.exposed false || return 1
    contract_equals "$file" instance.topology.exposed false || return 1
    contract_equals "$file" device.lease.pattern 'nexo-connect-lab:presence:v1:s:SUBJECT_DIGEST:d:*' || return 1
    contract_equals "$file" device.lease.discovery BOUNDED_INCREMENTAL_SCAN || return 1
    contract_equals "$file" device.lease.raw.subject false || return 1
    contract_equals "$file" device.lease.raw.device false || return 1
    contract_equals "$file" recent.marker.key 'nexo-connect-lab:presence:v1:s:SUBJECT_DIGEST:recent' || return 1
    contract_equals "$file" recent.marker.raw.subject false || return 1
    contract_equals "$file" recent.marker.ttl.seconds 900 || return 1
    contract_equals "$file" lease.ttl.seconds 45 || return 1
    contract_equals "$file" lease.refresh.interval.seconds 15 || return 1
    contract_equals "$file" clock.source REDIS_SERVER_RELATIVE_TTL || return 1
    contract_equals "$file" client.timestamp.trusted false || return 1
    contract_equals "$file" clock.skew.tolerance APPLICATION_CLOCK_INDEPENDENT || return 1
    contract_equals "$file" projection.schema.version 1 || return 1
    contract_equals "$file" projection.frame.type PRESENCE_CHANGED || return 1
    contract_equals "$file" projection.required.fields schemaVersion,frameType,subjectRef,state || return 1
    contract_equals "$file" projection.forbidden.fields \
        deviceRef,deviceCount,sessionRef,connectionRef,instanceRef,lastSeenAt,leaseExpiresAt || return 1
    contract_equals "$file" relationship.allowed SELF,ACTIVE_CONVERSATION_PARTICIPANT || return 1
    contract_equals "$file" relationship.denied.result SILENT_NO_FRAME || return 1
    contract_equals "$file" future.block.hook EXPLICIT_POLICY_PORT || return 1
    contract_equals "$file" future.mute.hook EXPLICIT_POLICY_PORT || return 1
    contract_equals "$file" policy.evaluation.order RELATIONSHIP,BLOCK,MUTE,VISIBILITY,SNAPSHOT || return 1
    contract_equals "$file" policy.evaluation.shape UNIFORM_ALL_PORTS_ONCE || return 1
    contract_equals "$file" denial.result SILENT_NO_FRAME || return 1
    contract_equals "$file" denial.reason.exposed false || return 1
    contract_equals "$file" denial.timing.class UNIFORM || return 1
    contract_equals "$file" visibility.modes SHARE_COARSE,HIDE || return 1
    contract_equals "$file" visibility.hide.result HIDDEN || return 1
    contract_equals "$file" hidden.emission.scope AUTHORISED_RELATION_ONLY || return 1
    contract_equals "$file" privacy.override.precedence BEFORE_COARSE_ACTIVITY_PROJECTION || return 1
    contract_equals "$file" exact.last.seen.exposed false || return 1
    contract_equals "$file" absence.of.frame.means UNKNOWN_NOT_OFFLINE || return 1
    contract_equals "$file" postgres.presence.persistence forbidden || return 1
    contract_equals "$file" durable.history.includes.presence false || return 1
    contract_equals "$file" redis.is.durable.truth false || return 1
    contract_equals "$file" redis.required.for.durable.chat false || return 1
    contract_equals "$file" runtime.redis.probe REAL_ISOLATED_REDIS || return 1
    contract_equals "$file" runtime.multi.device.probe RELEASE_ONE_KEEP_OTHER_ONLINE || return 1
    contract_equals "$file" runtime.postgres.hash PRESERVED || return 1
    contract_equals "$file" runtime.stale.device.key.count 0 || return 1
    contract_equals "$file" nexo.db.direct.access 0 || return 1
    contract_equals "$file" nexo.mutation forbidden || return 1
    contract_equals "$file" next.phase CONNECT.20 || return 1
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail REPOSITORY_IDENTITY_MISMATCH
[[ -f "$DECISION_FILE" && ! -L "$DECISION_FILE" ]] || fail DECISION_MISSING_OR_UNSAFE
[[ -f "$AGGREGATOR_FILE" && -f "$STORE_FILE" ]] || fail IMPLEMENTATION_MISSING
validate_contract "$CONTRACT_FILE" || fail PRESENCE_AGGREGATION_PROPERTIES_MISMATCH

for heading in \
    '## Scope and authority' \
    '## Multi-device aggregation' \
    '## Relative time and clock skew' \
    '## Privacy decision pipeline' \
    '## Topology-free projection' \
    '## Durable isolation' \
    '## Acceptance evidence' \
    '## Phase boundary'; do
    grep -Fqx "$heading" "$DECISION_FILE" || fail "DECISION_SECTION_MISSING:${heading#\#\# }"
done

for source_contract in \
    'class PrivacyAwarePresenceAggregator(' \
    'PresenceProjectionResult.SilentNoFrame' \
    'PresenceVisibilityMode.HIDE -> CoarsePresenceState.HIDDEN' \
    'val blocked = blockPolicy.isBlocked(request)' \
    'val muted = mutePolicy.isMuted(request)' \
    'val activity = snapshotReader.read(request.target)' \
    'setOf("schemaVersion", "frameType", "subjectRef", "state")'; do
    grep -Fq "$source_contract" "$AGGREGATOR_FILE" \
        src/test/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/presence/PrivacyAwarePresenceAggregatorTest.kt ||
        fail APPLICATION_AGGREGATION_CONTRACT_MISSING
done

for redis_contract in \
    'val DEFAULT_RECENTLY_ONLINE_WINDOW: Duration = Duration.ofMinutes(15)' \
    'fun deviceLeasePattern(target: PresenceSubjectTarget)' \
    'fun recentMarker(target: PresenceSubjectTarget)' \
    'current.hasAnyMatchingKey(keyCodec.deviceLeasePattern(target))' \
    'PresenceActivitySnapshot.RECENTLY_ONLINE' \
    'ScanArgs.Builder.matches(pattern).limit(SCAN_LIMIT)'; do
    grep -Fq "$redis_contract" "$STORE_FILE" || fail REDIS_AGGREGATION_CONTRACT_MISSING
done

grep -Fq '+scan +exists' docker/redis/start-ephemeral-redis.sh || fail REDIS_AGGREGATION_ACL_MISSING
[[ "$(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' -print | wc -l | tr -d '[:space:]')" == "5" ]] ||
    fail POSTGRES_MIGRATION_SET_CHANGED
if grep -REin \
    '(^|[^[:alnum:]_])(presence|last_seen|last_seen_at|online_status)([^[:alnum:]_]|$)' \
    "$MIGRATION_DIR"; then
    fail PRESENCE_DURABLE_MIGRATION_DETECTED
fi

awk '$0 == "aggregation.online.rule=ANY_ACTIVE_DEVICE_LEASE" { print "aggregation.online.rule=LATEST_DEVICE_ONLY"; next } { print }' \
    "$CONTRACT_FILE" > "$AGGREGATION_MUTATION"
validate_contract "$AGGREGATION_MUTATION" && fail SINGLE_DEVICE_AGGREGATION_MUTATION_ACCEPTED
awk '$0 == "device.topology.exposed=false" { print "device.topology.exposed=true"; next } { print }' \
    "$CONTRACT_FILE" > "$TOPOLOGY_MUTATION"
validate_contract "$TOPOLOGY_MUTATION" && fail TOPOLOGY_DISCLOSURE_MUTATION_ACCEPTED
awk '$0 == "client.timestamp.trusted=false" { print "client.timestamp.trusted=true"; next } { print }' \
    "$CONTRACT_FILE" > "$CLOCK_MUTATION"
validate_contract "$CLOCK_MUTATION" && fail CLIENT_CLOCK_TRUST_MUTATION_ACCEPTED
awk '$0 == "denial.result=SILENT_NO_FRAME" { print "denial.result=TARGET_NOT_FOUND"; next } { print }' \
    "$CONTRACT_FILE" > "$DENIAL_MUTATION"
validate_contract "$DENIAL_MUTATION" && fail DISCLOSING_DENIAL_MUTATION_ACCEPTED

./gradlew --no-daemon presenceAggregationTest --rerun-tasks --console=plain

printf 'MULTI_DEVICE_PRESENCE_AGGREGATION=PASS\n'
printf 'SINGLE_DEVICE_DISCONNECT_REMAINS_ONLINE=PASS\n'
printf 'PRESENCE_VISIBILITY_DENIAL_UNIFORM=PASS\n'
printf 'PRESENCE_DEVICE_TOPOLOGY_EXPOSED=0\n'
printf 'PRESENCE_CLOCK_SKEW_TOLERANCE=PASS\n'
printf 'PRESENCE_MUTABLE_POSTGRES_WRITES=0\n'
printf 'PRESENCE_AGGREGATION_MUTATIONS_REJECTED=PASS\n'
printf 'NEXO_DB_DIRECT_ACCESS=0\n'
printf 'PRESENCE_AGGREGATION_CONTRACT=PASS\n'
