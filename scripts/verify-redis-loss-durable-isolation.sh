#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${CONNECT_LAB_ENV_FILE:-${PROJECT_DIR}/.env}"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nexo-connect-12-redis-loss.XXXXXX")"

cleanup() {
    local status=$?
    trap - EXIT
    /bin/rm -f "$TEMP_DIR/body" "$TEMP_DIR/headers"
    rmdir "$TEMP_DIR" 2>/dev/null || status=1
    exit "$status"
}
trap cleanup EXIT

fail() {
    printf 'REDIS_LOSS_DURABLE_ISOLATION=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

read_env_value() {
    local key="$1"
    awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); count++ } END { if (count != 1) exit 1 }' "$ENV_FILE"
}

compose() {
    docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

durable_state_hash() {
    compose exec -T postgres pg_dump \
        --data-only --no-owner --no-privileges --rows-per-insert=1 \
        --restrict-key=NexoConnectLabDurableIsolationV1 \
        --schema=connect \
        -U "$POSTGRES_USER" -d "$DATABASE_NAME" |
        LC_ALL=C shasum -a 256 |
        awk '{print $1}'
}

http_probe() {
    local path="$1"
    curl --silent --show-error --max-time 4 \
        --dump-header "$TEMP_DIR/headers" \
        --output "$TEMP_DIR/body" \
        --write-out '%{http_code}' \
        "http://127.0.0.1:${HTTP_HOST_PORT}${path}" 2>/dev/null || true
}

[[ -f "$ENV_FILE" && -f "$COMPOSE_FILE" ]] || fail LOCAL_RUNTIME_CONTRACT_MISSING

HTTP_HOST_PORT="$(read_env_value CONNECT_LAB_HTTP_HOST_PORT)"
POSTGRES_USER="$(read_env_value CONNECT_LAB_POSTGRES_USER)"
DATABASE_NAME="$(read_env_value CONNECT_LAB_DATABASE_NAME)"
[[ "$HTTP_HOST_PORT" =~ ^[0-9]+$ ]] || fail HTTP_PORT_INVALID
[[ "$POSTGRES_USER" =~ ^[a-z0-9_]+$ ]] || fail POSTGRES_USER_INVALID
[[ "$DATABASE_NAME" =~ ^[a-z0-9_]+$ ]] || fail DATABASE_NAME_INVALID

app_id="$(compose ps -q app)"
redis_id="$(compose ps -q redis)"
[[ -n "$app_id" && -n "$redis_id" ]] || fail EXPECTED_RUNTIME_NOT_STARTED

if [[ "$(compose exec -T redis sh -ec 'REDISCLI_AUTH="$CONNECT_LAB_REDIS_APP_PASSWORD" redis-cli --no-auth-warning --user "$CONNECT_LAB_REDIS_APP_USER" ping' | tr -d '[:space:]')" != PONG ]]; then
    fail DEDICATED_REDIS_AUTH_FAILED
fi

acl_denial="$(compose exec -T redis sh -ec 'REDISCLI_AUTH="$CONNECT_LAB_REDIS_APP_PASSWORD" redis-cli --no-auth-warning --user "$CONNECT_LAB_REDIS_APP_USER" CONFIG GET appendonly' 2>&1 || true)"
grep -Fq 'NOPERM' <<<"$acl_denial" || fail APP_IDENTITY_HAS_ADMIN_AUTHORITY
unset acl_denial

redis_persistence="$(compose exec -T redis sh -ec 'REDISCLI_AUTH="$CONNECT_LAB_REDIS_PASSWORD" redis-cli --no-auth-warning CONFIG GET appendonly save' | tr '\n' ' ')"
grep -Fq 'appendonly no' <<<"$redis_persistence" || fail REDIS_APPENDONLY_NOT_DISABLED

ready_code="$(http_probe /health/ready/ephemeral-redis)"
[[ "$ready_code" == 200 && "$(tr -d '\r\n' <"$TEMP_DIR/body")" == REDIS_READY ]] ||
    fail INITIAL_REDIS_READINESS_FAILED

before_hash="$(durable_state_hash)"
control_hash="$(durable_state_hash)"
[[ "$control_hash" == "$before_hash" ]] || fail POSTGRES_DURABLE_HASH_NOT_REPRODUCIBLE
compose stop --timeout 15 redis >/dev/null

degraded=0
for _attempt in $(seq 1 30); do
    durable_code="$(http_probe /health/ready)"
    durable_body="$(tr -d '\r\n' <"$TEMP_DIR/body")"
    redis_header="$(awk 'BEGIN { IGNORECASE=1 } /^X-Nexo-Connect-Redis-Readiness:/ { gsub(/\r/, ""); print $2 }' "$TEMP_DIR/headers")"
    if [[ "$durable_code" == 200 && "$durable_body" == READY && "$redis_header" == DEGRADED ]]; then
        degraded=1
        break
    fi
    sleep 1
done
[[ "$degraded" == 1 ]] || fail DURABLE_READINESS_DID_NOT_SURVIVE_REDIS_LOSS

redis_code="$(http_probe /health/ready/ephemeral-redis)"
[[ "$redis_code" == 503 && "$(tr -d '\r\n' <"$TEMP_DIR/body")" == REDIS_DEGRADED ]] ||
    fail EXPLICIT_REDIS_DEGRADATION_NOT_REPORTED

after_loss_hash="$(durable_state_hash)"
[[ "$after_loss_hash" == "$before_hash" ]] || fail POSTGRES_DURABLE_STATE_CHANGED_DURING_REDIS_LOSS

compose start redis >/dev/null
recovered=0
for _attempt in $(seq 1 45); do
    redis_code="$(http_probe /health/ready/ephemeral-redis)"
    if [[ "$redis_code" == 200 && "$(tr -d '\r\n' <"$TEMP_DIR/body")" == REDIS_READY ]]; then
        recovered=1
        break
    fi
    sleep 1
done
[[ "$recovered" == 1 ]] || fail REDIS_RECONNECT_DID_NOT_RECOVER

after_recovery_hash="$(durable_state_hash)"
[[ "$after_recovery_hash" == "$before_hash" ]] || fail POSTGRES_DURABLE_STATE_CHANGED_AFTER_REDIS_RECOVERY
[[ "$(docker inspect --format '{{.Id}}' "$app_id")" == "$app_id" ]] || fail APPLICATION_RESTARTED_DURING_REDIS_LOSS

printf 'REDIS_DEDICATED_RUNTIME_AUTH=PASS\n'
printf 'REDIS_APP_ADMIN_COMMAND_DENIED=PASS\n'
printf 'REDIS_PERSISTENCE_DISABLED=PASS\n'
printf 'REDIS_EXPLICIT_DEGRADATION=PASS\n'
printf 'DURABLE_READINESS_DURING_REDIS_LOSS=PASS\n'
printf 'POSTGRES_DURABLE_HASH_REPRODUCIBLE=PASS\n'
printf 'POSTGRES_DURABLE_HASH_PRESERVED=PASS\n'
printf 'REDIS_RECONNECT_RECOVERY=PASS\n'
printf 'APPLICATION_RESTARTS_DURING_REDIS_LOSS=0\n'
printf 'REDIS_LOSS_DURABLE_IMPACT=0\n'
printf 'REDIS_LOSS_DURABLE_ISOLATION=PASS\n'
