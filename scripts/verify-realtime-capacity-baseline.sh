#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${CONNECT_LAB_ENV_FILE:-${PROJECT_DIR}/.env}"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"
REPORT_PATH="${PROJECT_DIR}/build/reports/realtime-capacity/connect-10-single-instance.properties"
STATS_FILE="$(mktemp "${TMPDIR:-/tmp}/connect-10-stats.XXXXXX")"
SAMPLER_PID=""

read_env_value() {
    local key="$1"
    awk -F= -v key="$key" '
        $1 == key {
            print substr($0, index($0, "=") + 1)
            found = 1
            exit
        }
        END { if (!found) exit 1 }
    ' "$ENV_FILE"
}

property_value() {
    local key="$1"
    awk -F= -v key="$key" '
        $1 == key {
            count++
            value = substr($0, index($0, "=") + 1)
        }
        END {
            if (count != 1) exit 2
            print value
        }
    ' "$REPORT_PATH"
}

fail() {
    printf 'REALTIME_CAPACITY_BASELINE=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

if [[ ! -f "$ENV_FILE" || -L "$ENV_FILE" || ! -f "$COMPOSE_FILE" ]]; then
    fail "CAPACITY_LOCAL_CONTRACT_MISSING"
fi

HTTP_HOST_PORT="$(read_env_value CONNECT_LAB_HTTP_HOST_PORT)"
POSTGRES_USER="$(read_env_value CONNECT_LAB_POSTGRES_USER)"
POSTGRES_PASSWORD="$(read_env_value CONNECT_LAB_POSTGRES_PASSWORD)"
DATABASE_NAME="$(read_env_value CONNECT_LAB_DATABASE_NAME)"
BUSINESS_TOKEN="$(read_env_value CONNECT_LAB_SYNTHETIC_BUSINESS_TOKEN)"

if [[ ! "$HTTP_HOST_PORT" =~ ^[0-9]+$ ]] ||
    [[ ! "$POSTGRES_USER" =~ ^[a-z0-9_]+$ ]] ||
    [[ ! "$DATABASE_NAME" =~ ^[a-z0-9_]+$ ]] ||
    [[ -z "$POSTGRES_PASSWORD" ]] ||
    [[ ${#BUSINESS_TOKEN} -lt 32 ]]; then
    fail "CAPACITY_ENV_INVALID"
fi

compose() {
    docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

admin_psql() {
    compose exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
        psql --set ON_ERROR_STOP=1 --quiet --username "$POSTGRES_USER" --dbname "$DATABASE_NAME" "$@"
}

cleanup_seed() {
    admin_psql >/dev/null <<'SQL'
DELETE FROM connect.conversation_receipt_cursors
WHERE conversation_ref = 'connect-10-capacity';
DELETE FROM connect.message_identities
WHERE conversation_ref = 'connect-10-capacity';
DELETE FROM connect.messages
WHERE conversation_ref = 'connect-10-capacity';
DELETE FROM connect.business_client_conversation_keys
WHERE conversation_ref = 'connect-10-capacity';
DELETE FROM connect.conversation_participants
WHERE conversation_ref = 'connect-10-capacity';
DELETE FROM connect.conversations
WHERE conversation_ref = 'connect-10-capacity';
SQL
}

finish() {
    local status=$?
    trap - EXIT
    set +e
    if [[ -n "$SAMPLER_PID" ]]; then
        kill "$SAMPLER_PID" >/dev/null 2>&1 || true
        wait "$SAMPLER_PID" >/dev/null 2>&1 || true
    fi
    if [[ -n "$(compose ps -q postgres 2>/dev/null)" ]]; then
        cleanup_seed
        if [[ $? -ne 0 ]]; then
            printf 'REALTIME_CAPACITY_SEED_CLEANUP=FAIL\n' >&2
            status=1
        fi
    fi
    /bin/rm -f "$STATS_FILE"
    unset POSTGRES_PASSWORD BUSINESS_TOKEN
    exit "$status"
}

trap finish EXIT

APP_ID="$(compose ps -q app)"
if [[ -z "$APP_ID" || -z "$(compose ps -q postgres)" ]]; then
    fail "CAPACITY_RUNTIME_NOT_STARTED"
fi

cleanup_seed
admin_psql >/dev/null <<'SQL'
INSERT INTO connect.conversations (
    conversation_ref, conversation_type, platform_scope_ref,
    organization_scope_ref, business_scope_ref, status,
    created_at, last_activity_at, last_message_sequence, version, schema_version
) VALUES (
    'connect-10-capacity', 'BUSINESS_CLIENT', 'synthetic-platform-c1',
    'synthetic-organization-c1', 'synthetic-business-scope-c1', 'ACTIVE',
    '2026-08-13T16:30:00Z', '2026-08-13T16:30:00Z', 0, 0, 1
);

INSERT INTO connect.conversation_participants (
    conversation_ref, subject_ref, actor_type, status, capabilities, joined_at, left_at
) VALUES
    ('connect-10-capacity', 'synthetic-business-c1', 'BUSINESS', 'ACTIVE', ARRAY['SEND_TEXT']::text[], '2026-08-13T16:30:00Z', NULL),
    ('connect-10-capacity', 'synthetic-client-c1', 'CLIENT', 'ACTIVE', ARRAY['SEND_TEXT']::text[], '2026-08-13T16:30:00Z', NULL);

INSERT INTO connect.business_client_conversation_keys (
    platform_scope_ref, organization_scope_ref, business_scope_ref,
    business_subject_ref, client_subject_ref, conversation_ref
) VALUES (
    'synthetic-platform-c1', 'synthetic-organization-c1', 'synthetic-business-scope-c1',
    'synthetic-business-c1', 'synthetic-client-c1', 'connect-10-capacity'
);
SQL
printf 'REALTIME_CAPACITY_SEED=PASS\n'

/bin/rm -f "$REPORT_PATH"
(
    while true; do
        docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}' "$APP_ID" >>"$STATS_FILE" 2>/dev/null || true
        sleep 0.25
    done
) &
SAMPLER_PID=$!

(
    cd "$PROJECT_DIR"
    CONNECT_LAB_CONNECT_10_RUNTIME_URL="ws://127.0.0.1:${HTTP_HOST_PORT}/v1/realtime" \
    CONNECT_LAB_CONNECT_10_RUNTIME_HTTP_URL="http://127.0.0.1:${HTTP_HOST_PORT}" \
    CONNECT_LAB_CONNECT_10_BUSINESS_TOKEN="$BUSINESS_TOKEN" \
    CONNECT_LAB_CONNECT_10_CONVERSATION_REF="connect-10-capacity" \
    CONNECT_LAB_CONNECT_10_REPORT_PATH="$REPORT_PATH" \
        ./gradlew --no-daemon realtimeCapacityBaselineTest --rerun-tasks --console=plain
)

kill "$SAMPLER_PID" >/dev/null 2>&1 || true
wait "$SAMPLER_PID" >/dev/null 2>&1 || true
SAMPLER_PID=""

[[ -s "$REPORT_PATH" && ! -L "$REPORT_PATH" ]] || fail "CAPACITY_REPORT_MISSING"

STATS_SAMPLE_COUNT="$(awk 'NF { count++ } END { print count + 0 }' "$STATS_FILE")"
APP_CPU_PEAK="$(awk -F'|' '
    {
        gsub(/%/, "", $1)
        if (($1 + 0) > peak) peak = $1 + 0
    }
    END { printf "%.2f", peak + 0 }
' "$STATS_FILE")"
APP_MEMORY_LAST="$(awk -F'|' '
    NF { value = $2 }
    END {
        gsub(/[[:space:]]/, "", value)
        print value
    }
' "$STATS_FILE")"

[[ "$STATS_SAMPLE_COUNT" -gt 0 && -n "$APP_MEMORY_LAST" ]] || fail "CAPACITY_RESOURCE_SAMPLE_MISSING"
{
    printf 'app.container.resource.samples=%s\n' "$STATS_SAMPLE_COUNT"
    printf 'app.container.cpu.peak_observed.percent=%s\n' "$APP_CPU_PEAK"
    printf 'app.container.memory.last_observed=%s\n' "$APP_MEMORY_LAST"
} >>"$REPORT_PATH"

required_report_properties=(
    report.schema.version
    programme
    phase
    scope
    claim
    baseline.connections
    baseline.connection.p50.micros
    baseline.connection.p95.micros
    baseline.connection.p99.micros
    pressure.connections
    pressure.slow_reader.connections
    pressure.slow_reader.delay.millis
    pressure.connection.p50.micros
    pressure.connection.p95.micros
    pressure.connection.p99.micros
    degradation.point.connections
    degradation.controlled
    durable.messages.expected
    durable.messages.live_observed
    durable.messages.catch_up_observed
    durable.loss
    send_live.p50.micros
    send_live.p95.micros
    send_live.p99.micros
    catch_up.p50.micros
    catch_up.p95.micros
    catch_up.p99.micros
    harness.jvm.memory.before.bytes
    harness.jvm.memory.after.bytes
    app.container.resource.samples
    app.container.cpu.peak_observed.percent
    app.container.memory.last_observed
)

for property in "${required_report_properties[@]}"; do
    property_value "$property" >/dev/null || fail "CAPACITY_REPORT_PROPERTY_INVALID:${property}"
done

[[ "$(property_value report.schema.version)" == "1" ]] || fail "CAPACITY_REPORT_SCHEMA_MISMATCH"
[[ "$(property_value programme)" == "NEXO_CONNECT_LAB" ]] || fail "CAPACITY_REPORT_PROGRAMME_MISMATCH"
[[ "$(property_value phase)" == "CONNECT.10" ]] || fail "CAPACITY_REPORT_PHASE_MISMATCH"
[[ "$(property_value scope)" == "SINGLE_APPLICATION_INSTANCE" ]] || fail "CAPACITY_REPORT_SCOPE_MISMATCH"
[[ "$(property_value claim)" == "BOUNDED_LOCAL_BASELINE_NOT_PRODUCTION_CAPACITY" ]] ||
    fail "CAPACITY_REPORT_CLAIM_MISMATCH"
[[ "$(property_value baseline.connections)" == "4" ]] || fail "CAPACITY_BASELINE_TIER_MISMATCH"
[[ "$(property_value pressure.connections)" == "16" ]] || fail "CAPACITY_PRESSURE_TIER_MISMATCH"
[[ "$(property_value pressure.slow_reader.connections)" == "4" ]] || fail "CAPACITY_SLOW_MIX_MISMATCH"
[[ "$(property_value pressure.slow_reader.delay.millis)" == "750" ]] || fail "CAPACITY_SLOW_DELAY_MISMATCH"
[[ "$(property_value degradation.point.connections)" == "16" ]] || fail "CAPACITY_DEGRADATION_POINT_MISMATCH"
[[ "$(property_value degradation.controlled)" == "true" ]] || fail "CAPACITY_DEGRADATION_NOT_CONTROLLED"
[[ "$(property_value durable.messages.expected)" == "12" ]] || fail "CAPACITY_DURABLE_EXPECTATION_MISMATCH"
[[ "$(property_value durable.messages.live_observed)" == "12" ]] || fail "CAPACITY_LIVE_OBSERVATION_MISMATCH"
[[ "$(property_value durable.messages.catch_up_observed)" == "12" ]] || fail "CAPACITY_CATCH_UP_OBSERVATION_MISMATCH"
[[ "$(property_value durable.loss)" == "0" ]] || fail "CAPACITY_DURABLE_LOSS"

if grep -Ein '(authorization|bearer|token|password|message.body|subject.ref|/Users/|/home/)' "$REPORT_PATH"; then
    fail "CAPACITY_REPORT_SENSITIVE_FIELD_PRESENT"
fi

MESSAGE_COUNT="$(admin_psql --tuples-only --no-align --command \
    "SELECT count(*) FROM connect.messages WHERE conversation_ref = 'connect-10-capacity';")"
IDENTITY_COUNT="$(admin_psql --tuples-only --no-align --command \
    "SELECT count(*) FROM connect.message_identities WHERE conversation_ref = 'connect-10-capacity';")"
LAST_SEQUENCE="$(admin_psql --tuples-only --no-align --command \
    "SELECT last_message_sequence FROM connect.conversations WHERE conversation_ref = 'connect-10-capacity';")"

if [[ "$MESSAGE_COUNT" != "12" || "$IDENTITY_COUNT" != "12" || "$LAST_SEQUENCE" != "12" ]]; then
    fail "CAPACITY_POSTGRES_DURABILITY_ORACLE_MISMATCH"
fi

printf 'REALTIME_CAPACITY_CONNECTION_PERCENTILES=PASS\n'
printf 'REALTIME_CAPACITY_SEND_PERCENTILES=PASS\n'
printf 'REALTIME_CAPACITY_CATCH_UP_PERCENTILES=PASS\n'
printf 'REALTIME_CAPACITY_SLOW_READER_MIX=PASS\n'
printf 'REALTIME_CAPACITY_DEGRADATION_POINT=MEASURED\n'
printf 'REALTIME_CAPACITY_DURABLE_LOSS=0\n'
printf 'REALTIME_CAPACITY_RESOURCE_SAMPLING=PASS\n'
printf 'REALTIME_CAPACITY_CLAIM=BOUNDED_LOCAL_BASELINE_NOT_PRODUCTION_CAPACITY\n'
printf 'REALTIME_CAPACITY_BASELINE=PASS\n'
