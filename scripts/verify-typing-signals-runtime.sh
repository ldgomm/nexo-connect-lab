#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${CONNECT_LAB_ENV_FILE:-}"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"

fail() {
    printf 'REDIS_TYPING_SIGNAL_RUNTIME=FAIL\n' >&2
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

typing_key_count() {
    redis_admin --scan --pattern 'nexo-connect-lab:typing:v1:*' |
        awk 'NF { count++ } END { print count + 0 }'
}

clear_typing_keys() {
    local key
    while IFS= read -r key; do
        [[ -z "$key" ]] || redis_admin DEL "$key" >/dev/null
    done < <(redis_admin --scan --pattern 'nexo-connect-lab:typing:v1:*')
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
        --restrict-key=NexoConnectLabConnect18TypingV1 |
        shasum -a 256 |
        awk '{ print $1 }'
}

cd "$PROJECT_DIR"

[[ -n "$ENV_FILE" && -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail RUNTIME_ENV_MISSING_OR_UNSAFE
command -v docker >/dev/null 2>&1 || fail DOCKER_MISSING
[[ -n "$(compose ps -q redis)" && -n "$(compose ps -q postgres)" ]] || fail STACK_NOT_RUNNING

clear_typing_keys || fail TYPING_KEY_PRE_CLEANUP_FAILED
[[ "$(typing_key_count)" == "0" ]] || fail TYPING_KEY_PRECONDITION_FAILED
before_hash="$(durable_hash)" || fail DURABLE_HASH_BEFORE_FAILED

CONNECT_LAB_REDIS_TYPING_INTEGRATION=true \
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
./gradlew --no-daemon test \
    --tests 'com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.RedisTypingLeaseIntegrationTest' \
    --tests 'com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.RedisTypingSignalFanoutIntegrationTest' \
    --rerun-tasks --console=plain

after_hash="$(durable_hash)" || fail DURABLE_HASH_AFTER_FAILED
[[ "$before_hash" == "$after_hash" ]] || fail POSTGRES_DURABLE_STATE_CHANGED_DURING_TYPING_PROBE
stale_count="$(typing_key_count)" || fail TYPING_KEY_POSTCHECK_FAILED
[[ "$stale_count" == "0" ]] || fail TYPING_STALE_KEYS_REMAIN

printf 'TYPING_LEASE_REAL_REDIS=PASS\n'
printf 'TYPING_MULTI_INSTANCE_FANOUT=PASS\n'
printf 'TYPING_STALE_KEY_COUNT=0\n'
printf 'POSTGRES_DURABLE_HASH_PRESERVED=PASS\n'
printf 'TYPING_POSTGRES_MUTABLE_WRITES=0\n'
printf 'REDIS_TYPING_SIGNAL_RUNTIME=PASS\n'
