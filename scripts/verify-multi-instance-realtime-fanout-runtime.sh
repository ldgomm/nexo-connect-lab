#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
ENV_FILE="${CONNECT_LAB_ENV_FILE:-}"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"

fail() {
    printf 'MULTI_INSTANCE_REALTIME_FANOUT_RUNTIME=FAIL\n' >&2
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
        --restrict-key=NexoConnectLabConnect13FanoutV1 |
        shasum -a 256 |
        awk '{ print $1 }'
}

cd "$PROJECT_DIR"

[[ -n "$ENV_FILE" && -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail RUNTIME_ENV_MISSING_OR_UNSAFE
command -v docker >/dev/null 2>&1 || fail DOCKER_MISSING
[[ -n "$(compose ps -q redis)" && -n "$(compose ps -q postgres)" ]] || fail STACK_NOT_RUNNING

before_hash="$(durable_hash)" || fail DURABLE_HASH_BEFORE_FAILED

CONNECT_LAB_REDIS_FANOUT_INTEGRATION=true \
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
    --tests 'com.premierdarkcoffee.nexo.connect.lab.infrastructure.redis.RedisMultiInstanceRealtimeFanoutIntegrationTest' \
    --rerun-tasks --console=plain

after_hash="$(durable_hash)" || fail DURABLE_HASH_AFTER_FAILED
[[ "$before_hash" == "$after_hash" ]] || fail POSTGRES_DURABLE_STATE_CHANGED_DURING_FANOUT

printf 'TWO_INSTANCE_MESSAGE_FANOUT=PASS\n'
printf 'TWO_INSTANCE_RECEIPT_FANOUT=PASS\n'
printf 'REDIS_ORIGIN_ECHO_EXCLUDED=PASS\n'
printf 'REDIS_DUPLICATE_NOTIFICATION_SUPPRESSED=PASS\n'
printf 'REMOTE_SUBSCRIPTION_REAUTHORISED=PASS\n'
printf 'CROSS_CONVERSATION_LEAK=0\n'
printf 'POSTGRES_DURABLE_HASH_PRESERVED=PASS\n'
printf 'DURABLE_DUPLICATES=0\n'
printf 'MULTI_INSTANCE_REALTIME_FANOUT_RUNTIME=PASS\n'
