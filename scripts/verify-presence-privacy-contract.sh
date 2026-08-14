#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-presence-privacy-contract.properties"
DECISION_FILE="${PROJECT_DIR}/docs/architecture/CONNECT_16_PRESENCE_PRIVACY_CONTRACT.md"
MIGRATION_DIR="${PROJECT_DIR}/src/main/resources/db/migration"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-16-presence.XXXXXX")"
UNAUTHORISED_MUTATION="${TEMP_DIR}/unauthorised.properties"
LAST_SEEN_MUTATION="${TEMP_DIR}/last-seen.properties"
DURABLE_MUTATION="${TEMP_DIR}/durable.properties"
HIDDEN_SCOPE_MUTATION="${TEMP_DIR}/hidden-scope.properties"

fail() {
    printf 'PRESENCE_PRIVACY_CONTRACT=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

unlink_if_present() {
    local target="$1"
    if [[ -e "$target" || -L "$target" ]]; then
        unlink "$target"
    fi
}

cleanup() {
    local status=$?
    trap - EXIT
    unlink_if_present "$UNAUTHORISED_MUTATION"
    unlink_if_present "$LAST_SEEN_MUTATION"
    unlink_if_present "$DURABLE_MUTATION"
    unlink_if_present "$HIDDEN_SCOPE_MUTATION"
    rmdir "$TEMP_DIR" 2>/dev/null || status=1
    exit "$status"
}

trap cleanup EXIT

property_value() {
    local file="$1"
    local key="$2"
    awk -F= -v target="$key" '
        $1 == target {
            count++
            value = substr($0, index($0, "=") + 1)
        }
        END {
            if (count != 1) exit 2
            print value
        }
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
    local property_count

    [[ -f "$file" && ! -L "$file" ]] || return 1
    property_count="$(awk 'NF && $0 !~ /^#/ { count++ } END { print count + 0 }' "$file")"
    [[ "$property_count" == "54" ]] || return 1

    contract_equals "$file" contract.version 1 || return 1
    contract_equals "$file" programme NEXO_CONNECT_LAB || return 1
    contract_equals "$file" phase CONNECT.16 || return 1
    contract_equals "$file" status FROZEN || return 1
    contract_equals "$file" presence.role EPHEMERAL_AUTHORISED_PROJECTION_ONLY || return 1
    contract_equals "$file" presence.states ONLINE,RECENTLY_ONLINE,OFFLINE,HIDDEN || return 1
    contract_equals "$file" presence.default.interpretation UNKNOWN || return 1
    contract_equals "$file" absence.of.frame.means UNKNOWN_NOT_OFFLINE || return 1
    contract_equals "$file" authorised.relations SELF,ACTIVE_CONVERSATION_PARTICIPANT || return 1
    contract_equals "$file" unauthorised.relations NO_ACTIVE_RELATION,BLOCKED,UNKNOWN_SUBJECT || return 1
    contract_equals "$file" decision.order \
        AUTHENTICATE,AUTHORISE_ACTIVE_RELATION,APPLY_BLOCKS,APPLY_SUBJECT_VISIBILITY,PROJECT_COARSE_STATE || return 1
    contract_equals "$file" subject.visibility.modes SHARE_COARSE,HIDE || return 1
    contract_equals "$file" share.coarse.allowed.states ONLINE,RECENTLY_ONLINE,OFFLINE || return 1
    contract_equals "$file" hide.authorised.result HIDDEN || return 1
    contract_equals "$file" hidden.emission.scope AUTHORISED_RELATION_ONLY || return 1
    contract_equals "$file" unauthorised.behaviour SILENT_NO_FRAME || return 1
    contract_equals "$file" unauthorised.observable.target.information 0 || return 1
    contract_equals "$file" blocked.behaviour SILENT_NO_FRAME || return 1
    contract_equals "$file" unknown.subject.behaviour SILENT_NO_FRAME || return 1
    contract_equals "$file" no.active.relation.behaviour SILENT_NO_FRAME || return 1
    contract_equals "$file" denial.timing.class UNIFORM || return 1
    contract_equals "$file" online.source ACTIVE_UNEXPIRED_LEASE || return 1
    contract_equals "$file" recently.online.source EXPIRED_LEASE_WITHIN_COARSE_WINDOW || return 1
    contract_equals "$file" recently.online.window.seconds 900 || return 1
    contract_equals "$file" offline.source AUTHORISED_NO_CURRENT_OR_RECENT_LEASE || return 1
    contract_equals "$file" last.seen.exact.default false || return 1
    contract_equals "$file" last.seen.exact.exposed false || return 1
    contract_equals "$file" device.topology.exposed false || return 1
    contract_equals "$file" instance.topology.exposed false || return 1
    contract_equals "$file" client.frame.schema.major 1 || return 1
    contract_equals "$file" client.frame.type PRESENCE_SUBSCRIBE || return 1
    contract_equals "$file" client.frame.required.fields schemaVersion,frameType,subjectRef || return 1
    contract_equals "$file" server.frame.schema.major 1 || return 1
    contract_equals "$file" server.frame.type PRESENCE_CHANGED || return 1
    contract_equals "$file" server.frame.required.fields schemaVersion,frameType,subjectRef,state || return 1
    contract_equals "$file" server.frame.forbidden.fields \
        lastSeenAt,lastActiveAt,offlineAt,deviceRef,sessionRef,connectionRef,instanceRef,ipAddress,leaseExpiresAt || return 1
    contract_equals "$file" frame.subject.ref OPAQUE_NON_AUTHORITY || return 1
    contract_equals "$file" frame.delivery.precondition AUTHORISED_RELATION || return 1
    contract_equals "$file" unknown.major.behaviour REJECT_AND_MEASURE || return 1
    contract_equals "$file" reconnect.behaviour REAUTHORISE_AND_RECOMPUTE_CURRENT_VISIBLE_STATE || return 1
    contract_equals "$file" replay.required false || return 1
    contract_equals "$file" postgres.presence.persistence forbidden || return 1
    contract_equals "$file" durable.message.history.includes.presence false || return 1
    contract_equals "$file" durable.receipt.history.includes.presence false || return 1
    contract_equals "$file" durable.outbox.includes.presence false || return 1
    contract_equals "$file" redis.presence.role BOUNDED_TTL_LEASE_ONLY || return 1
    contract_equals "$file" redis.persistence.required false || return 1
    contract_equals "$file" metrics.identifiers forbidden || return 1
    contract_equals "$file" nexo.db.direct.access 0 || return 1
    contract_equals "$file" nexo.mutation forbidden || return 1
    contract_equals "$file" implementation.first.phase CONNECT.17 || return 1
    contract_equals "$file" multi.device.aggregation.phase CONNECT.19 || return 1
    contract_equals "$file" failure.injection.phase CONNECT.20 || return 1
    contract_equals "$file" next.phase CONNECT.17 || return 1
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail "REPOSITORY_IDENTITY_MISMATCH"
[[ -f "$DECISION_FILE" && ! -L "$DECISION_FILE" ]] || fail "DECISION_MISSING_OR_UNSAFE"
validate_contract "$CONTRACT_FILE" || fail "FROZEN_CONTRACT_MISMATCH"

for heading in \
    '## Scope and authority' \
    '## State model' \
    '## Relationship policy' \
    '## Privacy decision order' \
    '## Versioned frames' \
    '## Ephemeral lifecycle' \
    '## Security and non-disclosure' \
    '## Phase boundaries'; do
    grep -Fqx "$heading" "$DECISION_FILE" || fail "DECISION_SECTION_MISSING:${heading#\#\# }"
done

for statement in \
    'The frozen public state set is `ONLINE|RECENTLY_ONLINE|OFFLINE|HIDDEN`.' \
    '`SILENT_NO_FRAME`. They share the same externally observable result and timing' \
    'The v1 client request is `PRESENCE_SUBSCRIBE` with exactly' \
    'The v1 server notification is' \
    'Presence frames are best-effort and have no replay contract.' \
    'An unauthorised actor learns zero target-specific presence information.' \
    '- CONNECT.17 implements bounded Redis presence leases.'; do
    grep -Fq -- "$statement" "$DECISION_FILE" || fail "DECISION_STATEMENT_MISSING"
done

if grep -REin \
    '(^|[^[:alnum:]_])(presence|last_seen|last_seen_at|last_online|last_online_at|online_status)([^[:alnum:]_]|$)' \
    "$MIGRATION_DIR"; then
    fail "PRESENCE_DURABLE_MIGRATION_DETECTED"
fi

if grep -REin \
    '(class|interface|data[[:space:]]+class|object)[[:space:]]+[A-Za-z0-9_]*(Presence|LastSeen)|[A-Za-z0-9_]*(Presence|LastSeen)(Repository|Record|Entity)' \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/persistence \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/infrastructure/persistence \
    src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/persistence; then
    fail "PRESENCE_DURABLE_REPOSITORY_DETECTED"
fi

awk '
    $0 == "unauthorised.behaviour=SILENT_NO_FRAME" { print "unauthorised.behaviour=HIDDEN"; next }
    { print }
' "$CONTRACT_FILE" > "$UNAUTHORISED_MUTATION"
if validate_contract "$UNAUTHORISED_MUTATION"; then
    fail "UNAUTHORISED_DISCLOSURE_MUTATION_ACCEPTED"
fi

awk '
    $0 == "last.seen.exact.default=false" { print "last.seen.exact.default=true"; next }
    { print }
' "$CONTRACT_FILE" > "$LAST_SEEN_MUTATION"
if validate_contract "$LAST_SEEN_MUTATION"; then
    fail "EXACT_LAST_SEEN_MUTATION_ACCEPTED"
fi

awk '
    $0 == "postgres.presence.persistence=forbidden" { print "postgres.presence.persistence=allowed"; next }
    { print }
' "$CONTRACT_FILE" > "$DURABLE_MUTATION"
if validate_contract "$DURABLE_MUTATION"; then
    fail "DURABLE_PRESENCE_MUTATION_ACCEPTED"
fi

awk '
    $0 == "hidden.emission.scope=AUTHORISED_RELATION_ONLY" { print "hidden.emission.scope=ALL_REQUESTERS"; next }
    { print }
' "$CONTRACT_FILE" > "$HIDDEN_SCOPE_MUTATION"
if validate_contract "$HIDDEN_SCOPE_MUTATION"; then
    fail "HIDDEN_ENUMERATION_MUTATION_ACCEPTED"
fi

printf 'PRESENCE_STATE_MODEL=PASS\n'
printf 'RELATIONSHIP_POLICY=PASS\n'
printf 'UNAUTHORISED_TARGET_INFORMATION=0\n'
printf 'EXACT_LAST_SEEN_DEFAULT=FALSE\n'
printf 'VERSIONED_PRESENCE_FRAMES=PASS\n'
printf 'PRESENCE_DURABLE_HISTORY=0\n'
printf 'PRESENCE_PRIVACY_MUTATIONS_REJECTED=PASS\n'
printf 'NEXO_DB_DIRECT_ACCESS=0\n'
printf 'PRESENCE_PRIVACY_CONTRACT=PASS\n'
