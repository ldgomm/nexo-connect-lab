#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${CONNECT_LAB_ENV_FILE:-}"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"

fail() {
    printf 'EPHEMERAL_SIGNAL_RESILIENCE_RUNTIME=FAIL\n' >&2
    printf 'ERROR=%s\n' "$1" >&2
    exit 1
}

env_value() {
    local key="$1"
    awk -F= -v target="$key" '
        $1 == target { count++; value = substr($0, index($0, "=") + 1) }
        END { if (count != 1) exit 2; print value }
    ' "$ENV_FILE"
}

compose() {
    docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

redis_admin() {
    local redis_id
    redis_id="$(compose ps -q redis)"
    [[ -n "$redis_id" ]] || return 1
    docker exec \
        -e REDISCLI_AUTH="$(env_value CONNECT_LAB_REDIS_PASSWORD)" \
        "$redis_id" redis-cli --no-auth-warning "$@"
}

signal_key_count() {
    local pattern="$1"
    redis_admin --scan --pattern "$pattern" | awk 'NF { count++ } END { print count + 0 }'
}

clear_signal_keys() {
    local pattern="$1"
    local key
    while IFS= read -r key; do
        [[ -z "$key" ]] || redis_admin DEL "$key" >/dev/null
    done < <(redis_admin --scan --pattern "$pattern")
}

durable_hash() {
    local postgres_id
    postgres_id="$(compose ps -q postgres)"
    [[ -n "$postgres_id" ]] || return 1
    docker exec \
        -e PGPASSWORD="$(env_value CONNECT_LAB_POSTGRES_PASSWORD)" \
        "$postgres_id" \
        pg_dump \
        --username="$(env_value CONNECT_LAB_POSTGRES_USER)" \
        --dbname="$(env_value CONNECT_LAB_DATABASE_NAME)" \
        --schema=connect \
        --no-owner \
        --no-privileges \
        --restrict-key=NexoConnectLabConnect20EphemeralResilienceV1 |
        shasum -a 256 |
        awk '{ print $1 }'
}

cd "$PROJECT_DIR"

[[ -n "$ENV_FILE" && -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail RUNTIME_ENV_MISSING_OR_UNSAFE
command -v docker >/dev/null 2>&1 || fail DOCKER_MISSING
[[ -n "$(compose ps -q redis)" && -n "$(compose ps -q postgres)" ]] || fail STACK_NOT_RUNNING

clear_signal_keys 'nexo-connect-lab:presence:v1:*' || fail PRESENCE_PRE_CLEANUP_FAILED
clear_signal_keys 'nexo-connect-lab:typing:v1:*' || fail TYPING_PRE_CLEANUP_FAILED
[[ "$(signal_key_count 'nexo-connect-lab:presence:v1:*')" == "0" ]] || fail PRESENCE_KEY_PRECONDITION_FAILED
[[ "$(signal_key_count 'nexo-connect-lab:typing:v1:*')" == "0" ]] || fail TYPING_KEY_PRECONDITION_FAILED
before_hash="$(durable_hash)" || fail DURABLE_HASH_BEFORE_FAILED

CONNECT_LAB_REDIS_EPHEMERAL_RESILIENCE_INTEGRATION=true \
CONNECT_LAB_TEST_CLOCK_OFFSET_SECONDS=315360000 \
CONNECT_LAB_REDIS_PASSWORD="$(env_value CONNECT_LAB_REDIS_PASSWORD)" \
CONNECT_LAB_REDIS_HOST=127.0.0.1 \
CONNECT_LAB_REDIS_PORT="$(env_value CONNECT_LAB_REDIS_HOST_PORT)" \
CONNECT_LAB_REDIS_APP_USER="$(env_value CONNECT_LAB_REDIS_APP_USER)" \
CONNECT_LAB_REDIS_APP_PASSWORD="$(env_value CONNECT_LAB_REDIS_APP_PASSWORD)" \
CONNECT_LAB_REDIS_NAMESPACE="$(env_value CONNECT_LAB_REDIS_NAMESPACE)" \
CONNECT_LAB_REDIS_CHANNEL_NAMESPACE="$(env_value CONNECT_LAB_REDIS_CHANNEL_NAMESPACE)" \
CONNECT_LAB_REDIS_DATABASE="$(env_value CONNECT_LAB_REDIS_DATABASE)" \
CONNECT_LAB_REDIS_CONNECT_TIMEOUT_MILLIS="$(env_value CONNECT_LAB_REDIS_CONNECT_TIMEOUT_MILLIS)" \
CONNECT_LAB_REDIS_COMMAND_TIMEOUT_MILLIS="$(env_value CONNECT_LAB_REDIS_COMMAND_TIMEOUT_MILLIS)" \
CONNECT_LAB_REDIS_RECONNECT_MIN_DELAY_MILLIS="$(env_value CONNECT_LAB_REDIS_RECONNECT_MIN_DELAY_MILLIS)" \
CONNECT_LAB_REDIS_RECONNECT_MAX_DELAY_MILLIS="$(env_value CONNECT_LAB_REDIS_RECONNECT_MAX_DELAY_MILLIS)" \
CONNECT_LAB_REDIS_REQUEST_QUEUE_SIZE="$(env_value CONNECT_LAB_REDIS_REQUEST_QUEUE_SIZE)" \
./gradlew --no-daemon ephemeralSignalResilienceTest --rerun-tasks --console=plain

after_hash="$(durable_hash)" || fail DURABLE_HASH_AFTER_FAILED
[[ "$before_hash" == "$after_hash" ]] || fail POSTGRES_DURABLE_STATE_CHANGED_DURING_FAILURE_INJECTION
stale_presence_count="$(signal_key_count 'nexo-connect-lab:presence:v1:*')" || fail PRESENCE_POSTCHECK_FAILED
stale_typing_count="$(signal_key_count 'nexo-connect-lab:typing:v1:*')" || fail TYPING_POSTCHECK_FAILED
[[ "$stale_presence_count" == "0" ]] || fail STALE_PRESENCE_KEYS_REMAIN
[[ "$stale_typing_count" == "0" ]] || fail STALE_TYPING_KEYS_REMAIN

printf 'EPHEMERAL_RESILIENCE_REAL_REDIS=PASS\n'
printf 'REDIS_FLUSH_RECOVERY=PASS\n'
printf 'CLOCK_SKEW_TOLERANCE=PASS\n'
printf 'DUPLICATE_REFRESH_IDEMPOTENT=PASS\n'
printf 'INSTANCE_CRASH_EXPIRY=PASS\n'
printf 'RAPID_RECONNECT_OWNERSHIP=PASS\n'
printf 'STALE_PRESENCE_KEY_COUNT=0\n'
printf 'STALE_TYPING_KEY_COUNT=0\n'
printf 'POSTGRES_DURABLE_HASH_PRESERVED=PASS\n'
printf 'DURABLE_MESSAGE_CHANGES=0\n'
printf 'DURABLE_RECEIPT_CHANGES=0\n'
printf 'NEXO_DB_DIRECT_ACCESS=0\n'
printf 'EPHEMERAL_SIGNAL_RESILIENCE_RUNTIME=PASS\n'
