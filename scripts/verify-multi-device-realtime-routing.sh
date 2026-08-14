#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CONTRACT_FILE="${PROJECT_DIR}/docs/architecture/connect-multi-device-routing.properties"
REGISTRY_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/EphemeralRealtimeConnectionRegistry.kt"
HUB_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/application/realtime/AuthorizedConversationEventHub.kt"
ROUTES_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/backend/routes/AuthenticatedRealtimeRoutes.kt"
PROTOCOL_FILE="${PROJECT_DIR}/src/main/kotlin/com/premierdarkcoffee/nexo/connect/lab/domain/realtime/RealtimeProtocol.kt"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-14-routing.XXXXXX")"
MUTATION_FILE="${TEMP_DIR}/mutation.properties"

fail() {
    printf 'MULTI_DEVICE_REALTIME_ROUTING=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

cleanup() {
    local status=$?
    trap - EXIT
    [[ ! -e "$MUTATION_FILE" && ! -L "$MUTATION_FILE" ]] || unlink "$MUTATION_FILE"
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

require_property() {
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
    [[ "$(awk 'NF && $0 !~ /^#/ { count++ } END { print count + 0 }' "$file")" == "32" ]] || return 1
    require_property "$file" contract.version 1 || return 1
    require_property "$file" programme NEXO_CONNECT_LAB || return 1
    require_property "$file" phase CONNECT.14 || return 1
    require_property "$file" status IMPLEMENTED || return 1
    require_property "$file" routing.scope MULTI_APPLICATION_INSTANCE_MULTI_DEVICE || return 1
    require_property "$file" registry.storage INSTANCE_LOCAL_EPHEMERAL_MEMORY || return 1
    require_property "$file" registry.ttl.seconds 90 || return 1
    require_property "$file" registry.refresh.seconds 30 || return 1
    require_property "$file" registry.capacity 10000 || return 1
    require_property "$file" connection.ref.entropy.bits 192 || return 1
    require_property "$file" device.ref.entropy.bits 192 || return 1
    require_property "$file" session.ref.entropy.bits 192 || return 1
    require_property "$file" refs.encoding BASE64URL_WITHOUT_PADDING || return 1
    require_property "$file" refs.client.supplied false || return 1
    require_property "$file" refs.authorization.authority false || return 1
    require_property "$file" refs.exposed.on.auth.ok true || return 1
    require_property "$file" client.target.by.routing.ref false || return 1
    require_property "$file" multi.device.same.subject true || return 1
    require_property "$file" sticky.sessions.required false || return 1
    require_property "$file" cross.instance.transport VERSIONED_REDIS_PUB_SUB || return 1
    require_property "$file" destination.routing LOCAL_AUTHORISED_SUBSCRIPTION_REGISTRY || return 1
    require_property "$file" destination.reauthorisation true || return 1
    require_property "$file" receipt.origin.exclusion EXACT_CONNECTION_DEVICE_SESSION_TUPLE || return 1
    require_property "$file" receipt.other.devices true || return 1
    require_property "$file" stale.cleanup IMMEDIATE_CLOSE_PLUS_TTL_FALLBACK || return 1
    require_property "$file" redis.loss.durable.impact 0 || return 1
    require_property "$file" durable.truth POSTGRESQL || return 1
    require_property "$file" cross.conversation.leak 0 || return 1
    require_property "$file" id.guessing.authorization.gain 0 || return 1
    require_property "$file" nexo.db.direct.access 0 || return 1
    require_property "$file" nexo.mutation forbidden || return 1
    require_property "$file" next.phase CONNECT.15 || return 1
}

cd "$PROJECT_DIR"

[[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$PROJECT_DIR" ]] ||
    fail REPOSITORY_IDENTITY_MISMATCH
for file in "$CONTRACT_FILE" "$REGISTRY_FILE" "$HUB_FILE" "$ROUTES_FILE" "$PROTOCOL_FILE"; do
    [[ -f "$file" && ! -L "$file" ]] || fail REQUIRED_ROUTING_FILE_MISSING_OR_UNSAFE
done
validate_contract "$CONTRACT_FILE" || fail MULTI_DEVICE_ROUTING_CONTRACT_MISMATCH

grep -Fq 'SecureRandom' "$REGISTRY_FILE" || fail SECURE_OPAQUE_REFERENCE_FACTORY_MISSING
grep -Fq 'ENTROPY_BYTES = 24' "$REGISTRY_FILE" || fail ROUTING_REFERENCE_ENTROPY_MISMATCH
grep -Fq 'DEFAULT_TTL: Duration = Duration.ofSeconds(90)' "$REGISTRY_FILE" || fail REGISTRY_TTL_MISMATCH
grep -Fq 'REFRESH_INTERVAL: Duration = Duration.ofSeconds(30)' "$REGISTRY_FILE" || fail REGISTRY_REFRESH_MISMATCH
grep -Fq 'sameRegistration(connection.registration, excludedRegistration)' "$REGISTRY_FILE" ||
    fail EXACT_ORIGIN_EXCLUSION_MISSING
grep -Fq 'connectionRegistry.candidates' "$HUB_FILE" || fail LOCAL_ROUTE_RESOLUTION_MISSING
grep -Fq 'conversationEventHub.touch(registration)' "$ROUTES_FILE" || fail ROUTING_LEASE_REFRESH_MISSING
grep -Fq 'RealtimeRoutingRefs(' "$ROUTES_FILE" || fail AUTH_OK_ROUTING_REFS_MISSING
grep -Fq 'val routing: RealtimeRoutingRefs? = null' "$PROTOCOL_FILE" || fail ROUTING_PROTOCOL_FIELD_MISSING

client_frame="$(awk '/data class ClientRealtimeFrame\(/,/^\)/' "$PROTOCOL_FILE")"
if grep -Eiq '(token|credential|password|message.?body)' "$REGISTRY_FILE" ||
    grep -Eq 'connectionRef|deviceRef|sessionRef' <<<"$client_frame"; then
    fail ROUTING_REGISTRY_SECRET_OR_CLIENT_TARGET_BOUNDARY_VIOLATION
fi

awk '$0 == "id.guessing.authorization.gain=0" { print "id.guessing.authorization.gain=1"; next } { print }' \
    "$CONTRACT_FILE" > "$MUTATION_FILE"
if validate_contract "$MUTATION_FILE"; then
    fail ID_GUESSING_MUTATION_ACCEPTED
fi

./gradlew --no-daemon multiDeviceRealtimeRoutingTest --rerun-tasks --console=plain

printf 'OPAQUE_CONNECTION_DEVICE_SESSION_REFS=PASS\n'
printf 'BOUNDED_TTL_CONNECTION_REGISTRY=PASS\n'
printf 'SAME_SUBJECT_MULTI_DEVICE_ROUTING=PASS\n'
printf 'EXACT_ORIGIN_EXCLUSION=PASS\n'
printf 'OTHER_DEVICE_RECEIPT_PROPAGATION=PASS\n'
printf 'STICKY_SESSIONS_REQUIRED=FALSE\n'
printf 'ID_GUESSING_AUTHORIZATION_GAIN=0\n'
printf 'CROSS_CONVERSATION_LEAK=0\n'
printf 'MULTI_DEVICE_REALTIME_ROUTING=PASS\n'
